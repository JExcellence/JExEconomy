package de.jexcellence.economy.service.ledger;

import de.jexcellence.economy.api.ChangeType;
import de.jexcellence.economy.api.TransactionResult;
import de.jexcellence.economy.money.Money;
import de.jexcellence.economy.service.FailureReason;
import jakarta.persistence.OptimisticLockException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the per-account ledger: changes of one account never overlap, an absolute set is applied in queue order
 * (so it cannot swallow a deposit), version conflicts are retried, and refused changes write nothing.
 */
class BalanceLedgerTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final AccountKey COINS = new AccountKey(PLAYER, "Steve", "coins");

    private final ExecutorService executor = Executors.newFixedThreadPool(8);
    private final FakeStore store = new FakeStore();
    private final RecordingHooks hooks = new RecordingHooks();
    private final BalanceLedger ledger = new BalanceLedger(store, hooks, executor);

    @AfterEach
    void stopExecutor() throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void concurrentDepositsAreAllBookedWithoutOverlap() throws InterruptedException {
        int threads = 8;
        int perThread = 50;
        List<CompletableFuture<TransactionResult>> results = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> submitters = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread submitter = new Thread(() -> {
                awaitQuietly(start);
                for (int i = 0; i < perThread; i++) {
                    var future = ledger.submit(delta("1.00", ChangeType.DEPOSIT));
                    synchronized (results) {
                        results.add(future);
                    }
                }
            });
            submitters.add(submitter);
            submitter.start();
        }
        start.countDown();
        for (Thread submitter : submitters) {
            submitter.join();
        }
        CompletableFuture.allOf(results.toArray(CompletableFuture[]::new)).join();

        assertTrue(results.stream().allMatch(future -> future.join().isSuccess()));
        assertEquals(new BigDecimal("400.00"), store.balance(COINS));
        assertFalse(store.overlapped.get(), "two changes of one account ran at the same time");
        assertEquals(threads * perThread, hooks.committed.size());
    }

    @Test
    void absoluteSetIsAppliedInQueueOrder() {
        List<CompletableFuture<TransactionResult>> results = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            results.add(ledger.submit(delta("1.00", ChangeType.DEPOSIT)));
        }
        results.add(ledger.submit(new LedgerRequest(COINS, BalanceOperation.absolute(Money.ZERO), ChangeType.DEPOSIT,
                "Set balance", null)));
        for (int i = 0; i < 25; i++) {
            results.add(ledger.submit(delta("1.00", ChangeType.DEPOSIT)));
        }
        CompletableFuture.allOf(results.toArray(CompletableFuture[]::new)).join();

        assertEquals(new BigDecimal("25.00"), store.balance(COINS),
                "the set must see the 25 earlier deposits and none of the later ones");
        TransactionResult set = results.get(25).join();
        assertTrue(set.isSuccess());
        assertEquals(25.0, set.amount());
        assertEquals(0.0, set.balance());
    }

    @Test
    void compareAndSetIsRefusedWhenTheBalanceMoved() {
        store.seed(COINS, "10.00");

        TransactionResult result = ledger.submit(new LedgerRequest(COINS,
                BalanceOperation.compareAndSet(new BigDecimal("9"), new BigDecimal("4")), ChangeType.DEPOSIT, null,
                null)).join();

        assertEquals("Balance changed", result.error());
        assertEquals(new BigDecimal("10.00"), store.balance(COINS));
    }

    @Test
    void compareAndSetRunsOnTheExpectedBalance() {
        store.seed(COINS, "10.00");

        assertTrue(ledger.submit(new LedgerRequest(COINS,
                BalanceOperation.compareAndSet(new BigDecimal("10"), new BigDecimal("4")), ChangeType.DEPOSIT, null,
                null)).join().isSuccess());

        assertEquals(new BigDecimal("4.00"), store.balance(COINS));
    }

    @Test
    void absoluteSetRecordsTheDirection() {
        store.seed(COINS, "10.00");
        ledger.submit(new LedgerRequest(COINS, BalanceOperation.absolute(new BigDecimal("4")), ChangeType.DEPOSIT,
                null, null)).join();

        assertEquals(ChangeType.WITHDRAW, hooks.committed.getFirst().changeType());
        assertEquals(new BigDecimal("6.00"), hooks.committed.getFirst().amount());
    }

    @Test
    void absoluteSetToTheSameValueChangesNothing() {
        store.seed(COINS, "10.00");
        TransactionResult result = ledger.submit(new LedgerRequest(COINS,
                BalanceOperation.absolute(new BigDecimal("10")), ChangeType.DEPOSIT, null, null)).join();

        assertTrue(result.isSuccess());
        assertEquals(0.0, result.amount());
        assertEquals(0, store.writes.get());
        assertTrue(hooks.committed.isEmpty());
    }

    @Test
    void versionConflictIsRetriedWithAFreshRead() {
        store.seed(COINS, "10.00");
        store.conflictsLeft.set(2);

        TransactionResult result = ledger.submit(delta("5.00", ChangeType.DEPOSIT)).join();

        assertTrue(result.isSuccess());
        assertEquals(15.0, result.balance());
        assertEquals(3, store.writes.get());
        assertEquals(1, hooks.committed.size(), "only the written change is reported");
    }

    @Test
    void withdrawalThatDoesNotFitIsRefusedWithoutWrite() {
        store.seed(COINS, "5.00");

        TransactionResult result = ledger.submit(delta("-10.00", ChangeType.WITHDRAW)).join();

        assertTrue(result.isFailed());
        assertEquals(FailureReason.INSUFFICIENT_FUNDS.text(), result.error());
        assertEquals(10.0, result.amount());
        assertEquals(5.0, result.balance());
        assertEquals(0, store.writes.get());
        assertTrue(hooks.committed.isEmpty());
    }

    @Test
    void vetoedChangeKeepsTheBalance() {
        store.seed(COINS, "5.00");
        hooks.vetoReason = "blocked by test";

        TransactionResult result = ledger.submit(delta("3.00", ChangeType.DEPOSIT)).join();

        assertEquals("blocked by test", result.error());
        assertEquals(new BigDecimal("5.00"), store.balance(COINS));
        assertEquals(0, store.writes.get());
    }

    @Test
    void failedChangeDoesNotBlockTheQueue() {
        store.failNextLoad.set(true);
        CompletableFuture<TransactionResult> broken = ledger.submit(delta("1.00", ChangeType.DEPOSIT));
        TransactionResult next = ledger.submit(delta("2.00", ChangeType.DEPOSIT)).join();

        assertTrue(broken.isCompletedExceptionally());
        assertTrue(next.isSuccess());
        assertEquals(new BigDecimal("2.00"), store.balance(COINS));
    }

    @Test
    void amountsAreRoundedHalfUpToCents() {
        TransactionResult result = ledger.submit(delta("0.005", ChangeType.DEPOSIT)).join();

        assertEquals(0.01, result.balance());
        assertEquals(new BigDecimal("0.01"), store.balance(COINS));
    }

    @Test
    void refusedCallersBookNothing() {
        ledger.refuseWhen(() -> true);

        var refused = ledger.submit(delta("5.00", ChangeType.DEPOSIT));

        assertTrue(refused.isCompletedExceptionally());
        assertEquals(0, ledger.pendingAccounts());
        assertTrue(hooks.committed.isEmpty());

        ledger.refuseWhen(() -> false);
        assertTrue(ledger.submit(delta("5.00", ChangeType.DEPOSIT)).join().isSuccess());
        assertEquals(new BigDecimal("5.00"), store.balance(COINS));
    }

    @Test
    void aVersionConflictDoesNotAskTheListenersAgain() {
        store.seed(COINS, "10.00");
        store.conflictsLeft.set(2);

        assertTrue(ledger.submit(delta("5.00", ChangeType.DEPOSIT)).join().isSuccess());

        assertEquals(1, hooks.vetoCalls.get());
        assertEquals(new BigDecimal("15.00"), store.balance(COINS));
    }

    @Test
    void unknownNameKeepsTheStoredName() {
        AccountKey unknown = AccountKey.of(PLAYER, null, "coins");
        AccountKey floodgate = AccountKey.of(PLAYER, ".BedrockPlayer123", "coins");

        assertFalse(unknown.nameKnown());
        assertEquals(null, unknown.storedName());
        assertEquals(PLAYER.toString().substring(0, 16), unknown.playerName());
        assertEquals(".BedrockPlayer12", floodgate.storedName());
        assertEquals(COINS.queueKey(), unknown.queueKey());
    }

    private static @NotNull LedgerRequest delta(@NotNull String amount, @NotNull ChangeType type) {
        return new LedgerRequest(COINS, BalanceOperation.delta(new BigDecimal(amount)), type, "test", null);
    }

    private static void awaitQuietly(@NotNull CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** In-memory accounts with a version check like the database row. */
    private static final class FakeStore implements LedgerStore {

        private final Map<String, LedgerEntry> rows = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> active = new ConcurrentHashMap<>();
        private final AtomicBoolean overlapped = new AtomicBoolean();
        private final AtomicInteger writes = new AtomicInteger();
        private final AtomicInteger conflictsLeft = new AtomicInteger();
        private final AtomicBoolean failNextLoad = new AtomicBoolean();

        void seed(@NotNull AccountKey key, @NotNull String balance) {
            rows.put(key.queueKey(), new LedgerEntry(1L, 0, Money.of(new BigDecimal(balance))));
        }

        @NotNull BigDecimal balance(@NotNull AccountKey key) {
            return rows.get(key.queueKey()).balance();
        }

        @Override
        public @NotNull LedgerEntry load(@NotNull AccountKey key) {
            if (active.computeIfAbsent(key.queueKey(), ignored -> new AtomicInteger()).incrementAndGet() > 1) {
                overlapped.set(true);
            }
            if (failNextLoad.getAndSet(false)) {
                active.get(key.queueKey()).decrementAndGet();
                throw new IllegalStateException("database down");
            }
            return rows.computeIfAbsent(key.queueKey(), ignored -> new LedgerEntry(1L, 0, Money.ZERO));
        }

        @Override
        public void write(@NotNull LedgerEntry entry, @NotNull BigDecimal balance) {
            String key = COINS.queueKey();
            writes.incrementAndGet();
            try {
                if (conflictsLeft.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                    throw new IllegalStateException("wrapped", new OptimisticLockException("stale"));
                }
                LedgerEntry current = rows.get(key);
                if (current.version() != entry.version()) {
                    throw new OptimisticLockException("stale");
                }
                rows.put(key, new LedgerEntry(entry.accountId(), entry.version() + 1, balance));
            } finally {
                active.get(key).decrementAndGet();
            }
        }

        @Override
        public void writeBoth(@NotNull LedgerEntry first, @NotNull BigDecimal firstBalance,
                              @NotNull LedgerEntry second, @NotNull BigDecimal secondBalance) {
            throw new UnsupportedOperationException("not used here");
        }
    }

    /** Hooks that record committed changes and can refuse. */
    private static final class RecordingHooks implements LedgerHooks {

        private final List<BalanceChange> committed = new ArrayList<>();
        private volatile String vetoReason;
        private final AtomicInteger vetoCalls = new AtomicInteger();

        @Override
        public @NotNull Optional<String> veto(@NotNull BalanceChange change,
                                              @NotNull BooleanSupplier dispatchBlocked) {
            vetoCalls.incrementAndGet();
            return Optional.ofNullable(vetoReason);
        }

        @Override
        public synchronized void committed(@NotNull BalanceChange change) {
            committed.add(change);
        }
    }
}
