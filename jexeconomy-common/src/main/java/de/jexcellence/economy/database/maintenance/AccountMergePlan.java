package de.jexcellence.economy.database.maintenance;

import de.jexcellence.economy.money.Money;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * How duplicate accounts of one player and currency become one: the oldest row (lowest id) stays and receives the
 * sum of all balances, every other row is deleted. No money is lost or created.
 *
 * @param keeperId   the account that stays
 * @param total      the merged balance of the keeper
 * @param removedIds the accounts to delete
 * @author JExcellence
 * @since 3.1.0
 */
public record AccountMergePlan(long keeperId, @NotNull BigDecimal total, @NotNull List<Long> removedIds) {

    /**
     * One duplicate account row.
     *
     * @param id      the account id
     * @param balance the balance
     */
    public record Row(long id, @NotNull BigDecimal balance) {
    }

    /**
     * Plans the merge of duplicate rows.
     *
     * @param rows the accounts of one player and currency, at least one
     * @return the plan
     */
    public static @NotNull AccountMergePlan of(@NotNull List<Row> rows) {
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Nothing to merge");
        }
        List<Row> ordered = rows.stream().sorted(Comparator.comparingLong(Row::id)).toList();
        BigDecimal total = ordered.stream().map(Row::balance).map(Money::of).reduce(Money.ZERO, BigDecimal::add);
        List<Long> removed = ordered.stream().skip(1).map(Row::id).toList();
        return new AccountMergePlan(ordered.getFirst().id(), Money.of(total), removed);
    }
}
