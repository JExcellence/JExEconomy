package de.jexcellence.economy.command.player.pay;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the {@code /pay} confirmation flow: payments from the threshold on need one confirmation within 30
 * seconds (button, form or the same command again), a cancelled or replaced payment can never be confirmed, and a
 * confirmation works only once.
 */
class PayConfirmationsTest {

    private static final UUID SENDER = UUID.randomUUID();
    private static final UUID TARGET = UUID.randomUUID();
    private static final String COINS = "coins";

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final PayConfirmations confirmations = new PayConfirmations(now::get);

    @Test
    void thresholdAppliesFromTheConfiguredAmountOn() {
        assertFalse(PayConfirmations.needsConfirmation(99_999.0, 100_000.0));
        assertTrue(PayConfirmations.needsConfirmation(100_000.0, 100_000.0));
        assertFalse(PayConfirmations.needsConfirmation(5_000_000.0, 0.0), "0 turns the confirmation off");
    }

    @Test
    void buttonConfirmsOnceWithinTheWindow() {
        PayConfirmations.Pending pending = confirmations.arm(SENDER, TARGET, COINS, 150_000.0);
        now.addAndGet(PayConfirmations.WINDOW_MS);

        assertTrue(confirmations.confirm(SENDER, pending));
        assertFalse(confirmations.confirm(SENDER, pending), "a double click pays only once");
    }

    @Test
    void expiredPaymentCannotBeConfirmed() {
        PayConfirmations.Pending pending = confirmations.arm(SENDER, TARGET, COINS, 150_000.0);
        now.addAndGet(PayConfirmations.WINDOW_MS + 1);

        assertFalse(confirmations.confirm(SENDER, pending));
    }

    @Test
    void repeatingTheSameCommandConfirmsOnlyTheSamePayment() {
        confirmations.arm(SENDER, TARGET, COINS, 150_000.0);

        assertFalse(confirmations.confirmRepeat(SENDER, TARGET, COINS, 160_000.0), "other amount");
        assertFalse(confirmations.confirmRepeat(SENDER, UUID.randomUUID(), COINS, 150_000.0), "other target");
        assertTrue(confirmations.confirmRepeat(SENDER, TARGET, COINS, 150_000.0));
        assertFalse(confirmations.confirmRepeat(SENDER, TARGET, COINS, 150_000.0), "used up");
    }

    @Test
    void cancelledOrReplacedPaymentCannotBeConfirmed() {
        PayConfirmations.Pending cancelled = confirmations.arm(SENDER, TARGET, COINS, 150_000.0);
        assertTrue(confirmations.cancel(SENDER, cancelled));
        assertFalse(confirmations.confirm(SENDER, cancelled));

        PayConfirmations.Pending stale = confirmations.arm(SENDER, TARGET, COINS, 200_000.0);
        now.incrementAndGet();
        PayConfirmations.Pending current = confirmations.arm(SENDER, TARGET, COINS, 300_000.0);
        assertFalse(confirmations.confirm(SENDER, stale), "an old button never pays the new amount");
        assertTrue(confirmations.confirm(SENDER, current));
    }
}
