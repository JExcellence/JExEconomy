package de.jexcellence.economy;

import de.jexcellence.economy.concurrent.BoundedExecutor;
import de.jexcellence.economy.database.maintenance.DuplicateAccountMerge;
import de.jexcellence.economy.database.maintenance.UniqueIndexGuard;
import de.jexcellence.economy.database.repository.AccountRepository;
import de.jexcellence.economy.database.repository.CurrencyRepository;
import de.jexcellence.economy.database.repository.ItemReturnRepository;
import de.jexcellence.economy.database.repository.OpenTransferRepository;
import de.jexcellence.economy.database.repository.MigrationEntryRepository;
import de.jexcellence.economy.database.repository.MigrationRunRepository;
import de.jexcellence.economy.database.repository.PlayerRepository;
import de.jexcellence.economy.migration.JpaMigrationStore;
import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.database.repository.RedeemedCouponRepository;
import de.jexcellence.economy.database.repository.TransactionLogRepository;
import de.jexcellence.economy.service.EconomyServices;
import de.jexcellence.jehibernate.config.PropertyLoader;
import de.jexcellence.jehibernate.core.JEHibernate;
import de.jexcellence.jexplatform.logging.JExLogger;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.Optional;
import java.util.function.Function;
import java.util.concurrent.TimeUnit;

/**
 * Owns the economy's threads and database: the virtual-thread executor, the bounded database executor, JEHibernate
 * and the start-up maintenance. The workers carry the dependency classloader JEDependency set during {@code onLoad},
 * so Hibernate and the injected libraries resolve on every worker.
 *
 * @author JExcellence
 * @since 3.2.0
 */
final class EconomyDatabase {

    private static final String DATABASE_FOLDER = "database";
    private static final long SHUTDOWN_WAIT_SECONDS = 5L;
    private static final String POOL_SIZE_PROPERTY = "jehibernate.pool.maximumPoolSize";
    private static final int DEFAULT_POOL_SIZE = 10;
    private static final String H2_URL_PROPERTY = "h2.url";
    private static final String DEFAULT_DATA_PATH = "./plugins/JExEconomy";
    private static final Path SHIPPED_H2_FILE = Path.of(DEFAULT_DATA_PATH, DATABASE_FOLDER, "jexeconomy.mv.db");

    private final JavaPlugin plugin;
    private final JExLogger logger;
    private final ExecutorService workers;
    private ExecutorService databaseExecutor;
    private JEHibernate jeHibernate;

    EconomyDatabase(@NotNull JavaPlugin plugin, @NotNull JExLogger logger, @NotNull ClassLoader dependencyClassLoader) {
        this.plugin = plugin;
        this.logger = logger;
        ThreadFactory virtual = Thread.ofVirtual().name("jexeconomy-worker-", 0).factory();
        this.workers = Executors.newThreadPerTaskExecutor(task -> {
            Thread thread = virtual.newThread(task);
            thread.setContextClassLoader(dependencyClassLoader);
            return thread;
        });
    }

    /**
     * The unlimited workers, for work that mostly waits.
     *
     * @return the worker executor
     */
    @NotNull ExecutorService workers() {
        return workers;
    }

    /**
     * The workers limited to the capacity of the connection pool. Available after {@link #open()}.
     *
     * @return the database executor
     */
    @NotNull ExecutorService databaseExecutor() {
        return databaseExecutor;
    }

    /**
     * Copies the default files and connects. Blocking.
     */
    void open() {
        saveDefaultResource("database/hibernate.properties");
        saveDefaultResource("database/log4j.properties");
        Properties properties = PropertyLoader.load(plugin.getDataFolder(), DATABASE_FOLDER, "hibernate.properties");
        relocateH2File(properties);
        int poolSize = poolSize(properties);
        databaseExecutor = new BoundedExecutor(workers, poolSize);
        jeHibernate = JEHibernate.builder()
                .configuration(config -> config.fromProperties(properties)
                        .property("hibernate.schema_update.unique_constraint_strategy", "SKIP"))
                .executor(databaseExecutor)
                .scanPackages("de.jexcellence.economy.database")
                .build();
        logger.info("Database initialized, at most {} parallel database tasks", poolSize);
    }

    /**
     * Moves the H2 file to the data folder, unless a database already exists at the shipped location: an installation
     * with a custom plugins directory keeps using the file that holds its balances.
     */
    private void relocateH2File(@NotNull Properties properties) {
        String url = properties.getProperty(H2_URL_PROPERTY);
        if (url != null && !Files.exists(SHIPPED_H2_FILE)) {
            properties.setProperty(H2_URL_PROPERTY, withDataFolder(url, plugin.getDataFolder().toPath()));
        }
    }

    /**
     * Points the shipped relative H2 path at the plugin's real data folder, which differs on servers that run with a
     * custom plugins directory. Any other URL stays as it is.
     *
     * @param url        the configured JDBC URL
     * @param dataFolder the plugin's data folder
     * @return the URL to connect to
     */
    static @NotNull String withDataFolder(@NotNull String url, @NotNull Path dataFolder) {
        return url.replace(DEFAULT_DATA_PATH, dataFolder.toAbsolutePath().toString().replace('\\', '/'));
    }

    private int poolSize(@NotNull Properties properties) {
        String configured = properties.getProperty(POOL_SIZE_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_POOL_SIZE;
        }
        try {
            return Math.max(1, Integer.parseInt(configured.trim()));
        } catch (NumberFormatException invalid) {
            logger.warn("{} is not a number ({}), using {}", POOL_SIZE_PROPERTY, configured, DEFAULT_POOL_SIZE);
            return DEFAULT_POOL_SIZE;
        }
    }

    private void saveDefaultResource(@NotNull String resourcePath) {
        var target = new File(plugin.getDataFolder(), resourcePath.replace('/', File.separatorChar));
        if (!target.exists()) {
            plugin.saveResource(resourcePath, false);
        }
    }

    /**
     * The repositories the economy services need.
     *
     * @return the repositories
     */
    @NotNull EconomyServices.Repositories economyRepositories() {
        var repositories = jeHibernate.repositories();
        return new EconomyServices.Repositories(repositories.get(CurrencyRepository.class),
                repositories.get(PlayerRepository.class), repositories.get(AccountRepository.class),
                repositories.get(OpenTransferRepository.class));
    }

    /**
     * The currency items waiting for their owners.
     *
     * @return the repository
     */
    @NotNull ItemReturnRepository itemReturns() {
        return jeHibernate.repositories().get(ItemReturnRepository.class);
    }

    /**
     * The transaction log repository.
     *
     * @return the repository
     */
    @NotNull TransactionLogRepository transactionLogs() {
        return jeHibernate.repositories().get(TransactionLogRepository.class);
    }

    /**
     * The store of balance imports.
     *
     * @param currencies finds a currency by id
     * @return the store
     */
    @NotNull JpaMigrationStore migrationStore(@NotNull Function<String, Optional<Currency>> currencies) {
        var repositories = jeHibernate.repositories();
        return new JpaMigrationStore(new JpaMigrationStore.Repositories(repositories.get(MigrationRunRepository.class),
                repositories.get(MigrationEntryRepository.class), repositories.get(AccountRepository.class),
                repositories.get(TransactionLogRepository.class)), currencies);
    }

    /**
     * The coupon redemption repository.
     *
     * @return the repository
     */
    @NotNull RedeemedCouponRepository redeemedCoupons() {
        return jeHibernate.repositories().get(RedeemedCouponRepository.class);
    }

    /**
     * Runs the one-time duplicate-account merge and then creates every unique index an existing installation lacks,
     * see {@link UniqueIndexGuard}. Blocking.
     */
    void runMaintenance() {
        var accounts = jeHibernate.repositories().get(AccountRepository.class);
        var databaseFolder = plugin.getDataFolder().toPath().resolve(DATABASE_FOLDER);
        try {
            new DuplicateAccountMerge(accounts, databaseFolder, logger).runOnce();
        } catch (RuntimeException failure) {
            logger.error("Duplicate account merge failed, it runs again on the next start: {}",
                    failure.getMessage(), failure);
        }
        var guard = new UniqueIndexGuard(jeHibernate.getDataSource(), message -> logger.warn("{}", message));
        for (UniqueIndexGuard.Requirement requirement : UniqueIndexGuard.ECONOMY) {
            guard.ensure(requirement);
        }
    }

    /**
     * Stops the workers, giving running economy work a moment to finish, and closes the database.
     */
    void close() {
        workers.shutdown();
        try {
            if (!workers.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                logger.warn("Economy work still running after {}s, stopping it", SHUTDOWN_WAIT_SECONDS);
                workers.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
        if (jeHibernate != null) {
            jeHibernate.close();
        }
    }
}
