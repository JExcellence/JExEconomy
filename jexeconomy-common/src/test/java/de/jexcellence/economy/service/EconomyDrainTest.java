package de.jexcellence.economy.service;

import de.jexcellence.economy.api.ChangeType;
import de.jexcellence.economy.service.ledger.AccountKey;
import de.jexcellence.economy.service.ledger.BalanceLedger;
import de.jexcellence.economy.service.ledger.BalanceOperation;
import de.jexcellence.economy.service.ledger.LedgerEntry;
import de.jexcellence.economy.service.ledger.LedgerHooks;
import de.jexcellence.economy.service.ledger.LedgerRequest;
import de.jexcellence.economy.service.ledger.LedgerStore;
import de.jexcellence.jexplatform.logging.JExLogger;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shutdown drain waits until every queued balance change is booked, and gives up after its timeout with the
 * number of accounts that still had one, instead of blocking the stop forever.
 */
class EconomyDrainTest {

    private static final AccountKey COINS = AccountKey.of(UUID.randomUUID(), "Steve", "coins");

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final CountDownLatch release = new CountDownLatch(1);
    private final BalanceLedger ledger = new BalanceLedger(new SlowStore(release), LedgerHooks.NONE, executor);
    /** The drain only stops the main-thread event dispatch; that never touches the scheduler. */
    private final EconomyDrain drain = new EconomyDrain(ledger, new EconomyEvents(null),
            JExLogger.of("EconomyDrainTest"));

    @AfterEach
    void stop() {
        release.countDown();
        executor.shutdownNow();
    }

    @Test
    void nothingQueuedDrainsAtOnce() {
        assertEquals(0, drain.drain(Duration.ofSeconds(1)));
    }

    @Test
    void aQueuedChangeIsBookedBeforeTheDrainEnds() {
        CompletableFuture<?> pending = ledger.submit(deposit());
        assertEquals(1, ledger.pendingAccounts());
        CompletableFuture.runAsync(() -> sleepThenRelease(200L));

        assertEquals(0, drain.drain(Duration.ofSeconds(5)));
        assertTrue(pending.join() != null);
    }

    @Test
    void aStuckChangeIsReportedAfterTheTimeout() {
        ledger.submit(deposit());

        long started = System.nanoTime();
        assertEquals(1, drain.drain(Duration.ofMillis(200)));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000L, "the drain gave up in time");
    }

    private void sleepThenRelease(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        release.countDown();
    }

    private static @NotNull LedgerRequest deposit() {
        return new LedgerRequest(COINS, BalanceOperation.delta(new BigDecimal("5.00")), ChangeType.DEPOSIT, "test",
                null);
    }

    /** A store whose writes wait until the test releases them. */
    private record SlowStore(@NotNull CountDownLatch release) implements LedgerStore {

        @Override
        public @NotNull LedgerEntry load(@NotNull AccountKey key) {
            return new LedgerEntry(1L, 0, new BigDecimal("0.00"));
        }

        @Override
        public void write(@NotNull LedgerEntry entry, @NotNull BigDecimal balance) {
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void writeBoth(@NotNull LedgerEntry first, @NotNull BigDecimal firstBalance,
                              @NotNull LedgerEntry second, @NotNull BigDecimal secondBalance) {
            throw new UnsupportedOperationException("not used here");
        }
    }
}
