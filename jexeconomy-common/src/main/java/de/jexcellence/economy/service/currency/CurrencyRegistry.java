package de.jexcellence.economy.service.currency;

import de.jexcellence.economy.api.CurrencyPolicy;
import de.jexcellence.economy.api.CurrencySnapshot;
import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.database.repository.CurrencyRepository;
import de.jexcellence.jexplatform.logging.JExLogger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The currencies of the server: the cache of currency entities, their behaviour flags and the startup seeding from
 * {@code currencies.yml}.
 *
 * <p>Cached entities are shared read-only. The flags (payable, resettable, whole numbers) are resolved per
 * identifier from the typed {@link CurrencyConfig} and the policies other plugins registered, never stored on the
 * entity. All loading and seeding methods block and run off the main thread.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public final class CurrencyRegistry {

    /** Identifier of the soft currency used as the {@code /pay} default. */
    public static final String DEFAULT_PAY_CURRENCY = "coins";

    private static final String CONFIG_FILE = "currencies.yml";

    /**
     * Currencies removed by the two-currency consolidation (coins and crystals). Includes {@code credits}: a
     * {@code currencies.yml} from before the rename would otherwise keep seeding it while {@code crystals} never
     * exists.
     */
    private static final List<String> REMOVED_CURRENCIES = List.of("sky_shards", "celestial_gems", "credits");
    private static final String LEGACY_PURGE_MARKER = "database/legacy-currency-purge.v1.done";

    private final CurrencyRepository repository;
    private final JavaPlugin plugin;
    private final JExLogger logger;
    private final Map<String, Currency> currencies = new ConcurrentHashMap<>();
    private final Map<String, CurrencyPolicy> registeredPolicies = new ConcurrentHashMap<>();
    private final AtomicReference<CurrencyConfig> config = new AtomicReference<>(CurrencyConfig.defaults());

    /**
     * Creates the registry.
     *
     * @param repository the currency repository
     * @param plugin     the plugin, for the data folder and the bundled {@code currencies.yml}
     * @param logger     the plugin logger
     */
    public CurrencyRegistry(@NotNull CurrencyRepository repository, @NotNull JavaPlugin plugin,
                            @NotNull JExLogger logger) {
        this.repository = repository;
        this.plugin = plugin;
        this.logger = logger;
    }

    /**
     * Startup sequence: read the config, load the cache, purge the removed legacy currencies, then create missing
     * configured and required currencies. The purge runs before the seeding so a purged id is replaced by its
     * successor; seeding never overwrites an existing currency, so operator edits survive a restart. Blocking.
     */
    public void bootstrap() {
        readConfig();
        loadAll();
        purgeRemovedOnce();
        seedConfigured();
        ensureRequired();
        logger.info("Currency registry ready - {} currencies", currencies.size());
        warnWithoutVaultCurrency();
    }

    /**
     * Reloads the cache and the config and creates currencies newly added to {@code currencies.yml}. Blocking.
     */
    public void reload() {
        readConfig();
        loadAll();
        seedConfigured();
    }

    private void readConfig() {
        var file = new File(plugin.getDataFolder(), CONFIG_FILE);
        if (!file.exists()) {
            plugin.saveResource(CONFIG_FILE, false);
        }
        if (!file.exists()) {
            config.set(CurrencyConfig.defaults());
            return;
        }
        config.set(CurrencyConfig.read(YamlConfiguration.loadConfiguration(file),
                warning -> logger.warn("{}: {}", CONFIG_FILE, warning)));
    }

    private void loadAll() {
        var loaded = new LinkedHashMap<String, Currency>();
        repository.findAll().forEach(currency -> loaded.put(currency.getIdentifier(), currency));
        currencies.putAll(loaded);
        currencies.keySet().retainAll(loaded.keySet());
        logger.info("Loaded {} currencies into cache", currencies.size());
        loaded.keySet().stream().filter(ReservedCurrencyNames::isReserved).forEach(identifier -> logger.warn(
                "Currency '{}' uses a reserved name: /balance {} opens the subcommand, not the currency. "
                        + "Rename it with /currencies edit {} identifier <new id>.",
                identifier, identifier, identifier));
    }

    /**
     * Removes the retired legacy currencies once per installation. The marker file records the run, so a currency
     * an operator creates later under one of these names is kept. A failed purge leaves no marker and runs again.
     */
    private void purgeRemovedOnce() {
        var marker = plugin.getDataFolder().toPath().resolve(LEGACY_PURGE_MARKER);
        if (Files.exists(marker)) {
            return;
        }
        boolean complete = true;
        for (String identifier : REMOVED_CURRENCIES) {
            var currency = currencies.get(identifier);
            if (currency != null && !purgeRemovedCurrency(currency)) {
                complete = false;
            }
        }
        if (complete) {
            writeMarker(marker);
        }
    }

    private boolean purgeRemovedCurrency(@NotNull Currency currency) {
        try {
            var result = repository.purge(currency.getId());
            currencies.remove(currency.getIdentifier());
            logger.info("Purged removed currency '{}' ({} account(s), {} log(s))", currency.getIdentifier(),
                    result.accounts(), result.logs());
            return true;
        } catch (RuntimeException failure) {
            logger.error("Failed to purge removed currency '{}': {}", currency.getIdentifier(),
                    failure.getMessage(), failure);
            return false;
        }
    }

    private void writeMarker(@NotNull Path marker) {
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "at=" + Instant.now() + System.lineSeparator());
        } catch (IOException failure) {
            logger.warn("Could not write {}: {}", marker, failure.getMessage());
        }
    }

    private void warnWithoutVaultCurrency() {
        if (vaultCurrency().isEmpty()) {
            logger.warn("vault.currency '{}' in {} does not exist; Vault plugins see no economy until it does.",
                    config.get().vaultCurrency(), CONFIG_FILE);
        }
    }

    private void seedConfigured() {
        int seeded = 0;
        for (var seed : config.get().currencies()) {
            if (!currencies.containsKey(seed.identifier()) && create(seed)) {
                seeded++;
            }
        }
        if (seeded > 0) {
            logger.info("Seeded {} currency definitions from {}", seeded, CONFIG_FILE);
        }
    }

    private void ensureRequired() {
        for (var seed : CurrencyConfig.REQUIRED) {
            if (!currencies.containsKey(seed.identifier()) && create(seed)) {
                logger.warn("Currency '{}' was missing - created with defaults. Check plugins/JExEconomy/{} "
                        + "so operator overrides survive a restart.", seed.identifier(), CONFIG_FILE);
            }
        }
    }

    /**
     * Creates a currency another plugin registered, unless it exists. Blocking.
     *
     * @param seed the currency
     * @return {@code true} when the currency exists afterwards
     */
    public boolean register(@NotNull CurrencyConfig.Seed seed) {
        if (currencies.containsKey(seed.identifier())) {
            return true;
        }
        boolean created = create(seed);
        if (created) {
            logger.info("Registered currency '{}'", seed.identifier());
        }
        return created;
    }

    private boolean create(@NotNull CurrencyConfig.Seed seed) {
        if (ReservedCurrencyNames.isReserved(seed.identifier())) {
            logger.warn("Currency '{}' not created: the name is reserved for a /balance subcommand ({})",
                    seed.identifier(), String.join(", ", ReservedCurrencyNames.names()));
            return false;
        }
        try {
            put(repository.create(seed.toEntity()));
            return true;
        } catch (RuntimeException failure) {
            logger.error("Failed to create currency '{}': {}", seed.identifier(), failure.getMessage(), failure);
            return false;
        }
    }

    /**
     * Puts a saved currency into the cache.
     *
     * @param currency the saved currency
     */
    public void put(@NotNull Currency currency) {
        currencies.put(currency.getIdentifier(), currency);
    }

    /**
     * Replaces a cached currency with its saved copy, also when the identifier changed.
     *
     * @param previousIdentifier the identifier before the update
     * @param saved              the saved copy
     */
    public void replace(@NotNull String previousIdentifier, @NotNull Currency saved) {
        if (!previousIdentifier.equals(saved.getIdentifier())) {
            currencies.remove(previousIdentifier);
        }
        currencies.put(saved.getIdentifier(), saved);
    }

    /**
     * Drops a currency from the cache.
     *
     * @param identifier the identifier
     */
    public void remove(@NotNull String identifier) {
        currencies.remove(identifier);
    }

    /**
     * Looks up a cached currency.
     *
     * @param identifier the identifier
     * @return the currency, or empty
     */
    public @NotNull Optional<Currency> find(@NotNull String identifier) {
        return Optional.ofNullable(currencies.get(identifier));
    }

    /**
     * All cached currencies, keyed by identifier.
     *
     * @return an unmodifiable view
     */
    public @NotNull Map<String, Currency> all() {
        return Collections.unmodifiableMap(currencies);
    }

    /**
     * Snapshots of all currencies.
     *
     * @return an unmodifiable map of identifier to snapshot
     */
    public @NotNull Map<String, CurrencySnapshot> snapshots() {
        var result = new LinkedHashMap<String, CurrencySnapshot>();
        currencies.forEach((identifier, currency) -> result.put(identifier, currency.toSnapshot()));
        return Collections.unmodifiableMap(result);
    }

    /**
     * The currency {@code /pay} uses when the sender names none: {@code coins}, else the first payable currency.
     *
     * @return the default pay currency, or empty without a payable currency
     */
    public @NotNull Optional<Currency> defaultPayCurrency() {
        var coins = currencies.get(DEFAULT_PAY_CURRENCY);
        if (coins != null) {
            return Optional.of(coins);
        }
        return currencies.values().stream()
                .filter(currency -> policy(currency.getIdentifier()).payable())
                .findFirst();
    }

    /**
     * The one currency Vault plugins see, {@code vault.currency} in {@code currencies.yml}. There is deliberately no
     * fallback: anonymous Vault deposits must never reach a premium currency by accident.
     *
     * @return the currency, empty when it does not exist
     */
    public @NotNull Optional<Currency> vaultCurrency() {
        return find(config.get().vaultCurrency());
    }

    /**
     * The behaviour flags of a currency: a registered policy combined with the config lists.
     *
     * @param identifier the identifier
     * @return the policy
     */
    public @NotNull CurrencyPolicy policy(@NotNull String identifier) {
        var registered = registeredPolicies.getOrDefault(identifier, CurrencyPolicy.DEFAULT);
        var current = config.get();
        return new CurrencyPolicy(
                registered.payable() && !current.nonPayable().contains(identifier),
                registered.resettable() || current.resettable().contains(identifier),
                registered.wholeNumbers() || current.wholeNumbers().contains(identifier));
    }

    /**
     * Stores the policy another plugin registered for a currency.
     *
     * @param identifier the identifier
     * @param policy     the policy
     */
    public void registerPolicy(@NotNull String identifier, @NotNull CurrencyPolicy policy) {
        registeredPolicies.put(identifier, policy);
    }

    /**
     * The current {@code /pay} confirmation threshold.
     *
     * @return the threshold
     */
    public double payConfirmThreshold() {
        return config.get().payConfirmThreshold();
    }
}
