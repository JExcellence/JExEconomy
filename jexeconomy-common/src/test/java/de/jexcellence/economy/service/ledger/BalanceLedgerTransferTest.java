package de.jexcellence.economy.service.ledger;

import de.jexcellence.economy.api.ChangeType;
import de.jexcellence.economy.api.TransactionResult;
import de.jexcellence.economy.money.Money;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Transfers book both balances in one store call or none, and a waiting dispatch thread only skips the pre-events of
 * its own account.
 */
class BalanceLedgerTransferTest {

    private static final AccountKey ALICE = AccountKey.of(UUID.randomUUID(), "Alice", "coins");
    private static final AccountKey BOB = AccountKey.of(UUID.randomUUID(), "Bob", "coins");

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final TwoAccounts store = new TwoAccounts();
    private final CountingHooks hooks = new CountingHooks();
    private final BalanceLedger ledger = new BalanceLedger(store, hooks, executor);

    @AfterEach
    void stop() {
        executor.shutdownNow();
    }

    @Test
    void aTransferMovesTheMoneyInOneWrite() {
        store.seed(ALICE, "100.00");

        TransactionResult result = ledger.submitTransfer(transfer(ALICE, BOB, "30.00")).join();

        assertTrue(result.isSuccess());
        assertEquals(70.0, result.balance());
        assertEquals(new BigDecimal("70.00"), store.balance(ALICE));
        assertEquals(new BigDecimal("30.00"), store.balance(BOB));
        assertEquals(1, store.pairWrites.get());
        assertEquals(List.of(ChangeType.WITHDRAW, ChangeType.DEPOSIT), hooks.committedTypes());
    }

    @Test
    void anUncoveredTransferBooksNothing() {
        store.seed(ALICE, "10.00");

        TransactionResult result = ledger.submitTransfer(transfer(ALICE, BOB, "30.00")).join();

        assertFalse(result.isSuccess());
        assertEquals(0, store.pairWrites.get());
        assertEquals(new BigDecimal("10.00"), store.balance(ALICE));
    }

    @Test
    void aRefusalOnTheRecipientSideBooksNothing() {
        store.seed(ALICE, "100.00");
        hooks.refuseFor = BOB;

        TransactionResult result = ledger.submitTransfer(transfer(ALICE, BOB, "30.00")).join();

        assertFalse(result.isSuccess());
        assertTrue(result.error().endsWith(": refused"), result.error());
        assertEquals(new BigDecimal("100.00"), store.balance(ALICE));
        assertEquals(0, store.pairWrites.get());
    }

    @Test
    void aFailedWriteChangesNeitherAccount() {
        store.seed(ALICE, "100.00");
        store.failPair.set(true);

        var booking = ledger.submitTransfer(transfer(ALICE, BOB, "30.00"));

        assertThrows(RuntimeException.class, booking::join);
        assertEquals(new BigDecimal("100.00"), store.balance(ALICE));
        assertEquals(Money.ZERO, store.balance(BOB));
    }

    @Test
    void aVersionConflictRetriesWithoutAskingTheListenersAgain() {
        store.seed(ALICE, "100.00");
        store.pairConflicts.set(2);

        assertTrue(ledger.submitTransfer(transfer(ALICE, BOB, "30.00")).join().isSuccess());

        assertEquals(2, hooks.vetoCalls.get(), "one pre-event per side, not one per attempt");
        assertEquals(new BigDecimal("70.00"), store.balance(ALICE));
    }

    @Test
    void transfersInOppositeDirectionsDoNotBlockEachOther() {
        store.seed(ALICE, "1000.00");
        store.seed(BOB, "1000.00");
        List<CompletableFuture<TransactionResult>> running = new ArrayList<>();

        for (int round = 0; round < 200; round++) {
            running.add(ledger.submitTransfer(transfer(ALICE, BOB, "1.00")));
            running.add(ledger.submitTransfer(transfer(BOB, ALICE, "1.00")));
        }

        CompletableFuture.allOf(running.toArray(CompletableFuture[]::new)).orTimeout(30L, TimeUnit.SECONDS).join();
        assertEquals(new BigDecimal("1000.00"), store.balance(ALICE));
        assertEquals(new BigDecimal("1000.00"), store.balance(BOB));
        assertEquals(0, ledger.pendingAccounts());
    }

    @Test
    void parkingOnlyMarksThePreEventsOfThatAccountAsBlocked() {
        store.seed(ALICE, "100.00");
        store.seed(BOB, "100.00");

        try (var ignored = ledger.parkDispatchThread(ALICE)) {
            ledger.submit(delta(ALICE)).join();
            ledger.submit(delta(BOB)).join();
        }
        ledger.submit(delta(ALICE)).join();

        assertEquals(List.of(true, false, false), hooks.blockedSeen);
    }

    @Test
    void parkingAlsoCoversAccountsJoinedByAQueuedTransfer() {
        store.seed(ALICE, "100.00");

        try (var ignored = ledger.parkDispatchThread(ALICE)) {
            ledger.submitTransfer(transfer(ALICE, BOB, "1.00")).join();
        }

        assertEquals(List.of(true, true), hooks.blockedSeen);
    }

    @Test
    void aCallerThatCannotWaitSkipsThePreEvent() {
        store.seed(ALICE, "100.00");

        ledger.submit(new LedgerRequest(ALICE, BalanceOperation.delta(new BigDecimal("1.00")), ChangeType.DEPOSIT,
                "test", null, true)).join();

        assertEquals(0, hooks.vetoCalls.get());
        assertEquals(new BigDecimal("101.00"), store.balance(ALICE));
    }

    private static @NotNull LedgerRequest delta(@NotNull AccountKey key) {
        return new LedgerRequest(key, BalanceOperation.delta(new BigDecimal("1.00")), ChangeType.DEPOSIT, "test", null);
    }

    private static @NotNull LedgerTransfer transfer(@NotNull AccountKey from, @NotNull AccountKey to,
                                                    @NotNull String amount) {
        return new LedgerTransfer(from, to, new BigDecimal(amount), "out", "in", from.playerId());
    }

    private static final class TwoAccounts implements LedgerStore {

        private final Map<Long, LedgerEntry> rows = new ConcurrentHashMap<>();
        private final Map<String, Long> ids = new ConcurrentHashMap<>();
        private final AtomicInteger pairWrites = new AtomicInteger();
        private final AtomicInteger pairConflicts = new AtomicInteger();
        private final AtomicBoolean failPair = new AtomicBoolean();

        void seed(@NotNull AccountKey key, @NotNull String balance) {
            long id = idOf(key);
            rows.put(id, new LedgerEntry(id, 0, Money.of(new BigDecimal(balance))));
        }

        @NotNull BigDecimal balance(@NotNull AccountKey key) {
            return rows.get(idOf(key)).balance();
        }

        private long idOf(@NotNull AccountKey key) {
            return ids.computeIfAbsent(key.queueKey(), ignored -> (long) ids.size() + 1L);
        }

        @Override
        public @NotNull LedgerEntry load(@NotNull AccountKey key) {
            long id = idOf(key);
            return rows.computeIfAbsent(id, ignored -> new LedgerEntry(id, 0, Money.ZERO));
        }

        @Override
        public void write(@NotNull LedgerEntry entry, @NotNull BigDecimal balance) {
            rows.put(entry.accountId(), new LedgerEntry(entry.accountId(), entry.version() + 1, balance));
        }

        @Override
        public void writeBoth(@NotNull LedgerEntry first, @NotNull BigDecimal firstBalance,
                              @NotNull LedgerEntry second, @NotNull BigDecimal secondBalance) {
            if (failPair.get()) {
                throw new IllegalStateException("database down");
            }
            if (pairConflicts.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                throw new OptimisticLockException("stale");
            }
            pairWrites.incrementAndGet();
            write(first, firstBalance);
            write(second, secondBalance);
        }
    }

    private static final class CountingHooks implements LedgerHooks {

        private final AtomicInteger vetoCalls = new AtomicInteger();
        private final List<BalanceChange> committed = new ArrayList<>();
        private final List<Boolean> blockedSeen = new ArrayList<>();
        private volatile AccountKey refuseFor;

        @Override
        public synchronized @NotNull Optional<String> veto(@NotNull BalanceChange change,
                                                           @NotNull BooleanSupplier dispatchBlocked) {
            vetoCalls.incrementAndGet();
            blockedSeen.add(dispatchBlocked.getAsBoolean());
            return change.key().equals(refuseFor) ? Optional.of("refused") : Optional.empty();
        }

        @Override
        public synchronized void committed(@NotNull BalanceChange change) {
            committed.add(change);
        }

        synchronized @NotNull List<ChangeType> committedTypes() {
            return committed.stream().map(BalanceChange::changeType).toList();
        }
    }
}
