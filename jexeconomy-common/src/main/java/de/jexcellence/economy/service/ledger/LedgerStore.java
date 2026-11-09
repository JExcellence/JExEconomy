package de.jexcellence.economy.service.ledger;

import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;

/**
 * Where the ledger reads and writes balances. Both methods block and run on the ledger executor.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public interface LedgerStore {

    /**
     * Reads the account, creating the player and the account when they do not exist yet.
     *
     * @param key the account
     * @return the current state
     */
    @NotNull LedgerEntry load(@NotNull AccountKey key);

    /**
     * Writes a new balance. Throws an optimistic-lock failure ({@code jakarta.persistence.OptimisticLockException}
     * or a Hibernate stale-state failure) when the row no longer has the version of {@code entry}; the ledger then
     * reads again and retries.
     *
     * @param entry   the state the change was computed from
     * @param balance the new balance
     */
    void write(@NotNull LedgerEntry entry, @NotNull BigDecimal balance);

    /**
     * Writes two new balances in one transaction: both are stored or neither. Fails like {@link #write} when either
     * row no longer has the version of its entry.
     *
     * @param first         the state of the first account
     * @param firstBalance  its new balance
     * @param second        the state of the second account
     * @param secondBalance its new balance
     */
    void writeBoth(@NotNull LedgerEntry first, @NotNull BigDecimal firstBalance,
                   @NotNull LedgerEntry second, @NotNull BigDecimal secondBalance);
}
