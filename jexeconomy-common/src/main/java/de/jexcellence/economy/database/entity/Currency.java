package de.jexcellence.economy.database.entity;

import de.jexcellence.economy.api.CurrencySnapshot;
import de.jexcellence.jehibernate.entity.base.LongIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A server currency with display properties.
 *
 * <p>Cached instances are shared between threads and treated as read-only: an edit works on {@link #copy()} and the
 * saved copy replaces the cached one. Behaviour flags (payable, resettable, whole numbers) are not stored here; the
 * currency registry resolves them per identifier. Amounts are formatted by {@code CurrencyFormat}.
 *
 * @author JExcellence
 * @since 3.0.0
 */
@Entity
@Table(name = "economy_currency")
public class Currency extends LongIdEntity {

    private static final String DEFAULT_ICON = "GOLD_INGOT";

    @Column(name = "identifier", unique = true, nullable = false, length = 32)
    private String identifier;

    @Column(name = "symbol", nullable = false, length = 8)
    private String symbol;

    @Column(name = "prefix", length = 32)
    private String prefix;

    @Column(name = "suffix", length = 32)
    private String suffix;

    @Column(name = "icon", length = 64)
    private String icon;

    /** JPA constructor. */
    protected Currency() {
    }

    /**
     * Creates a currency with all properties.
     *
     * @param identifier unique programmatic name
     * @param symbol     short display symbol (e.g. "$")
     * @param prefix     text before amounts (nullable)
     * @param suffix     text after amounts (nullable)
     * @param icon       material name for GUI display (nullable, defaults to GOLD_INGOT)
     */
    public Currency(@NotNull String identifier,
                    @NotNull String symbol,
                    @Nullable String prefix,
                    @Nullable String suffix,
                    @Nullable String icon) {
        this.identifier = identifier;
        this.symbol = symbol;
        this.prefix = prefix != null ? prefix : "";
        this.suffix = suffix != null ? suffix : "";
        this.icon = icon != null ? icon : DEFAULT_ICON;
    }

    /**
     * Creates a currency with just an identifier and sensible defaults.
     *
     * @param identifier unique programmatic name
     */
    public Currency(@NotNull String identifier) {
        this(identifier, "", "", "", DEFAULT_ICON);
    }

    /**
     * Returns the unique identifier.
     *
     * @return the identifier
     */
    public @NotNull String getIdentifier() {
        return identifier;
    }

    /**
     * Sets the identifier.
     *
     * @param identifier the identifier
     */
    public void setIdentifier(@NotNull String identifier) {
        this.identifier = identifier;
    }

    /**
     * Returns the display symbol.
     *
     * @return the symbol
     */
    public @NotNull String getSymbol() {
        return symbol;
    }

    /**
     * Sets the display symbol.
     *
     * @param symbol the symbol
     */
    public void setSymbol(@NotNull String symbol) {
        this.symbol = symbol;
    }

    /**
     * Returns the amount prefix.
     *
     * @return the prefix
     */
    public @NotNull String getPrefix() {
        return prefix != null ? prefix : "";
    }

    /**
     * Sets the amount prefix.
     *
     * @param prefix the prefix
     */
    public void setPrefix(@NotNull String prefix) {
        this.prefix = prefix;
    }

    /**
     * Returns the amount suffix.
     *
     * @return the suffix
     */
    public @NotNull String getSuffix() {
        return suffix != null ? suffix : "";
    }

    /**
     * Sets the amount suffix.
     *
     * @param suffix the suffix
     */
    public void setSuffix(@NotNull String suffix) {
        this.suffix = suffix;
    }

    /**
     * Returns the Material name used as GUI icon.
     *
     * @return the icon material name
     */
    public @NotNull String getIcon() {
        return icon != null ? icon : DEFAULT_ICON;
    }

    /**
     * Sets the icon material name.
     *
     * @param icon the material name
     */
    public void setIcon(@NotNull String icon) {
        this.icon = icon;
    }

    /**
     * Creates an API-safe snapshot of this currency.
     *
     * @return a lightweight, immutable snapshot
     */
    public @NotNull CurrencySnapshot toSnapshot() {
        return new CurrencySnapshot(
                getId() != null ? getId() : 0L, identifier, symbol, getPrefix(), getSuffix(), getIcon());
    }

    /**
     * A detached copy with the same id and row version, for an update that must not touch the cached instance
     * before the database accepted it.
     *
     * @return the copy
     */
    public @NotNull Currency copy() {
        var copy = new Currency(identifier, symbol, prefix, suffix, icon);
        copy.setCreatedAt(getCreatedAt());
        copy.setUpdatedAt(getUpdatedAt());
        copy.setVersion(getVersion());
        if (getId() != null) {
            copy.setId(getId());
        }
        return copy;
    }

    @Override
    public String toString() {
        return "Currency[" + identifier + "]";
    }
}
