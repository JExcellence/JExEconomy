package de.jexcellence.economy.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the money scale: two decimals, rounded half up, without binary noise from doubles.
 */
class MoneyTest {

    @Test
    void doublesConvertWithoutBinaryNoise() {
        assertEquals(new BigDecimal("0.10"), Money.of(0.1));
        assertEquals(new BigDecimal("0.30"), Money.of(0.1).add(Money.of(0.2)));
        assertEquals(new BigDecimal("1234567.89"), Money.of(1234567.89));
    }

    @Test
    void roundingIsHalfUp() {
        assertEquals(new BigDecimal("0.01"), Money.of(0.005));
        assertEquals(new BigDecimal("1.01"), Money.of(1.005));
        assertEquals(new BigDecimal("2.67"), Money.of(new BigDecimal("2.665")));
        assertEquals(new BigDecimal("2.66"), Money.of(new BigDecimal("2.6649")));
        assertEquals(new BigDecimal("-0.01"), Money.of(-0.005));
    }

    @Test
    void nullStoredValueIsZero() {
        assertEquals(Money.ZERO, Money.of((BigDecimal) null));
        assertEquals(0.0, Money.toDouble(null));
    }

    @Test
    void nonFiniteDoublesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Money.of(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Money.of(Double.POSITIVE_INFINITY));
    }

    @Test
    void amountsThatRoundToZeroAreNotBookable() {
        assertFalse(Money.isBookable(0.004));
        assertFalse(Money.isBookable(0.0));
        assertFalse(Money.isBookable(-1.0));
        assertFalse(Money.isBookable(Double.NaN));
        assertFalse(Money.isBookable(Double.POSITIVE_INFINITY));
        assertTrue(Money.isBookable(0.005));
        assertTrue(Money.isBookable(12.5));
    }
}
