package de.jexcellence.economy.format;

import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.text.EconomyText;
import de.jexcellence.jexplatform.gui.style.MythCurrencyFormat;
import de.jexcellence.jexplatform.gui.style.MythCurrencyFormat.CurrencyType;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;

/**
 * Single source of truth for rendering a currency amount as branded MiniMessage.
 *
 * <p>The viewer-aware overloads use the resource pack icons for Coins and
 * Crystals. Generic overloads deliberately retain Unicode fallbacks, making
 * this formatter safe for console, APIs and clients that declined the pack.</p>
 *
 * @author JExcellence
 * @since 4.0.0
 */
public final class CurrencyFormat {

    /** Visual identity for a non-pack or dynamically-created currency. */
    public record Identity(@NotNull String gradient, @NotNull String glyph, @NotNull String name) { }

    private static final Identity COINS = new Identity("#FBBF24:#F59E0B", "\u26C1", "Coins");
    private static final Identity CRYSTALS = new Identity("#2DD4BF:#A855F7", "\u25C6", "Crystals");
    private static final String FALLBACK_GLYPH = "\u25C8";
    private static final String FALLBACK_GRADIENT = "#CBD5E1:#94A3B8";
    private static final String NAME_KEY = EconomyText.ROOT + "currency-name.";

    private static final Map<String, Identity> REGISTRY = Map.of(
            "coins", COINS,
            "crystals", CRYSTALS,
            "credits", CRYSTALS);

    private CurrencyFormat() {
    }

    /** The visual identity for a currency, or a neutral fallback built from the entity. */
    public static @NotNull Identity identityOf(@NotNull Currency currency) {
        Identity identity = REGISTRY.get(currency.getIdentifier().toLowerCase(Locale.ROOT));
        if (identity != null) {
            return identity;
        }
        String glyph = currency.getSymbol() == null || currency.getSymbol().isBlank()
                ? FALLBACK_GLYPH : currency.getSymbol();
        String name = currency.getSuffix() != null && !currency.getSuffix().isBlank()
                ? currency.getSuffix().trim() : currency.getIdentifier();
        return new Identity(FALLBACK_GRADIENT, glyph, name);
    }

    /** Grouped amount with trailing zero-decimals trimmed (e.g. {@code 1,234} / {@code 1,234.5}). */
    public static @NotNull String amount(double value) {
        return new DecimalFormat("#,##0.##", new DecimalFormatSymbols(Locale.US)).format(value);
    }

    /**
     * Plain text form for Vault and PlaceholderAPI: prefix, amount, symbol and suffix without MiniMessage. The
     * amount uses a dot and no grouping, independent of the JVM locale.
     *
     * @param currency     the currency
     * @param value        the amount
     * @param wholeNumbers {@code true} to round to whole units (the currency's policy)
     * @return the plain text
     */
    public static @NotNull String plain(@NotNull Currency currency, double value, boolean wholeNumbers) {
        return plain(Locale.ROOT, currency, value, wholeNumbers);
    }

    /**
     * Plain text form with the decimal separator of a locale.
     *
     * @param locale       the locale of the number
     * @param currency     the currency
     * @param value        the amount
     * @param wholeNumbers {@code true} to round to whole units
     * @return the plain text
     */
    public static @NotNull String plain(@NotNull Locale locale, @NotNull Currency currency, double value,
                                        boolean wholeNumbers) {
        String number = String.format(locale, wholeNumbers ? "%.0f" : "%.2f", value);
        return currency.getPrefix() + number + currency.getSymbol() + currency.getSuffix();
    }

    /** Branded inline form using Unicode fallbacks. */
    public static @NotNull String inline(@NotNull Currency currency, double value) {
        return inline(null, currency, value);
    }

    /** Branded inline form for one viewer, using custom glyphs when their pack is loaded. */
    public static @NotNull String inline(@Nullable Player viewer, @NotNull Currency currency, double value) {
        CurrencyType type = MythCurrencyFormat.fromIdentifier(currency.getIdentifier());
        if (type != null) {
            return MythCurrencyFormat.inline(type, amount(value), viewer);
        }
        Identity identity = identityOf(currency);
        return render(identity, amount(value) + " " + identity.name());
    }

    /** Compact branded form without the name, using Unicode fallbacks. */
    public static @NotNull String compact(@NotNull Currency currency, double value) {
        return compact(null, currency, value);
    }

    /** Compact branded form for one viewer. */
    public static @NotNull String compact(@Nullable Player viewer, @NotNull Currency currency, double value) {
        CurrencyType type = MythCurrencyFormat.fromIdentifier(currency.getIdentifier());
        if (type != null) {
            return MythCurrencyFormat.compact(type, amount(value), viewer);
        }
        return render(identityOf(currency), amount(value));
    }

    /** Just the branded currency name, using Unicode fallbacks. */
    public static @NotNull String label(@NotNull Currency currency) {
        return label(null, currency);
    }

    /** Branded currency label for one viewer. */
    public static @NotNull String label(@Nullable Player viewer, @NotNull Currency currency) {
        CurrencyType type = MythCurrencyFormat.fromIdentifier(currency.getIdentifier());
        if (type != null) {
            return MythCurrencyFormat.label(type, viewer);
        }
        Identity identity = identityOf(currency);
        return render(identity, identity.name());
    }

    /**
     * The plain, translated name of a currency for labels and rows: {@code economy-v2.currency-name.<id>} when the
     * locale defines it, otherwise the name from the currency's visual identity.
     *
     * @param viewer   the viewer, {@code null} for the default locale
     * @param currency the currency
     * @return the plain name
     */
    public static @NotNull String displayName(@Nullable Player viewer, @NotNull Currency currency) {
        var builder = EconomyText.msg(NAME_KEY + currency.getIdentifier().toLowerCase(Locale.ROOT));
        return builder.exists(viewer) ? builder.plain(viewer) : identityOf(currency).name();
    }

    /** Returns the canonical symbol for a currency. */
    public static @NotNull String symbol(@Nullable Player viewer, @NotNull Currency currency) {
        CurrencyType type = MythCurrencyFormat.fromIdentifier(currency.getIdentifier());
        return type == null ? identityOf(currency).glyph() : MythCurrencyFormat.icon(type, viewer);
    }

    private static @NotNull String render(@NotNull Identity identity, @NotNull String content) {
        return "<gradient:" + identity.gradient() + ">" + identity.glyph() + " " + content + "</gradient>";
    }
}
