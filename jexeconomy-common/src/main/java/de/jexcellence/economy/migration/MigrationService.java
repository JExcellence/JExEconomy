package de.jexcellence.economy.migration;

import de.jexcellence.economy.api.ChangeType;
import de.jexcellence.economy.api.TransactionResult;
import de.jexcellence.economy.database.entity.MigrationEntry;
import de.jexcellence.economy.database.entity.MigrationRun;
import de.jexcellence.economy.migration.MigrationPlanner.Plan;
import de.jexcellence.economy.migration.MigrationPlanner.Planned;
import de.jexcellence.economy.money.Money;
import de.jexcellence.economy.service.ledger.AccountKey;
import de.jexcellence.economy.service.ledger.BalanceOperation;
import de.jexcellence.economy.service.ledger.LedgerRequest;
import de.jexcellence.jexplatform.logging.JExLogger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Imports balances from another economy: a dry run first, then a run that can be paused, survives a restart and can
 * be rolled back. One import runs at a time. Balances are written through the ledger, so each change is logged and
 * fires its events. Before each entry the current balance is compared with the plan, which makes a resumed run safe:
 * a balance that already has its target is not written twice, and one that changed in between is left alone.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public final class MigrationService {

    static final Duration PLAN_VALID_FOR = Duration.ofHours(1L);

    private static final int BATCH_SIZE = 50;
    private static final long NONE = -1L;
    private static final String REASON = "migration:";
    private static final String ROLLBACK_REASON = "migration-rollback:";
    private static final String CHANGED = "Balance changed since the plan";
    private static final String BUSY = "busy";

    private final MigrationStore store;
    private final Function<LedgerRequest, CompletableFuture<TransactionResult>> ledger;
    private final Supplier<CompletableFuture<List<MigrationSource>>> sources;
    private final Path folder;
    private final Executor executor;
    private final JExLogger logger;
    private static final Set<MigrationRun.State> ROLLBACK_FROM = Set.of(MigrationRun.State.DONE,
            MigrationRun.State.PAUSED, MigrationRun.State.ROLLING_BACK);

    private final AtomicLong activeRun = new AtomicLong(NONE);
    private final AtomicBoolean pauseRequested = new AtomicBoolean();

    /**
     * Creates the service.
     *
     * @param store    the import data
     * @param ledger   books a balance change
     * @param sources  finds the available sources
     * @param folder   receives one folder of report files per run
     * @param executor runs file work
     * @param logger   the plugin logger
     */
    public MigrationService(@NotNull MigrationStore store,
                            @NotNull Function<LedgerRequest, CompletableFuture<TransactionResult>> ledger,
                            @NotNull Supplier<CompletableFuture<List<MigrationSource>>> sources,
                            @NotNull Path folder, @NotNull Executor executor, @NotNull JExLogger logger) {
        this.store = store;
        this.ledger = ledger;
        this.sources = sources;
        this.folder = folder;
        this.executor = executor;
        this.logger = logger;
    }

    /**
     * A finished dry run.
     *
     * @param planId the id {@code start} takes
     * @param report the report
     */
    public record PlanResult(long planId, @NotNull MigrationReport report) {
    }

    /**
     * A finished rollback.
     *
     * @param restored the balances restored
     * @param changed  the balances left alone because they changed after the run
     */
    public record RollbackResult(int restored, int changed) {
    }

    /** A batch run: an import can be paused, a rollback always finishes. */
    private enum Phase {
        IMPORT(MigrationEntry.State.DONE, MigrationRun.State.RUNNING, true),
        ROLLBACK(MigrationEntry.State.ROLLED_BACK, MigrationRun.State.ROLLING_BACK, false);

        private final MigrationEntry.State success;
        private final MigrationRun.State active;
        private final boolean pausable;

        Phase(@NotNull MigrationEntry.State success, @NotNull MigrationRun.State active, boolean pausable) {
            this.success = success;
            this.active = active;
            this.pausable = pausable;
        }
    }

    /**
     * The counts of a batch run.
     *
     * @param imported the entries that reached the phase's success state
     * @param failed   the other entries
     */
    private record Job(long runId, @NotNull MigrationMode mode, @NotNull Phase phase, @NotNull String currencyId,
                       int imported, int failed) {

        private @NotNull Job counted(@NotNull List<MigrationEntry> batch) {
            int succeeded = (int) batch.stream().filter(entry -> entry.getState() == phase.success).count();
            return new Job(runId, mode, phase, currencyId, imported + succeeded, failed + batch.size() - succeeded);
        }
    }

    private record Batches(@NotNull Job job, @NotNull List<MigrationEntry> entries, int from) {
    }

    private record Scan(@NotNull MigrationPlanner.Known known, @NotNull MigrationSource.SourceRead read,
                        @NotNull Map<UUID, MigrationEntry> existing) {
    }

    /**
     * The available sources.
     *
     * @return future resolving to the sources
     */
    public @NotNull CompletableFuture<List<MigrationSource>> sources() {
        return sources.get();
    }

    /**
     * Makes a dry run, stores it and writes its report. Changes no balance.
     *
     * @param sourceId   the source
     * @param currencyId the target currency
     * @param mode       the import mode
     * @return future resolving to the plan; fails with {@link MigrationRefused} when the source is unknown
     */
    public @NotNull CompletableFuture<PlanResult> plan(@NotNull String sourceId, @NotNull String currencyId,
                                                       @NotNull MigrationMode mode) {
        return source(sourceId)
                .thenCompose(source -> scan(source, currencyId)
                        .thenCompose(scan -> {
                            Plan plan = MigrationPlanner.plan(scan.read(), mode, scan.known());
                            return store.createRun(new MigrationRun(source.id(), currencyId, mode, plan.report()))
                                    .thenApplyAsync(run -> {
                                        writeReport(run.getId(), plan.report());
                                        return new PlanResult(run.getId(), plan.report());
                                    }, executor);
                        }));
    }

    /**
     * Starts a recent plan. The source is read again, so the import uses current balances.
     *
     * @param planId the plan
     * @return future that completes when the import is under way
     */
    public @NotNull CompletableFuture<Void> start(long planId) {
        if (!activeRun.compareAndSet(NONE, planId)) {
            return CompletableFuture.failedFuture(new MigrationRefused(BUSY));
        }
        pauseRequested.set(false);
        return store.run(planId)
                .thenCompose(found -> begin(found.orElseThrow(() -> new MigrationRefused("plan-unknown"))))
                .whenComplete((started, failure) -> releaseOnFailure(failure));
    }

    private void releaseOnFailure(Throwable failure) {
        if (failure != null) {
            activeRun.set(NONE);
        }
    }

    private @NotNull CompletableFuture<Void> begin(@NotNull MigrationRun plan) {
        boolean fresh = plan.getState() == MigrationRun.State.PLANNED
                && LocalDateTime.now(MigrationRun.ZONE).isBefore(plan.getPlannedAt().plus(PLAN_VALID_FOR));
        if (!fresh) {
            throw new MigrationRefused("plan-stale");
        }
        long runId = plan.getId();
        return source(plan.getSourceId())
                .thenCompose(source -> scan(source, plan.getCurrencyId()))
                .thenCompose(scan -> prepare(plan, scan))
                .thenCompose(prepared -> store.progress(runId, MigrationRun.State.RUNNING, 0, 0))
                .thenRun(() -> launch(new Job(runId, plan.getMode(), Phase.IMPORT, plan.getCurrencyId(), 0, 0)));
    }

    private @NotNull CompletableFuture<Void> prepare(@NotNull MigrationRun plan, @NotNull Scan scan) {
        Plan fresh = MigrationPlanner.plan(scan.read(), plan.getMode(), scan.known());
        long runId = plan.getId();
        List<MigrationEntry> rows = new ArrayList<>(fresh.entries().size());
        for (Planned planned : fresh.entries()) {
            MigrationEntry row = scan.existing().get(planned.player());
            if (row == null) {
                row = new MigrationEntry(plan.getSourceId(), planned.player(), plan.getCurrencyId());
            }
            row.assign(runId, planned.name(), planned.amount(), planned.before(), planned.expected());
            rows.add(row);
        }
        return CompletableFuture.runAsync(() -> writeBackup(runId, fresh.entries()), executor)
                .thenCompose(written -> store.save(rows));
    }

    private void launch(@NotNull Job job) {
        store.entries(job.runId(), MigrationEntry.State.PENDING)
                .thenCompose(pending -> nextBatch(new Batches(job, pending, 0),
                        (entry, balances) -> book(job, balances, entry), this::finishImport))
                .whenComplete((finished, failure) -> {
                    if (failure != null) {
                        pauseAfterFailure(job.runId(), failure);
                    }
                    activeRun.set(NONE);
                });
    }

    private @NotNull CompletableFuture<Void> finishImport(@NotNull Job tally) {
        logger.info("Migration run {} finished: {} imported, {} failed", tally.runId(), tally.imported(),
                tally.failed());
        return store.progress(tally.runId(), MigrationRun.State.DONE, tally.imported(), tally.failed());
    }

    /** Keeps the counts stored after the last finished batch. */
    private void pauseAfterFailure(long runId, @NotNull Throwable failure) {
        logger.error("Migration run {} stopped and is now paused: {}", runId, String.valueOf(failure.getMessage()));
        store.run(runId)
                .thenCompose(found -> found.map(this::markPaused).orElse(CompletableFuture.completedFuture(null)))
                .exceptionally(stored -> {
                    logger.error("Migration run {} could not be marked as paused: {}", runId,
                            String.valueOf(stored.getMessage()));
                    return null;
                });
    }

    private @NotNull CompletableFuture<Void> markPaused(@NotNull MigrationRun run) {
        return store.progress(run.getId(), MigrationRun.State.PAUSED, run.getImported(), run.getFailed());
    }

    /**
     * Stops a running import after its current batch.
     *
     * @return {@code true} when an import was running
     */
    public boolean pause() {
        if (activeRun.get() == NONE) {
            return false;
        }
        pauseRequested.set(true);
        return true;
    }

    /**
     * Continues the newest paused import.
     *
     * @return future that completes when the import is under way
     */
    public @NotNull CompletableFuture<Void> resume() {
        return store.runsIn(MigrationRun.State.PAUSED).thenCompose(paused -> {
            MigrationRun run = paused.stream().findFirst().orElseThrow(() -> new MigrationRefused("nothing-paused"));
            if (!activeRun.compareAndSet(NONE, run.getId())) {
                throw new MigrationRefused(BUSY);
            }
            pauseRequested.set(false);
            return store.progress(run.getId(), MigrationRun.State.RUNNING, run.getImported(), run.getFailed())
                    .whenComplete((stored, failure) -> releaseOnFailure(failure))
                    .thenRun(() -> launch(new Job(run.getId(), run.getMode(), Phase.IMPORT, run.getCurrencyId(),
                            run.getImported(), run.getFailed())));
        });
    }

    /**
     * The newest import.
     *
     * @return future resolving to the run, empty when no import exists
     */
    public @NotNull CompletableFuture<Optional<MigrationRun>> status() {
        return store.latestRun();
    }

    /**
     * Restores the balances from before a run. A balance that changed after the run is left alone.
     *
     * @param runId the run
     * @return future resolving to the outcome
     */
    public @NotNull CompletableFuture<RollbackResult> rollback(long runId) {
        if (!activeRun.compareAndSet(NONE, runId)) {
            return CompletableFuture.failedFuture(new MigrationRefused(BUSY));
        }
        return store.run(runId)
                .thenCompose(found -> rollback(found.orElseThrow(() -> new MigrationRefused("run-unknown"))))
                .whenComplete((result, failure) -> activeRun.set(NONE));
    }

    private @NotNull CompletableFuture<RollbackResult> rollback(@NotNull MigrationRun run) {
        if (!ROLLBACK_FROM.contains(run.getState())) {
            throw new MigrationRefused("not-rollbackable");
        }
        long runId = run.getId();
        var job = new Job(runId, run.getMode(), Phase.ROLLBACK, run.getCurrencyId(), 0, 0);
        var last = new AtomicReference<>(job);
        return store.progress(runId, MigrationRun.State.ROLLING_BACK, run.getImported(), run.getFailed())
                .thenCompose(started -> store.entries(runId, MigrationEntry.State.DONE))
                .thenCompose(done -> nextBatch(new Batches(job, done, 0),
                        (entry, balances) -> restore(runId, balances, entry),
                        tally -> {
                            last.set(tally);
                            return store.progress(runId, MigrationRun.State.ROLLED_BACK, tally.imported(),
                                    tally.failed());
                        }))
                .whenComplete((finished, failure) -> {
                    if (failure != null) {
                        logger.error("Rollback of migration run {} stopped and can be repeated: {}", runId,
                                String.valueOf(failure.getMessage()));
                    }
                })
                .thenApply(finished -> new RollbackResult(last.get().imported(), last.get().failed()));
    }

    /** Marks imports a restart cut off as paused. The admin decides when they continue. */
    public void recoverInterrupted() {
        store.runsIn(MigrationRun.State.RUNNING)
                .thenCompose(running -> CompletableFuture.allOf(running.stream()
                        .map(this::pauseInterrupted)
                        .toArray(CompletableFuture[]::new)))
                .thenCompose(paused -> store.runsIn(MigrationRun.State.ROLLING_BACK))
                .thenAccept(rollbacks -> rollbacks.forEach(run -> logger.warn(
                        "Rollback of migration run {} was cut off by a restart. Repeat it with /economy migrate rollback.",
                        run.getId())))
                .exceptionally(failure -> {
                    logger.warn("Interrupted migrations could not be recovered: {}",
                            String.valueOf(failure.getMessage()));
                    return null;
                });
    }

    private @NotNull CompletableFuture<Void> pauseInterrupted(@NotNull MigrationRun run) {
        logger.warn("Migration run {} was cut off by a restart. Continue it with /economy migrate resume.",
                run.getId());
        return markPaused(run);
    }

    private @NotNull CompletableFuture<MigrationSource> source(@NotNull String sourceId) {
        return sources.get().thenApply(available -> available.stream()
                .filter(source -> source.id().equalsIgnoreCase(sourceId))
                .findFirst()
                .orElseThrow(() -> new MigrationRefused("source-unknown")));
    }

    private @NotNull CompletableFuture<Scan> scan(@NotNull MigrationSource source, @NotNull String currencyId) {
        var balances = store.balances(currencyId);
        var history = store.playersWithHistory(currencyId);
        var existing = store.entries(source.id(), currencyId);
        return source.read().thenCompose(read -> CompletableFuture.allOf(balances, history, existing)
                .thenApply(done -> toScan(read, balances.join(), history.join(), existing.join())));
    }

    private static @NotNull Scan toScan(@NotNull MigrationSource.SourceRead read,
                                        @NotNull Map<UUID, BigDecimal> balances, @NotNull Set<UUID> history,
                                        @NotNull List<MigrationEntry> existing) {
        Set<UUID> imported = existing.stream()
                .filter(entry -> entry.getState() == MigrationEntry.State.DONE)
                .map(MigrationEntry::getPlayerUuid)
                .collect(Collectors.toSet());
        Map<UUID, MigrationEntry> rows = new HashMap<>();
        existing.forEach(entry -> rows.put(entry.getPlayerUuid(), entry));
        return new Scan(new MigrationPlanner.Known(balances, history, imported), read, rows);
    }

    /**
     * Works through the entries in batches, storing them and the counts after each batch. Balances are read per batch,
     * so a change made while earlier batches ran is seen. A ledger error fails the batch before it is stored: its
     * entries stay pending, and the next attempt decides from the real balance whether each was booked.
     */
    private @NotNull CompletableFuture<Void> nextBatch(
            @NotNull Batches batches,
            @NotNull BiFunction<MigrationEntry, Map<UUID, BigDecimal>, CompletableFuture<Void>> work,
            @NotNull Function<Job, CompletableFuture<Void>> finish) {
        Job job = batches.job();
        if (job.phase().pausable && pauseRequested.get()) {
            return store.progress(job.runId(), MigrationRun.State.PAUSED, job.imported(), job.failed());
        }
        if (batches.from() >= batches.entries().size()) {
            return finish.apply(job);
        }
        int to = Math.min(batches.from() + BATCH_SIZE, batches.entries().size());
        List<MigrationEntry> batch = batches.entries().subList(batches.from(), to);
        return store.balances(job.currencyId(), batch.stream().map(MigrationEntry::getPlayerUuid).toList())
                .thenCompose(balances -> CompletableFuture.allOf(batch.stream()
                        .map(entry -> work.apply(entry, balances))
                        .toArray(CompletableFuture[]::new)))
                .thenCompose(done -> store.save(batch))
                .thenCompose(saved -> {
                    Job counted = job.counted(batch);
                    return store.progress(job.runId(), job.phase().active, counted.imported(), counted.failed())
                            .thenCompose(stored -> nextBatch(new Batches(counted, batches.entries(), to), work,
                                    finish));
                });
    }

    private @NotNull CompletableFuture<Void> book(@NotNull Job job, @NotNull Map<UUID, BigDecimal> balances,
                                                  @NotNull MigrationEntry entry) {
        BigDecimal now = balances.getOrDefault(entry.getPlayerUuid(), Money.ZERO);
        if (same(now, entry.getBalanceExpected())) {
            entry.markDone();
            return CompletableFuture.completedFuture(null);
        }
        if (!same(now, entry.getBalanceBefore())) {
            entry.markFailed(CHANGED);
            return CompletableFuture.completedFuture(null);
        }
        BalanceOperation operation = job.mode() == MigrationMode.ADD
                ? BalanceOperation.delta(entry.getSourceBalance())
                : BalanceOperation.compareAndSet(entry.getBalanceBefore(), entry.getBalanceExpected());
        return submit(entry, operation, REASON + job.runId()).thenAccept(result -> {
            if (result.isSuccess()) {
                entry.markDone();
            } else {
                entry.markFailed(String.valueOf(result.error()));
            }
        });
    }

    /**
     * An entry whose balance is already back counts as restored, which also covers a repeat after a cut-off rollback.
     * An entry that cannot be restored stays imported, so a later run cannot import that player a second time.
     */
    private @NotNull CompletableFuture<Void> restore(long runId, @NotNull Map<UUID, BigDecimal> balances,
                                                     @NotNull MigrationEntry entry) {
        BigDecimal now = balances.getOrDefault(entry.getPlayerUuid(), Money.ZERO);
        if (same(now, entry.getBalanceBefore())) {
            entry.markRolledBack();
            return CompletableFuture.completedFuture(null);
        }
        if (!same(now, entry.getBalanceExpected())) {
            return CompletableFuture.completedFuture(null);
        }
        BalanceOperation operation = BalanceOperation.compareAndSet(entry.getBalanceExpected(),
                entry.getBalanceBefore());
        return submit(entry, operation, ROLLBACK_REASON + runId).thenAccept(result -> {
            if (result.isSuccess()) {
                entry.markRolledBack();
            }
        });
    }

    private @NotNull CompletableFuture<TransactionResult> submit(@NotNull MigrationEntry entry,
                                                                 @NotNull BalanceOperation operation,
                                                                 @NotNull String reason) {
        var key = AccountKey.of(entry.getPlayerUuid(), entry.getPlayerName(), entry.getCurrencyId());
        return ledger.apply(new LedgerRequest(key, operation, ChangeType.DEPOSIT, reason, null, true));
    }

    private static boolean same(@NotNull BigDecimal first, @NotNull BigDecimal second) {
        return first.compareTo(second) == 0;
    }

    private void writeReport(long runId, @NotNull MigrationReport report) {
        var leftOut = report.counts();
        write(runId, "plan.txt", List.of(
                "players=" + report.players(),
                "source_total=" + report.sourceTotal().toPlainString(),
                "import_total=" + report.importTotal().toPlainString(),
                "rounding_loss=" + report.roundingLoss().toPlainString(),
                "invalid=" + leftOut.invalid(),
                "unreadable=" + leftOut.unreadable(),
                "empty=" + leftOut.empty(),
                "existing=" + leftOut.existing(),
                "already_imported=" + leftOut.alreadyImported()));
    }

    private void writeBackup(long runId, @NotNull List<Planned> entries) {
        List<String> lines = new ArrayList<>(entries.size() + 1);
        lines.add("uuid,name,balance_before");
        entries.forEach(entry -> lines.add(entry.player() + "," + (entry.name() != null ? entry.name() : "") + ","
                + entry.before().toPlainString()));
        write(runId, "before.csv", lines);
    }

    private void write(long runId, @NotNull String file, @NotNull List<String> lines) {
        try {
            Path target = folder.resolve(String.valueOf(runId));
            Files.createDirectories(target);
            Files.write(target.resolve(file), lines);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
