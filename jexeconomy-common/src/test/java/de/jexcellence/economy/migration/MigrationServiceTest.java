package de.jexcellence.economy.migration;

import de.jexcellence.economy.api.TransactionResult;
import de.jexcellence.economy.database.entity.MigrationEntry;
import de.jexcellence.economy.database.entity.MigrationRun;
import de.jexcellence.economy.migration.MigrationSource.SourceBalance;
import de.jexcellence.economy.migration.MigrationSource.SourceRead;
import de.jexcellence.economy.money.Money;
import de.jexcellence.economy.service.FailureReason;
import de.jexcellence.economy.service.ledger.LedgerRequest;
import de.jexcellence.jexplatform.logging.JExLogger;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * An import is exact, runs once per player, survives a restart and can be taken back.
 */
class MigrationServiceTest {

    private static final String SOURCE = "csv:test";
    private static final String COINS = "coins";
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private final MemoryStore store = new MemoryStore();
    private final List<SourceBalance> sourceBalances = new ArrayList<>();
    private final AtomicInteger bookings = new AtomicInteger();
    private Consumer<LedgerRequest> beforeBooking = request -> { };
    private boolean failsAfterCommit;
    private MigrationService service;
    @TempDir
    private Path folder;

    @BeforeEach
    void wire() {
        MigrationSource source = new MigrationSource() {
            @Override
            public @NotNull String id() {
                return SOURCE;
            }

            @Override
            public @NotNull CompletableFuture<SourceRead> read() {
                return CompletableFuture.completedFuture(new SourceRead(List.copyOf(sourceBalances), 0));
            }
        };
        service = new MigrationService(store, this::book, () -> CompletableFuture.completedFuture(List.of(source)),
                folder, Runnable::run, JExLogger.of("MigrationServiceTest"));
    }

    private CompletableFuture<TransactionResult> book(LedgerRequest request) {
        beforeBooking.accept(request);
        UUID player = request.key().playerId();
        BigDecimal now = store.balances.getOrDefault(player, Money.ZERO);
        if (!request.operation().allows(now)) {
            return CompletableFuture.completedFuture(FailureReason.BALANCE_CHANGED.result(0.0, now.doubleValue()));
        }
        bookings.incrementAndGet();
        BigDecimal after = request.operation().apply(now);
        store.balances.put(player, after);
        if (failsAfterCommit) {
            failsAfterCommit = false;
            return CompletableFuture.failedFuture(new IllegalStateException("connection lost after commit"));
        }
        return CompletableFuture.completedFuture(TransactionResult.success(0.0, after.doubleValue()));
    }

    private void source(UUID player, String balance) {
        sourceBalances.add(new SourceBalance(player, "name", new BigDecimal(balance)));
    }

    private long plan(MigrationMode mode) {
        return service.plan(SOURCE, COINS, mode).join().planId();
    }

    private long imported(MigrationMode mode) {
        long id = plan(mode);
        service.start(id).join();
        return id;
    }

    private BigDecimal balance(UUID player) {
        return store.balances.getOrDefault(player, Money.ZERO);
    }

    private static void assertSame(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> expected + " but was " + actual);
    }

    private static void assertRefused(String reason, CompletableFuture<?> future) {
        var failure = assertThrows(CompletionException.class, future::join);
        assertEquals(reason, assertInstanceOf(MigrationRefused.class, failure.getCause()).reason());
    }

    @Test
    void aPlanMovesNothingAndWritesAReport() {
        source(ALEX, "10.50");

        var result = service.plan(SOURCE, COINS, MigrationMode.SKIP_EXISTING).join();

        assertEquals(1, result.report().players());
        assertEquals(0, bookings.get());
        assertTrue(Files.exists(folder.resolve(String.valueOf(result.planId())).resolve("plan.txt")));
    }

    @Test
    void aRunImportsTheBalancesAndKeepsABackup() {
        source(ALEX, "10.50");
        source(STEVE, "3");

        long id = imported(MigrationMode.SKIP_EXISTING);

        assertSame("10.50", balance(ALEX));
        assertSame("3", balance(STEVE));
        assertEquals(MigrationRun.State.DONE, store.runs.get(id).getState());
        assertEquals(2, store.runs.get(id).getImported());
        assertTrue(Files.exists(folder.resolve(String.valueOf(id)).resolve("before.csv")));
    }

    @Test
    void anImportedPlayerIsNeverImportedTwiceFromTheSameSource() {
        source(ALEX, "10");
        imported(MigrationMode.ADD);

        var again = service.plan(SOURCE, COINS, MigrationMode.ADD).join();
        service.start(again.planId()).join();

        assertEquals(1, again.report().counts().alreadyImported());
        assertSame("10", balance(ALEX));
    }

    @Test
    void skipExistingLeavesAnAccountWithABalanceOrHistoryAlone() {
        store.balances.put(ALEX, new BigDecimal("5"));
        store.history.add(STEVE);
        source(ALEX, "10");
        source(STEVE, "10");

        var result = service.plan(SOURCE, COINS, MigrationMode.SKIP_EXISTING).join();

        assertEquals(0, result.report().players());
        assertEquals(2, result.report().counts().existing());
    }

    @Test
    void addKeepsWhatThePlayerAlreadyHas() {
        store.balances.put(ALEX, new BigDecimal("5"));
        source(ALEX, "10");

        imported(MigrationMode.ADD);

        assertSame("15", balance(ALEX));
    }

    @Test
    void overwriteReplacesTheBalance() {
        store.balances.put(ALEX, new BigDecimal("5"));
        source(ALEX, "10");

        imported(MigrationMode.OVERWRITE);

        assertSame("10", balance(ALEX));
    }

    @Test
    void aBalanceThatChangedAfterThePlanIsLeftAlone() {
        source(ALEX, "10");
        long id = plan(MigrationMode.ADD);
        store.afterEntriesSaved = () -> store.balances.put(ALEX, new BigDecimal("99"));

        service.start(id).join();

        assertSame("99", balance(ALEX));
        assertEquals(1, store.runs.get(id).getFailed());
        assertEquals(0, bookings.get());
    }

    @Test
    void anEntryAlreadyBookedBeforeACrashIsNotBookedAgain() {
        source(ALEX, "10");
        long id = plan(MigrationMode.ADD);
        store.afterEntriesSaved = () -> store.balances.put(ALEX, new BigDecimal("10"));

        service.start(id).join();

        assertSame("10", balance(ALEX));
        assertEquals(1, store.runs.get(id).getImported());
        assertEquals(0, bookings.get());
    }

    @Test
    void aPausedRunStopsAfterItsBatchAndResumes() {
        for (int player = 0; player < 60; player++) {
            source(new UUID(1L, player), "1");
        }
        beforeBooking = request -> service.pause();
        long id = plan(MigrationMode.ADD);

        service.start(id).join();

        assertEquals(MigrationRun.State.PAUSED, store.runs.get(id).getState());
        assertEquals(50, bookings.get());

        beforeBooking = request -> { };
        service.resume().join();

        assertEquals(MigrationRun.State.DONE, store.runs.get(id).getState());
        assertEquals(60, store.runs.get(id).getImported());
        assertEquals(60, bookings.get());
    }

    @Test
    void aRunCutOffByARestartIsMarkedPaused() {
        source(ALEX, "10");
        long id = plan(MigrationMode.ADD);
        store.progress(id, MigrationRun.State.RUNNING, 0, 0);

        service.recoverInterrupted();

        assertEquals(MigrationRun.State.PAUSED, store.runs.get(id).getState());
    }

    @Test
    void aRollbackRestoresTheOldBalances() {
        store.balances.put(ALEX, new BigDecimal("5"));
        source(ALEX, "10");
        long id = imported(MigrationMode.ADD);

        var result = service.rollback(id).join();

        assertEquals(1, result.restored());
        assertSame("5", balance(ALEX));
        assertEquals(MigrationRun.State.ROLLED_BACK, store.runs.get(id).getState());
    }

    @Test
    void aRollbackLeavesAChangedBalanceAndKeepsItImported() {
        source(ALEX, "10");
        long id = imported(MigrationMode.ADD);
        store.balances.put(ALEX, new BigDecimal("7"));

        var result = service.rollback(id).join();

        assertEquals(0, result.restored());
        assertEquals(1, result.changed());
        assertSame("7", balance(ALEX));
        assertEquals(MigrationEntry.State.DONE, store.entries.getFirst().getState());
    }

    @Test
    void aRolledBackPlayerCanBeImportedAgain() {
        source(ALEX, "10");
        service.rollback(imported(MigrationMode.ADD)).join();

        var again = service.plan(SOURCE, COINS, MigrationMode.ADD).join();
        service.start(again.planId()).join();

        assertSame("10", balance(ALEX));
        assertEquals(1, store.entries.size());
    }

    @Test
    void aRollbackThatFailsCanBeRepeated() {
        source(ALEX, "10");
        long id = imported(MigrationMode.ADD);
        store.saveFails = true;

        assertThrows(CompletionException.class, () -> service.rollback(id).join());
        store.saveFails = false;

        assertEquals(MigrationRun.State.ROLLING_BACK, store.runs.get(id).getState());
        assertRefused("nothing-paused", service.resume());
        assertEquals(MigrationRun.State.ROLLED_BACK, rollBackAgain(id));
    }

    @Test
    void aBalanceThatChangesDuringEarlierBatchesIsLeftAlone() {
        UUID late = new UUID(1L, 55L);
        for (int player = 0; player < 60; player++) {
            source(new UUID(1L, player), "1");
        }
        long id = plan(MigrationMode.OVERWRITE);
        beforeBooking = request -> store.balances.put(late, new BigDecimal("99"));

        service.start(id).join();

        assertSame("99", balance(late));
        assertEquals(1, store.runs.get(id).getFailed());
        assertEquals(59, store.runs.get(id).getImported());
    }

    @Test
    void aChangeInTheLastMomentIsNotOverwritten() {
        source(ALEX, "10");
        long id = plan(MigrationMode.OVERWRITE);
        beforeBooking = request -> store.balances.put(ALEX, new BigDecimal("3"));

        service.start(id).join();

        assertSame("3", balance(ALEX));
        assertEquals(1, store.runs.get(id).getFailed());
        assertEquals(0, bookings.get());
    }

    @Test
    void aBookingThatCommitsButReportsAnErrorPausesAndIsNotBookedTwice() {
        source(ALEX, "10");
        long id = plan(MigrationMode.ADD);
        failsAfterCommit = true;

        service.start(id).join();

        assertEquals(MigrationRun.State.PAUSED, store.runs.get(id).getState());

        service.resume().join();

        assertSame("10", balance(ALEX));
        assertEquals(1, bookings.get());
        assertEquals(1, store.runs.get(id).getImported());
        assertEquals(MigrationRun.State.DONE, store.runs.get(id).getState());
    }

    @Test
    void aRollbackThatCommitsButReportsAnErrorCountsAsRestoredWhenRepeated() {
        source(ALEX, "10");
        long id = imported(MigrationMode.ADD);
        failsAfterCommit = true;

        assertThrows(CompletionException.class, () -> service.rollback(id).join());
        var repeated = service.rollback(id).join();

        assertEquals(1, repeated.restored());
        assertSame("0", balance(ALEX));
        assertEquals(MigrationEntry.State.ROLLED_BACK, store.entries.getFirst().getState());
    }

    @Test
    void aFailedResumeDoesNotBlockLaterSteps() {
        source(ALEX, "10");
        long id = plan(MigrationMode.ADD);
        store.progress(id, MigrationRun.State.PAUSED, 0, 0);
        store.progressFails = true;

        assertThrows(CompletionException.class, () -> service.resume().join());
        store.progressFails = false;

        assertEquals(false, service.pause());
    }

    @Test
    void aPauseWhileARunIsBeingPreparedIsKept() {
        source(ALEX, "10");
        long id = plan(MigrationMode.ADD);
        store.afterEntriesSaved = service::pause;

        service.start(id).join();

        assertEquals(MigrationRun.State.PAUSED, store.runs.get(id).getState());
        assertEquals(0, bookings.get());
    }

    private MigrationRun.State rollBackAgain(long id) {
        service.rollback(id).join();
        return store.runs.get(id).getState();
    }

    @Test
    void anOldPlanIsRefused() {
        source(ALEX, "10");
        long id = plan(MigrationMode.ADD);
        doReturn(LocalDateTime.now(MigrationRun.ZONE).minus(MigrationService.PLAN_VALID_FOR).minusMinutes(1L))
                .when(store.runs.get(id)).getPlannedAt();

        assertRefused("plan-stale", service.start(id));
    }

    @Test
    void anUnknownSourceIsRefused() {
        assertRefused("source-unknown", service.plan("nothing", COINS, MigrationMode.ADD));
    }

    @Test
    void aRefusedStartDoesNotBlockTheNextOne() {
        assertRefused("plan-unknown", service.start(404L));
        source(ALEX, "10");

        service.start(plan(MigrationMode.ADD)).join();

        assertSame("10", balance(ALEX));
    }

    private static final class MemoryStore implements MigrationStore {

        private final Map<Long, MigrationRun> runs = new HashMap<>();
        private final List<MigrationEntry> entries = new ArrayList<>();
        private final Map<UUID, BigDecimal> balances = new HashMap<>();
        private final Set<UUID> history = new HashSet<>();
        private Runnable afterEntriesSaved = () -> { };
        private boolean saveFails;
        private boolean progressFails;
        private long nextId = 1L;

        @Override
        public @NotNull CompletableFuture<MigrationRun> createRun(@NotNull MigrationRun run) {
            MigrationRun stored = spy(run);
            doReturn(nextId).when(stored).getId();
            runs.put(nextId++, stored);
            return CompletableFuture.completedFuture(stored);
        }

        @Override
        public @NotNull CompletableFuture<Optional<MigrationRun>> run(long id) {
            return CompletableFuture.completedFuture(Optional.ofNullable(runs.get(id)));
        }

        @Override
        public @NotNull CompletableFuture<Optional<MigrationRun>> latestRun() {
            return CompletableFuture.completedFuture(Optional.ofNullable(runs.get(nextId - 1L)));
        }

        @Override
        public @NotNull CompletableFuture<List<MigrationRun>> runsIn(@NotNull MigrationRun.State state) {
            return CompletableFuture.completedFuture(
                    runs.values().stream().filter(run -> run.getState() == state).toList());
        }

        @Override
        public @NotNull CompletableFuture<Void> progress(long id, @NotNull MigrationRun.State state, int imported,
                                                         int failed) {
            if (progressFails) {
                return CompletableFuture.failedFuture(new IllegalStateException("database down"));
            }
            runs.get(id).progress(state, imported, failed);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public @NotNull CompletableFuture<List<MigrationEntry>> entries(@NotNull String sourceId,
                                                                        @NotNull String currencyId) {
            return CompletableFuture.completedFuture(List.copyOf(entries));
        }

        @Override
        public @NotNull CompletableFuture<List<MigrationEntry>> entries(long runId,
                                                                        @NotNull MigrationEntry.State state) {
            return CompletableFuture.completedFuture(entries.stream()
                    .filter(entry -> entry.getRunId() == runId && entry.getState() == state).toList());
        }

        @Override
        public @NotNull CompletableFuture<Void> save(@NotNull Collection<MigrationEntry> rows) {
            if (saveFails) {
                return CompletableFuture.failedFuture(new IllegalStateException("database down"));
            }
            boolean firstSave = rows.stream().noneMatch(entries::contains);
            rows.stream().filter(row -> !entries.contains(row)).forEach(entries::add);
            if (firstSave) {
                afterEntriesSaved.run();
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public @NotNull CompletableFuture<Map<UUID, BigDecimal>> balances(@NotNull String currencyId) {
            return CompletableFuture.completedFuture(new HashMap<>(balances));
        }

        @Override
        public @NotNull CompletableFuture<Map<UUID, BigDecimal>> balances(@NotNull String currencyId,
                                                                          @NotNull Collection<UUID> players) {
            Map<UUID, BigDecimal> found = new HashMap<>();
            players.stream().filter(balances::containsKey).forEach(player -> found.put(player, balances.get(player)));
            return CompletableFuture.completedFuture(found);
        }

        @Override
        public @NotNull CompletableFuture<Set<UUID>> playersWithHistory(@NotNull String currencyId) {
            return CompletableFuture.completedFuture(history);
        }
    }
}
