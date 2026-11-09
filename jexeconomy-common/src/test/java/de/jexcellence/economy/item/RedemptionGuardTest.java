package de.jexcellence.economy.item;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the physical currency redeem against rapid clicks: while one redemption of a player waits for its
 * deposit, every further click is refused; other players are not affected.
 */
class RedemptionGuardTest {

    private static final UUID STEVE = UUID.randomUUID();
    private static final UUID ALEX = UUID.randomUUID();

    private final RedemptionGuard guard = new RedemptionGuard();

    @Test
    void secondClickIsRefusedUntilTheFirstFinished() {
        assertTrue(guard.tryBegin(STEVE));
        assertFalse(guard.tryBegin(STEVE), "a double click must not start a second deposit");
        assertTrue(guard.isRunning(STEVE));

        guard.finish(STEVE);

        assertFalse(guard.isRunning(STEVE));
        assertTrue(guard.tryBegin(STEVE));
    }

    @Test
    void playersAreGuardedSeparately() {
        assertTrue(guard.tryBegin(STEVE));
        assertTrue(guard.tryBegin(ALEX));
    }

    @Test
    void concurrentClicksStartExactlyOneRedemption() throws InterruptedException {
        ExecutorService clicks = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        for (int i = 0; i < 64; i++) {
            clicks.execute(() -> {
                try {
                    start.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (guard.tryBegin(STEVE)) {
                    started.incrementAndGet();
                }
            });
        }
        start.countDown();
        clicks.shutdown();
        assertTrue(clicks.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(1, started.get());
    }
}
