package de.jexcellence.economy;

import de.jexcellence.economy.bedrock.EconomyForms;
import de.jexcellence.economy.coupon.CouponService;
import de.jexcellence.economy.coupon.CouponTemplateRegistry;
import de.jexcellence.economy.database.repository.RedeemedCouponRepository;
import de.jexcellence.economy.database.repository.TransactionLogRepository;
import de.jexcellence.economy.extension.ExtensionHost;
import de.jexcellence.economy.migration.MigrationService;
import de.jexcellence.economy.migration.MigrationSources;
import de.jexcellence.economy.service.EconomyService;
import de.jexcellence.economy.service.EconomyServices;
import de.jexcellence.economy.service.TransactionLogger;
import de.jexcellence.economy.view.CurrencyChatInput;
import de.jexcellence.jexplatform.JExPlatform;
import de.jexcellence.jexplatform.logging.JExLogger;
import de.jexcellence.jexplatform.logging.LogLevel;
import me.devnatan.inventoryframework.ViewFrame;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Core entry point for JExEconomy: plugin lifecycle, database, services, views, commands and integrations across
 * the single plugin jar.
 *
 * <p>Startup never blocks the main thread: {@link #onEnable()} builds the database, the services, runs the one-time
 * duplicate-account merge and loads the currencies on the economy executor, then hands the Bukkit registrations
 * (services, listeners, views, commands, PlaceholderAPI) to the main thread through the platform scheduler. Only the
 * Vault provider registers at once, because Vault plugins look it up in their own {@code onEnable}.
 * The executor's threads carry the dependency classloader JEDependency set during {@code onLoad}, so Hibernate and
 * the injected libraries resolve on every worker.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public final class JExEconomy {

    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(10L);
    private static final Duration STARTUP_WAIT = Duration.ofSeconds(10L);
    private static final long INTERRUPTED_PAYMENT_CHECK_TICKS = 2_400L;

    private final JavaPlugin plugin;
    private ExtensionHost extensions;

    private JExPlatform platform;
    private CompletableFuture<Void> startup = CompletableFuture.completedFuture(null);
    private EconomyDatabase database;
    private EconomyIntegrations integrations;
    private volatile EconomyService economyService;
    private volatile boolean vaultOpen;
    private TransactionLogger transactionLogger;
    private TransactionLogRepository transactionLogRepo;
    private ViewFrame viewFrame;
    private CurrencyChatInput chatInput;
    private EconomyForms bedrockForms;
    private boolean anvilSupported;
    private JExLogger logger;
    private CouponService couponService;
    private MigrationService migration;
    private CouponTemplateRegistry couponRegistry;
    private RedeemedCouponRepository couponRepo;

    /**
     * Creates the core for its plugin.
     *
     * @param plugin the Bukkit plugin instance
     */
    public JExEconomy(@NotNull JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Builds the platform during the server load phase.
     */
    public void onLoad() {
        platform = JExPlatform.builder(plugin)
                .withLogLevel(LogLevel.INFO)
                .enableTranslations("en_US", "de_DE")
                .build();
        logger = platform.logger();
        logger.info("Loading JExEconomy");
    }

    /**
     * Starts the asynchronous enable sequence, see the class documentation.
     */
    public void onEnable() {
        logger.info("Enabling JExEconomy...");
        database = new EconomyDatabase(plugin, logger, Thread.currentThread().getContextClassLoader());
        extensions = new ExtensionHost(plugin.getServer().getServicesManager(), logger);
        integrations = new EconomyIntegrations(plugin, platform, logger, extensions);
        integrations.followExtensions();
        vaultOpen = true;
        integrations.registerVault(() -> vaultOpen ? economyService : null);
        startup = platform.initialize()
                .thenRunAsync(() -> {
                    database.open();
                    initializeServices();
                    database.runMaintenance();
                }, database.workers())
                .thenCompose(ignored -> economyService.bootstrap());
        startup.thenRun(this::scheduleCompleteEnable).exceptionally(this::handleStartupFailure);
    }

    private void scheduleCompleteEnable() {
        if (plugin.isEnabled()) {
            platform.scheduler().runSync(this::completeEnable);
        }
    }

    private void completeEnable() {
        if (!plugin.isEnabled()) {
            return;
        }
        try {
            integrations.registerServices(economyService);
            integrations.registerListeners(economyService, couponService, database.itemReturns());
            registerViews();
            EconomyCommands.register(this);
            integrations.registerPlaceholders(economyService);
            migration.recoverInterrupted();
            platform.scheduler().runDelayed(economyService::reportInterruptedTransfers,
                    INTERRUPTED_PAYMENT_CHECK_TICKS);
            logger.info("JExEconomy enabled - {} currencies", economyService.getAllCurrencies().size());
        } catch (RuntimeException failure) {
            handleStartupFailure(failure);
        }
    }

    private Void handleStartupFailure(@NotNull Throwable failure) {
        logger.error("Failed to enable JExEconomy: {}", failure.getMessage(), failure);
        if (plugin.isEnabled()) {
            platform.scheduler().runSync(() -> plugin.getServer().getPluginManager().disablePlugin(plugin));
        }
        return null;
    }

    /**
     * Unregisters services, lets running economy work finish and releases resources.
     */
    public void onDisable() {
        awaitStartup();
        vaultOpen = false;
        if (integrations != null) {
            integrations.unregister();
        }
        drainLedger();
        if (database != null) {
            database.close();
        }
        if (platform != null) {
            platform.shutdown();
        }
        logger.info("JExEconomy disabled");
    }

    /**
     * Waits for a start that is still running on the workers, so the database is not closed underneath it and the
     * drain sees the services. A failed start was already reported where it failed.
     */
    private void awaitStartup() {
        try {
            startup.get(STARTUP_WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException stillRunning) {
            logger.warn("Startup still running after {}s, stopping anyway", STARTUP_WAIT.toSeconds());
        } catch (ExecutionException failedStart) {
            logger.debug("Stopping after a failed start: {}", failedStart.getMessage());
        }
    }

    /**
     * Lets queued balance changes finish (at most {@code DRAIN_TIMEOUT}) before the executor stops and the database
     * closes, so a transfer whose withdrawal was booked also books its deposit or refund.
     */
    private void drainLedger() {
        if (economyService != null) {
            economyService.drain(DRAIN_TIMEOUT);
        }
    }

    /**
     * Builds the services; currencies are loaded and seeded afterwards by {@link EconomyService#bootstrap()}.
     */
    private void initializeServices() {
        transactionLogRepo = database.transactionLogs();
        transactionLogger = new TransactionLogger(transactionLogRepo, logger);
        economyService = EconomyServices.create(database.economyRepositories(), transactionLogger,
                new EconomyServices.Context(logger, platform.scheduler(), plugin, database.databaseExecutor(),
                        database.workers()));
        economyService.setTransferGuard((first, second) -> extensions.current().isTradeBlocked(first, second));
        economyService.setDisplayBalanceSource((player, currency) ->
                extensions.current().displayBalance(player, currency));
        economyService.setExternalBalanceSource((player, currency) ->
                extensions.current().isExternalBalance(player, currency));
        economyService.setDepositRedirect(extensions.depositRedirect());
        economyService.setTransferRoute(extensions.transferRoute());

        couponRepo = database.redeemedCoupons();
        couponRegistry = new CouponTemplateRegistry(logger);
        couponRegistry.reload(plugin);
        couponService = new CouponService(couponRepo, couponRegistry, economyService, logger);
        var migrationSources = new MigrationSources(plugin, platform.scheduler(), database.workers());
        migration = new MigrationService(database.migrationStore(economyService::findCurrency),
                economyService::submitLedgerRequest, migrationSources::detect,
                plugin.getDataFolder().toPath().resolve("migrations"), database.workers(), logger);
    }

    private void registerViews() {
        anvilSupported = EconomyViews.anvilSupported();
        viewFrame = EconomyViews.register(plugin, anvilSupported);
        logger.info("ViewFrame registered (anvil-input={})", anvilSupported);
        chatInput = new CurrencyChatInput(this);
        chatInput.register();
        bedrockForms = new EconomyForms(this);
    }

    /**
     * The Bukkit plugin instance.
     *
     * @return the Bukkit plugin instance
     */
    public @NotNull JavaPlugin getPlugin() {
        return plugin;
    }

    /**
     * The JExPlatform instance.
     *
     * @return the JExPlatform instance
     */
    public @NotNull JExPlatform platform() {
        return platform;
    }

    /**
     * The balance import.
     *
     * @return the balance import
     */
    public @NotNull MigrationService migration() {
        return migration;
    }

    /**
     * The economy service.
     *
     * @return the economy service
     */
    public @NotNull EconomyService economyService() {
        return economyService;
    }

    /**
     * The coupon template registry.
     *
     * @return the coupon template registry
     */
    public @NotNull CouponTemplateRegistry couponRegistry() {
        return couponRegistry;
    }

    /**
     * The coupon service.
     *
     * @return the coupon service
     */
    public @NotNull CouponService couponService() {
        return couponService;
    }

    /**
     * The coupon redemption repository.
     *
     * @return the coupon redemption repository
     */
    public @NotNull RedeemedCouponRepository couponRepo() {
        return couponRepo;
    }

    /**
     * The transaction logger.
     *
     * @return the transaction logger
     */
    public @NotNull TransactionLogger transactionLogger() {
        return transactionLogger;
    }

    /**
     * The transaction log repository.
     *
     * @return the transaction log repository
     */
    public @NotNull TransactionLogRepository transactionLogRepo() {
        return transactionLogRepo;
    }

    /**
     * The plugin logger.
     *
     * @return the plugin logger
     */
    public @NotNull JExLogger logger() {
        return logger;
    }

    /**
     * The InventoryFramework view frame.
     *
     * @return the InventoryFramework view frame
     */
    public @NotNull ViewFrame viewFrame() {
        return viewFrame;
    }

    /**
     * The chat input fallback for currency fields.
     *
     * @return the chat input fallback for currency fields
     */
    public @NotNull CurrencyChatInput chatInput() {
        return chatInput;
    }

    /**
     * The Bedrock form mirror of the currency views.
     *
     * @return the Bedrock form mirror of the currency views
     */
    public @NotNull EconomyForms bedrockForms() {
        return bedrockForms;
    }

    /**
     * Whether the anvil field input can open on this server.
     *
     * @return whether the anvil field input can open on this server
     */
    public boolean anvilSupported() {
        return anvilSupported;
    }

    /**
     * The registered economy extensions.
     *
     * @return the host that follows the current extension
     */
    public @NotNull ExtensionHost extensions() {
        return extensions;
    }
}
