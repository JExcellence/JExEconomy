package de.jexcellence.economy.placeholder;

import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.format.CurrencyFormat;
import de.jexcellence.economy.service.EconomyService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * PlaceholderAPI expansion for JExEconomy currency and balance placeholders. Balances come from the
 * {@link PlaceholderBalanceCache}, so a request never waits for the database; a balance that is not loaded yet
 * renders as an empty string until the next refresh.
 *
 * <p>Supported patterns:
 * <ul>
 *   <li>{@code %jexeconomy_currency_<id>_name|symbol|prefix|suffix%}</li>
 *   <li>{@code %jexeconomy_currency_<id>_icon%} - MiniMessage font icon, not legacy plain text</li>
 *   <li>{@code %jexeconomy_balance_<id>%} - raw two-decimal balance</li>
 *   <li>{@code %jexeconomy_balance_<id>_rounded%} - integer balance</li>
 *   <li>{@code %jexeconomy_balance_<id>_formatted%} - prefix + amount + symbol + suffix</li>
 * </ul>
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class EconomyExpansion extends PlaceholderExpansion {

    private static final String CURRENCY_PREFIX = "currency_";
    private static final String BALANCE_PREFIX = "balance_";
    private static final String NOT_AVAILABLE = "N/A";
    private static final String NOT_LOADED = "";

    private final EconomyService service;
    private final PlaceholderBalanceCache balances;
    private final String version;

    private record CurrencyTarget(@NotNull String currencyId, @NotNull String suffix) {
    }

    /**
     * Creates the expansion.
     *
     * @param service  the economy service
     * @param balances the balance cache
     * @param version  the plugin version
     */
    public EconomyExpansion(@NotNull EconomyService service, @NotNull PlaceholderBalanceCache balances,
                            @NotNull String version) {
        this.service = service;
        this.balances = balances;
        this.version = version;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "jexeconomy";
    }

    @Override
    public @NotNull String getAuthor() {
        return "JExcellence";
    }

    @Override
    public @NotNull String getVersion() {
        return version;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer player, @NotNull String params) {
        if (params.startsWith(CURRENCY_PREFIX)) {
            var target = splitCurrency(params.substring(CURRENCY_PREFIX.length()));
            return target == null || target.suffix().isEmpty()
                    ? null : currencyInfo(target.currencyId(), target.suffix());
        }
        if (params.startsWith(BALANCE_PREFIX) && player != null) {
            var target = splitCurrency(params.substring(BALANCE_PREFIX.length()));
            if (target == null) {
                return NOT_AVAILABLE;
            }
            return balance(player, target.currencyId(), target.suffix());
        }
        return null;
    }

    /**
     * Splits {@code <id>[_<suffix>]} by matching the longest known currency id, so ids that contain underscores
     * (for example {@code season_points}) resolve correctly.
     */
    private @Nullable CurrencyTarget splitCurrency(@NotNull String rest) {
        CurrencyTarget best = null;
        for (var id : service.getAllCurrencies().keySet()) {
            var matches = rest.equals(id) || rest.startsWith(id + "_");
            if (matches && (best == null || id.length() > best.currencyId().length())) {
                var suffix = rest.length() > id.length() ? rest.substring(id.length() + 1) : "";
                best = new CurrencyTarget(id, suffix);
            }
        }
        return best;
    }

    private @Nullable String currencyInfo(@NotNull String id, @NotNull String field) {
        var found = service.findCurrency(id);
        if (found.isEmpty()) {
            return null;
        }
        var currency = found.get();
        return switch (field) {
            case "name" -> currency.getIdentifier();
            case "symbol" -> currency.getSymbol();
            case "icon" -> CurrencyFormat.symbol(null, currency);
            case "prefix" -> currency.getPrefix();
            case "suffix" -> currency.getSuffix();
            default -> null;
        };
    }

    private @NotNull String balance(@NotNull OfflinePlayer player, @NotNull String currencyId,
                                    @NotNull String format) {
        var found = service.findCurrency(currencyId);
        if (found.isEmpty()) {
            return NOT_AVAILABLE;
        }
        Currency currency = found.get();
        var cached = balances.balance(player, currency);
        if (cached.isEmpty()) {
            return NOT_LOADED;
        }
        double value = cached.getAsDouble();
        return switch (format) {
            case "rounded" -> NumberFormat.getIntegerInstance(Locale.US).format(value);
            case "formatted" -> CurrencyFormat.plain(currency, value, service.isWholeNumbers(currency));
            default -> decimal(value);
        };
    }

    private static @NotNull String decimal(double value) {
        var format = NumberFormat.getInstance(Locale.US);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(value);
    }
}
