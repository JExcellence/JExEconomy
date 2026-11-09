package de.jexcellence.economy.migration;

import de.jexcellence.economy.migration.MigrationPlanner.Known;
import de.jexcellence.economy.migration.MigrationPlanner.Plan;
import de.jexcellence.economy.migration.MigrationSource.SourceBalance;
import de.jexcellence.economy.migration.MigrationSource.SourceRead;
import de.jexcellence.economy.money.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every balance in a source is either planned or counted under the reason it was left out.
 */
class MigrationPlannerTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Known NOTHING_KNOWN = new Known(Map.of(), Set.of(), Set.of());

    private static SourceBalance balance(UUID player, String amount) {
        return new SourceBalance(player, null, new BigDecimal(amount));
    }

    private static Plan plan(MigrationMode mode, Known known, SourceBalance... balances) {
        return MigrationPlanner.plan(new SourceRead(List.of(balances), 0), mode, known);
    }

    private static void assertSame(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual), () -> expected + " but was " + actual);
    }

    @Test
    void aNegativeBalanceIsInvalid() {
        Plan plan = plan(MigrationMode.ADD, NOTHING_KNOWN, balance(ALEX, "-1"));

        assertEquals(0, plan.report().players());
        assertEquals(1, plan.report().counts().invalid());
    }

    @Test
    void aRepeatedPlayerIsInvalidAndImportedOnce() {
        Plan plan = plan(MigrationMode.ADD, NOTHING_KNOWN, balance(ALEX, "2"), balance(ALEX, "3"));

        assertEquals(1, plan.report().players());
        assertEquals(1, plan.report().counts().invalid());
        assertSame(new BigDecimal("2"), plan.entries().getFirst().amount());
    }

    @Test
    void aZeroBalanceIsEmpty() {
        assertEquals(1, plan(MigrationMode.ADD, NOTHING_KNOWN, balance(ALEX, "0")).report().counts().empty());
    }

    @Test
    void anAlreadyImportedPlayerIsCountedBeforeAnythingElse() {
        Plan plan = plan(MigrationMode.ADD, new Known(Map.of(), Set.of(), Set.of(ALEX)), balance(ALEX, "-1"));

        assertEquals(1, plan.report().counts().alreadyImported());
        assertEquals(0, plan.report().counts().invalid());
    }

    @Test
    void addAddsAndOverwriteReplaces() {
        Known known = new Known(Map.of(ALEX, new BigDecimal("5")), Set.of(), Set.of());

        assertSame(new BigDecimal("15"), plan(MigrationMode.ADD, known, balance(ALEX, "10")).entries().getFirst().expected());
        assertSame(new BigDecimal("10"),
                plan(MigrationMode.OVERWRITE, known, balance(ALEX, "10")).entries().getFirst().expected());
    }

    @Test
    void historyAloneCountsAsAnExistingAccount() {
        Known known = new Known(Map.of(), Set.of(STEVE), Set.of());

        assertEquals(1, plan(MigrationMode.SKIP_EXISTING, known, balance(STEVE, "10")).report().counts().existing());
    }

    @Test
    void roundingIsAddedUpAndTheTotalsUseTheRoundedAmounts() {
        BigDecimal raw = new BigDecimal("1").movePointLeft(Money.SCALE + 1);
        BigDecimal source = BigDecimal.ONE.add(raw);

        Plan plan = plan(MigrationMode.ADD, NOTHING_KNOWN, balance(ALEX, source.toPlainString()));

        assertSame(Money.of(source), plan.report().importTotal());
        assertSame(source.subtract(Money.of(source)).abs(), plan.report().roundingLoss());
    }
}
