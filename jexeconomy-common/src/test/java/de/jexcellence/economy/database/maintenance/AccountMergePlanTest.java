package de.jexcellence.economy.database.maintenance;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the duplicate-account merge: the oldest row stays, it receives the sum of every balance, and no money is
 * lost or created.
 */
class AccountMergePlanTest {

    @Test
    void oldestRowKeepsTheSummedBalance() {
        var plan = AccountMergePlan.of(List.of(
                new AccountMergePlan.Row(42L, new BigDecimal("10.50")),
                new AccountMergePlan.Row(7L, new BigDecimal("100.25")),
                new AccountMergePlan.Row(19L, new BigDecimal("0.25"))));

        assertEquals(7L, plan.keeperId());
        assertEquals(new BigDecimal("111.00"), plan.total());
        assertEquals(List.of(19L, 42L), plan.removedIds());
    }

    @Test
    void singleRowIsKeptUnchanged() {
        var plan = AccountMergePlan.of(List.of(new AccountMergePlan.Row(3L, new BigDecimal("5"))));

        assertEquals(3L, plan.keeperId());
        assertEquals(new BigDecimal("5.00"), plan.total());
        assertTrue(plan.removedIds().isEmpty());
    }

    @Test
    void emptyGroupIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> AccountMergePlan.of(List.of()));
    }
}
