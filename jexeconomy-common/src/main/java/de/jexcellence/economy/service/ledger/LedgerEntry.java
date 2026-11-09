package de.jexcellence.economy.service.ledger;

import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;

/**
 * The state of an account as one ledger attempt read it.
 *
 * @param accountId the account row id
 * @param version   the row version the write must still find
 * @param balance   the balance with two decimals
 * @author JExcellence
 * @since 3.1.0
 */
public record LedgerEntry(long accountId, int version, @NotNull BigDecimal balance) {
}
