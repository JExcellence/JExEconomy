package de.jexcellence.economy.service.currency;

import de.jexcellence.economy.database.entity.Currency;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Typed form of {@code currencies.yml}. The defaults live here in code; the bundled file only repeats the values an
 * operator is expected to edit, and a key that is missing falls back to its default.
 *
 * <pre>
 * currencies:            # currencies created on startup when missing (never overwritten)
 *   coins: { symbol: "$", prefix: "", suffix: "", icon: GOLD_INGOT }
 * non-payable: [crystals] # replaces the default when present, an empty list allows every currency
 * pay-confirm-threshold: 100000
 * resettable: []          # optional
 * whole-numbers: []       # optional
 * vault:                  # optional
 *   currency: coins       # the one currency Vault plugins see; must exist
 * </pre>
 *
 * @param currencies          currencies seeded on startup
 * @param nonPayable          currencies {@code /pay} refuses
 * @param resettable          currencies a bulk reset may zero
 * @param wholeNumbers        currencies shown without decimals
 * @param payConfirmThreshold {@code /pay} amounts from this value on ask for a confirmation
 * @param vaultCurrency       the one currency Vault plugins see
 * @author JExcellence
 * @since 3.1.0
 */
public record CurrencyConfig(@NotNull List<Seed> currencies,
                             @NotNull Set<String> nonPayable,
                             @NotNull Set<String> resettable,
                             @NotNull Set<String> wholeNumbers,
                             double payConfirmThreshold,
                             @NotNull String vaultCurrency) {

    /** Default {@code pay-confirm-threshold}. */
    public static final double DEFAULT_PAY_CONFIRM_THRESHOLD = 100_000.0;

    /** The currency Vault plugins see when {@code vault.currency} is not set. */
    public static final String DEFAULT_VAULT_CURRENCY = "coins";

    private static final String KEY_CURRENCIES = "currencies";
    private static final String KEY_NON_PAYABLE = "non-payable";
    private static final String KEY_RESETTABLE = "resettable";
    private static final String KEY_WHOLE_NUMBERS = "whole-numbers";
    private static final String KEY_THRESHOLD = "pay-confirm-threshold";
    private static final String KEY_VAULT_CURRENCY = "vault.currency";
    private static final String DEFAULT_ICON = "GOLD_INGOT";
    private static final int MAX_IDENTIFIER = 32;
    private static final int MAX_SYMBOL = 8;
    private static final int MAX_AFFIX = 32;
    private static final int MAX_ICON = 64;

    /** The currency every server has. */
    public static final List<Seed> REQUIRED = List.of(new Seed("coins", "$", "", "", DEFAULT_ICON));

    /**
     * Copies the collections so the record stays immutable.
     *
     * @param currencies          currencies seeded on startup
     * @param nonPayable          currencies {@code /pay} refuses
     * @param resettable          currencies a bulk reset may zero
     * @param wholeNumbers        currencies shown without decimals
     * @param payConfirmThreshold the confirmation threshold
     */
    public CurrencyConfig {
        currencies = List.copyOf(currencies);
        nonPayable = Set.copyOf(nonPayable);
        resettable = Set.copyOf(resettable);
        wholeNumbers = Set.copyOf(wholeNumbers);
    }

    /**
     * One currency to seed.
     *
     * @param identifier the identifier, at most 32 characters
     * @param symbol     the symbol, at most 8 characters
     * @param prefix     text before the amount
     * @param suffix     text after the amount
     * @param icon       the GUI icon material
     */
    public record Seed(@NotNull String identifier, @NotNull String symbol, @NotNull String prefix,
                       @NotNull String suffix, @NotNull String icon) {

        /**
         * A new, unsaved entity for this seed.
         *
         * @return the entity
         */
        public @NotNull Currency toEntity() {
            return new Currency(identifier, symbol, prefix, suffix, icon);
        }
    }

    /**
     * The configuration without a file.
     *
     * @return the defaults
     */
    public static @NotNull CurrencyConfig defaults() {
        return new CurrencyConfig(REQUIRED, Set.of(), Set.of(), Set.of(), DEFAULT_PAY_CONFIRM_THRESHOLD,
                DEFAULT_VAULT_CURRENCY);
    }

    /**
     * Reads the configuration. Invalid entries are skipped with a warning that names the path.
     *
     * @param yaml the loaded file
     * @param warn receives warnings
     * @return the configuration
     */
    public static @NotNull CurrencyConfig read(@NotNull YamlConfiguration yaml, @NotNull Consumer<String> warn) {
        var defaults = defaults();
        Set<String> nonPayable = yaml.isSet(KEY_NON_PAYABLE)
                ? Set.copyOf(yaml.getStringList(KEY_NON_PAYABLE)) : defaults.nonPayable();
        double threshold = yaml.getDouble(KEY_THRESHOLD, DEFAULT_PAY_CONFIRM_THRESHOLD);
        if (threshold < 0) {
            warn.accept(KEY_THRESHOLD + " is negative (" + threshold + "), using 0");
            threshold = 0.0;
        }
        return new CurrencyConfig(readSeeds(yaml.getConfigurationSection(KEY_CURRENCIES), warn), nonPayable,
                Set.copyOf(yaml.getStringList(KEY_RESETTABLE)), Set.copyOf(yaml.getStringList(KEY_WHOLE_NUMBERS)),
                threshold, yaml.getString(KEY_VAULT_CURRENCY, DEFAULT_VAULT_CURRENCY));
    }

    private static @NotNull List<Seed> readSeeds(@Nullable ConfigurationSection section,
                                                 @NotNull Consumer<String> warn) {
        if (section == null) {
            return List.of();
        }
        List<Seed> seeds = new ArrayList<>();
        for (String identifier : section.getKeys(false)) {
            var spec = section.getConfigurationSection(identifier);
            String path = KEY_CURRENCIES + "." + identifier;
            if (spec == null) {
                warn.accept(path + " is not a section, skipped");
                continue;
            }
            var seed = new Seed(identifier, spec.getString("symbol", ""), spec.getString("prefix", ""),
                    spec.getString("suffix", ""), spec.getString("icon", DEFAULT_ICON));
            String problem = problemOf(seed);
            if (problem == null) {
                seeds.add(seed);
            } else {
                warn.accept(path + ": " + problem + ", skipped");
            }
        }
        return seeds;
    }

    private static @Nullable String problemOf(@NotNull Seed seed) {
        if (seed.identifier().isBlank() || seed.identifier().length() > MAX_IDENTIFIER) {
            return "identifier must have 1 to " + MAX_IDENTIFIER + " characters";
        }
        if (ReservedCurrencyNames.isReserved(seed.identifier())) {
            return "identifier is reserved for a /balance subcommand";
        }
        if (seed.symbol().length() > MAX_SYMBOL) {
            return "symbol is longer than " + MAX_SYMBOL + " characters";
        }
        if (seed.prefix().length() > MAX_AFFIX || seed.suffix().length() > MAX_AFFIX) {
            return "prefix and suffix may have at most " + MAX_AFFIX + " characters";
        }
        if (seed.icon().length() > MAX_ICON) {
            return "icon is longer than " + MAX_ICON + " characters";
        }
        return null;
    }
}
