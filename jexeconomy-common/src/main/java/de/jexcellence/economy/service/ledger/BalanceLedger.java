package de.jexcellence.economy.service.ledger;

import de.jexcellence.economy.api.ChangeType;
import de.jexcellence.economy.api.TransactionResult;
import de.jexcellence.economy.database.PersistenceFailures;
import de.jexcellence.economy.money.Money;
import de.jexcellence.economy.service.FailureReason;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Applies balance changes one account at a time.
 *
 * <p>Every request joins the queue of its account ({@code uuid:currency}) and starts only after the previous request
 * of that account finished, whatever its outcome. A transfer joins the queues of both accounts and books both in one
 * database transaction. Each attempt reads the balance, computes the new value (a delta or an absolute set), asks the
 * {@link LedgerHooks} whether the change may happen, writes it with a version check and reports it. A version
 * conflict, which only another server or a direct database write can cause, is answered by reading again, up to
 * {@value #MAX_RETRIES} times. The conflict is recognised by exception type
 * ({@link PersistenceFailures#isOptimisticLock(Throwable)}), not by class name.
 *
 * <p>Store calls run on the ledger executor, never on the caller's thread. The pre-change hook may wait for a server
 * tick, so it runs on the separate veto executor: a request waiting for its listeners holds no database slot. It runs
 * once per request; a version conflict repeats only the read and the write.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public final class BalanceLedger {

    /** Attempts after the first one when the account row changed between read and write. */
    public static final int MAX_RETRIES = 8;

    private static final Logger LOGGER = Logger.getLogger(BalanceLedger.class.getName());

    private final LedgerStore store;
    private final LedgerHooks hooks;
    private final Executor executor;
    private final Executor vetoExecutor;
    private final Map<String, CompletableFuture<TransactionResult>> tails = new ConcurrentHashMap<>();
    private final ReentrantLock queueLock = new ReentrantLock();
    private final AtomicReference<BooleanSupplier> submitRefusal = new AtomicReference<>(() -> false);
    private final AtomicReference<String> parkedQueueKey = new AtomicReference<>();
    private final Set<List<String>> runningTransfers = ConcurrentHashMap.newKeySet();

    /**
     * Ends the park of the dispatch thread.
     */
    @FunctionalInterface
    public interface Parking extends AutoCloseable {

        /** For a caller that is not the dispatch thread: there is nothing to end. */
        Parking NONE = () -> {
            // Nothing was parked.
        };

        @Override
        void close();
    }

    /**
     * Creates a ledger whose pre-change hook shares the store executor.
     *
     * @param store    where balances are read and written
     * @param hooks    the pre- and post-change notification
     * @param executor runs every attempt off the caller's thread
     */
    public BalanceLedger(@NotNull LedgerStore store, @NotNull LedgerHooks hooks, @NotNull Executor executor) {
        this(store, hooks, executor, executor);
    }

    /**
     * Creates the ledger.
     *
     * @param store        where balances are read and written
     * @param hooks        the pre- and post-change notification
     * @param executor     runs the store work off the caller's thread
     * @param vetoExecutor runs the pre-change hook, which may wait for a server tick; must not limit the store work
     */
    public BalanceLedger(@NotNull LedgerStore store, @NotNull LedgerHooks hooks, @NotNull Executor executor,
                         @NotNull Executor vetoExecutor) {
        this.store = store;
        this.hooks = hooks;
        this.executor = executor;
        this.vetoExecutor = vetoExecutor;
    }

    /**
     * Queues a balance change behind the earlier changes of the same account.
     *
     * @param request the change
     * @return future resolving to the result; completes exceptionally when the store fails for another reason than
     *         a version conflict, or still conflicts after every retry
     */
    public @NotNull CompletableFuture<TransactionResult> submit(@NotNull LedgerRequest request) {
        if (submitRefusal.get().getAsBoolean()) {
            return CompletableFuture.failedFuture(shuttingDown());
        }
        Attempt attempt = new Attempt(() -> planChange(request), request.skipsPreEvent(), new AtomicBoolean(),
                request.key().queueKey());
        return enqueue(List.of(request.key().queueKey()), () -> attempt(attempt, MAX_RETRIES));
    }

    /**
     * Queues a transfer behind the earlier changes of both accounts; both balances change in one transaction, so a
     * crash cannot lose the amount between them.
     *
     * @param transfer the transfer
     * @return future resolving to the sender's result; completes exceptionally like {@link #submit}
     */
    public @NotNull CompletableFuture<TransactionResult> submitTransfer(@NotNull LedgerTransfer transfer) {
        if (submitRefusal.get().getAsBoolean()) {
            return CompletableFuture.failedFuture(shuttingDown());
        }
        String from = transfer.from().queueKey();
        String to = transfer.to().queueKey();
        Attempt attempt = new Attempt(() -> planTransfer(transfer), false, new AtomicBoolean(), from + " > " + to);
        List<String> accounts = List.of(from, to);
        runningTransfers.add(accounts);
        return enqueue(accounts, () -> attempt(attempt, MAX_RETRIES))
                .whenComplete((result, failure) -> runningTransfers.remove(accounts));
    }

    private static @NotNull IllegalStateException shuttingDown() {
        return new IllegalStateException("The economy is shutting down");
    }

    /**
     * Appends work to the queues of {@code keys} and starts it once all of their earlier work finished. The lock
     * makes the append atomic over all keys, so two transfers in opposite directions can never wait for each other.
     */
    private @NotNull CompletableFuture<TransactionResult> enqueue(
            @NotNull List<String> keys, @NotNull Supplier<CompletableFuture<TransactionResult>> work) {
        CompletableFuture<Void> gate = new CompletableFuture<>();
        CompletableFuture<TransactionResult> next = gate.thenCompose(started -> work.get());
        List<CompletableFuture<?>> earlier = new ArrayList<>();
        queueLock.lock();
        try {
            for (String key : keys) {
                tails.compute(key, (ignored, previous) -> {
                    if (previous != null) {
                        earlier.add(previous.handle((result, failure) -> null));
                    }
                    return next;
                });
            }
        } finally {
            queueLock.unlock();
        }
        CompletableFuture.allOf(earlier.toArray(CompletableFuture[]::new))
                .whenComplete((result, failure) -> gate.complete(null));
        keys.forEach(key -> next.whenComplete((result, failure) -> tails.remove(key, next)));
        return next.copy();
    }

    /**
     * Refuses new changes whose caller matches {@code condition}, used on shutdown to refuse new requests from the
     * main thread while changes already queued, and their follow-up legs on the workers, still complete.
     *
     * @param condition checked on the calling thread of every {@link #submit}
     */
    public void refuseWhen(@NotNull BooleanSupplier condition) {
        submitRefusal.set(condition);
    }

    /**
     * Number of accounts with a queued or running change.
     *
     * @return the queue count
     */
    public int pendingAccounts() {
        return tails.size();
    }

    /**
     * Records that the thread which dispatches pre-events now waits for a change of {@code key}. An earlier change
     * of that account, or of an account joined to it by a queued transfer, cannot get its pre-event dispatched while
     * this lasts and would hold up the waiting thread, so its pre-event is skipped; other accounts are not affected.
     *
     * @param key the account the dispatch thread waits for
     * @return closes the park
     */
    public @NotNull Parking parkDispatchThread(@NotNull AccountKey key) {
        parkedQueueKey.set(key.queueKey());
        return () -> parkedQueueKey.set(null);
    }

    private boolean isParked(@NotNull AccountKey key) {
        String parked = parkedQueueKey.get();
        if (parked == null) {
            return false;
        }
        Set<String> waitedFor = new HashSet<>(Set.of(parked));
        boolean grew = true;
        while (grew) {
            grew = false;
            for (List<String> accounts : runningTransfers) {
                if (accounts.stream().anyMatch(waitedFor::contains)) {
                    grew |= waitedFor.addAll(accounts);
                }
            }
        }
        return waitedFor.contains(key.queueKey());
    }

    private @NotNull CompletableFuture<TransactionResult> attempt(@NotNull Attempt attempt, int retriesLeft) {
        return CompletableFuture.supplyAsync(() -> planOrBook(attempt), executor)
                .thenCompose(plan -> plan.settled() != null
                        ? CompletableFuture.completedFuture(plan.settled())
                        : vetoThenBook(plan, attempt))
                .exceptionallyCompose(failure -> {
                    if (retriesLeft > 0 && PersistenceFailures.isOptimisticLock(failure)) {
                        final int retry = MAX_RETRIES - retriesLeft + 1;
                        LOGGER.log(Level.FINE, () -> "Version conflict on " + attempt.label()
                                + ", retry " + retry + "/" + MAX_RETRIES);
                        return attempt(attempt, retriesLeft - 1);
                    }
                    return CompletableFuture.failedFuture(failure);
                });
    }

    private @NotNull Plan planOrBook(@NotNull Attempt attempt) {
        Plan plan = attempt.planner().get();
        if (plan.settled() != null) {
            return plan;
        }
        boolean noVeto = attempt.vetoPassed().get() || attempt.skipsPreEvent() || !hooks.vetoRequired();
        return noVeto ? Plan.done(book(plan)) : plan;
    }

    private @NotNull CompletableFuture<TransactionResult> vetoThenBook(@NotNull Plan plan, @NotNull Attempt attempt) {
        return CompletableFuture.supplyAsync(() -> vetoOf(plan), vetoExecutor)
                .thenApplyAsync(refusal -> {
                    if (refusal.isPresent()) {
                        return refusal.get();
                    }
                    attempt.vetoPassed().set(true);
                    return book(plan);
                }, executor);
    }

    private @NotNull Optional<TransactionResult> vetoOf(@NotNull Plan plan) {
        for (Planned planned : plan.changes()) {
            BalanceChange change = planned.change();
            Optional<String> refusal = hooks.veto(change, () -> isParked(change.key()));
            if (refusal.isPresent()) {
                BalanceChange first = plan.changes().getFirst().change();
                return Optional.of(TransactionResult.failure(Money.toDouble(first.amount()),
                        Money.toDouble(first.oldBalance()), planned.refusalPrefix() + refusal.get()));
            }
        }
        return Optional.empty();
    }

    private @NotNull Plan planChange(@NotNull LedgerRequest request) {
        LedgerEntry entry = store.load(request.key());
        BigDecimal before = entry.balance();
        BalanceOperation operation = request.operation();
        BigDecimal after = Money.of(operation.apply(before));
        BigDecimal amount = Money.of(operation.requested(before));
        if (!operation.allows(before)) {
            return Plan.done(FailureReason.BALANCE_CHANGED.result(Money.toDouble(amount), Money.toDouble(before)));
        }
        if (after.signum() < 0) {
            return Plan.done(FailureReason.INSUFFICIENT_FUNDS.result(Money.toDouble(amount), Money.toDouble(before)));
        }
        if (operation.skipsNoOp() && after.compareTo(before) == 0) {
            return Plan.done(TransactionResult.success(0.0, Money.toDouble(before)));
        }
        var change = new BalanceChange(request.key(), before, after, amount,
                operation.changeType(request.changeType(), before), request.reason(), request.initiator());
        return Plan.booking(List.of(new Planned(entry, change, "")),
                TransactionResult.success(Money.toDouble(amount), Money.toDouble(after)));
    }

    private @NotNull Plan planTransfer(@NotNull LedgerTransfer transfer) {
        LedgerEntry from = store.load(transfer.from());
        LedgerEntry to = store.load(transfer.to());
        BigDecimal amount = Money.of(transfer.amount());
        BigDecimal fromAfter = Money.of(from.balance().subtract(amount));
        if (fromAfter.signum() < 0) {
            return Plan.done(FailureReason.INSUFFICIENT_FUNDS.result(Money.toDouble(amount),
                    Money.toDouble(from.balance())));
        }
        var outgoing = new BalanceChange(transfer.from(), from.balance(), fromAfter, amount, ChangeType.WITHDRAW,
                transfer.fromReason(), transfer.initiator());
        var incoming = new BalanceChange(transfer.to(), to.balance(), Money.of(to.balance().add(amount)), amount,
                ChangeType.DEPOSIT, transfer.toReason(), transfer.initiator());
        return Plan.booking(List.of(new Planned(from, outgoing, ""),
                        new Planned(to, incoming, FailureReason.TRANSFER_FAILED.text() + ": ")),
                TransactionResult.success(Money.toDouble(amount), Money.toDouble(fromAfter)));
    }

    private @NotNull TransactionResult book(@NotNull Plan plan) {
        List<Planned> changes = plan.changes();
        if (changes.size() == 1) {
            store.write(changes.getFirst().entry(), changes.getFirst().change().newBalance());
        } else {
            store.writeBoth(changes.get(0).entry(), changes.get(0).change().newBalance(),
                    changes.get(1).entry(), changes.get(1).change().newBalance());
        }
        changes.forEach(planned -> notifyCommitted(planned.change()));
        return plan.onBooked();
    }

    private void notifyCommitted(@NotNull BalanceChange change) {
        try {
            hooks.committed(change);
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, failure, () -> "Balance change of " + change.key().queueKey()
                    + " was written, but its notification failed: " + failure.getMessage());
        }
    }

    /** One run of a request: its planner and whether its listeners already agreed or do not need to be asked. */
    private record Attempt(@NotNull Supplier<Plan> planner, boolean skipsPreEvent, @NotNull AtomicBoolean vetoPassed,
                           @NotNull String label) {
    }

    /** One account change of a plan; a refusal of its listeners is reported with {@code refusalPrefix}. */
    private record Planned(@NotNull LedgerEntry entry, @NotNull BalanceChange change, @NotNull String refusalPrefix) {
    }

    /** Either a finished result, or the changes to veto and book with the result they lead to. */
    private record Plan(@Nullable TransactionResult settled, @NotNull List<Planned> changes,
                        @Nullable TransactionResult onBooked) {

        static @NotNull Plan done(@NotNull TransactionResult result) {
            return new Plan(result, List.of(), null);
        }

        static @NotNull Plan booking(@NotNull List<Planned> changes, @NotNull TransactionResult onBooked) {
            return new Plan(null, changes, onBooked);
        }
    }
}
