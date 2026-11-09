package de.jexcellence.economy.api.event;

import de.jexcellence.economy.api.CurrencySnapshot;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

/**
 * Fired <em>after</em> every account of a resettable currency was set to zero.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public class CurrencyResetEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final @NotNull CurrencySnapshot currency;
    private final int accountsReset;
    private final double totalRemoved;
    private final @Nullable String reason;
    private final @NotNull Instant resetTime;

    /**
     * Creates a new currency reset event.
     *
     * @param currency      the reset currency
     * @param accountsReset number of accounts that held a balance
     * @param totalRemoved  sum of all removed balances
     * @param reason        the reason passed to the reset, or null
     */
    public CurrencyResetEvent(@NotNull CurrencySnapshot currency,
                              int accountsReset,
                              double totalRemoved,
                              @Nullable String reason) {
        this.currency = currency;
        this.accountsReset = accountsReset;
        this.totalRemoved = totalRemoved;
        this.reason = reason;
        this.resetTime = Instant.now();
    }

    /**
     * Returns the reset currency.
     *
     * @return the reset currency
     */
    public @NotNull CurrencySnapshot getCurrency() { return currency; }

    /**
     * Returns how many accounts held a balance before the reset.
     *
     * @return number of reset accounts
     */
    public int getAccountsReset() { return accountsReset; }

    /**
     * Returns the sum of all removed balances.
     *
     * @return removed total
     */
    public double getTotalRemoved() { return totalRemoved; }

    /**
     * Returns the reason passed to the reset, or null.
     *
     * @return the reset reason
     */
    public @Nullable String getReason() { return reason; }

    /**
     * Returns the instant the reset finished.
     *
     * @return the reset time
     */
    public @NotNull Instant getResetTime() { return resetTime; }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }

    /**
     * Returns the handler list for this event type.
     *
     * @return the handler list
     */
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}
