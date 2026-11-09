package de.jexcellence.economy.migration;

import de.jexcellence.economy.migration.MigrationSource.SourceBalance;
import de.jexcellence.economy.migration.MigrationSource.SourceRead;
import de.jexcellence.economy.money.Money;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Decides, for each balance in a source, whether and how it is imported. Holds no state and changes nothing.
 *
 * @author JExcellence
 * @since 3.2.0
 */
final class MigrationPlanner {

    private MigrationPlanner() {
    }

    /**
     * What the economy knows when the plan is made.
     *
     * @param balances        the current balances; a missing player has none
     * @param history         players with a transaction history in the currency
     * @param alreadyImported players an earlier run imported from this source
     */
    record Known(@NotNull Map<UUID, BigDecimal> balances, @NotNull Set<UUID> history,
                 @NotNull Set<UUID> alreadyImported) {
    }

    /**
     * One player to import.
     *
     * @param player   the player
     * @param name     the player's name, {@code null} when unknown
     * @param amount   the source balance at the economy's precision
     * @param before   the balance before the import
     * @param expected the balance after the import
     */
    record Planned(@NotNull UUID player, @Nullable String name, @NotNull BigDecimal amount,
                   @NotNull BigDecimal before, @NotNull BigDecimal expected) {
    }

    /**
     * A finished plan.
     *
     * @param entries the players to import
     * @param report  the report
     */
    record Plan(@NotNull List<Planned> entries, @NotNull MigrationReport report) {
    }

    private static final class Tally {
        private BigDecimal sourceTotal = Money.ZERO;
        private BigDecimal importTotal = Money.ZERO;
        private BigDecimal roundingLoss = Money.ZERO;
        private int invalid;
        private int empty;
        private int existing;
        private int alreadyImported;
    }

    static @NotNull Plan plan(@NotNull SourceRead read, @NotNull MigrationMode mode, @NotNull Known known) {
        var tally = new Tally();
        List<Planned> entries = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (SourceBalance balance : read.balances()) {
            if (known.alreadyImported().contains(balance.player())) {
                tally.alreadyImported++;
            } else if (balance.balance().signum() < 0 || !seen.add(balance.player())) {
                tally.invalid++;
            } else {
                planValid(balance, mode, known, tally, entries);
            }
        }
        var leftOut = new MigrationReport.LeftOut(tally.invalid, read.unreadable(), tally.empty, tally.existing,
                tally.alreadyImported);
        return new Plan(entries, new MigrationReport(entries.size(), tally.sourceTotal, tally.importTotal,
                tally.roundingLoss, leftOut));
    }

    private static void planValid(@NotNull SourceBalance balance, @NotNull MigrationMode mode, @NotNull Known known,
                                  @NotNull Tally tally, @NotNull List<Planned> entries) {
        BigDecimal amount = Money.of(balance.balance());
        tally.sourceTotal = tally.sourceTotal.add(amount);
        tally.roundingLoss = tally.roundingLoss.add(balance.balance().subtract(amount).abs());
        BigDecimal before = known.balances().getOrDefault(balance.player(), Money.ZERO);
        if (amount.signum() == 0) {
            tally.empty++;
        } else if (mode == MigrationMode.SKIP_EXISTING
                && (before.signum() != 0 || known.history().contains(balance.player()))) {
            tally.existing++;
        } else {
            BigDecimal expected = mode == MigrationMode.ADD ? before.add(amount) : amount;
            tally.importTotal = tally.importTotal.add(amount);
            entries.add(new Planned(balance.player(), balance.name(), amount, before, expected));
        }
    }
}
