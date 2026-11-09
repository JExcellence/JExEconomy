package de.jexcellence.economy.migration;

import de.jexcellence.economy.database.entity.MigrationEntry;
import de.jexcellence.economy.database.entity.MigrationRun;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The data an import reads and writes.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public interface MigrationStore {

    /**
     * Stores a new run.
     *
     * @param run the run
     * @return future resolving to the stored run, with its id
     */
    @NotNull CompletableFuture<MigrationRun> createRun(@NotNull MigrationRun run);

    /**
     * Finds a run.
     *
     * @param id the run
     * @return future resolving to the run, empty when none exists
     */
    @NotNull CompletableFuture<Optional<MigrationRun>> run(long id);

    /**
     * The newest run.
     *
     * @return future resolving to the run, empty when none exists
     */
    @NotNull CompletableFuture<Optional<MigrationRun>> latestRun();

    /**
     * The runs in a state, newest first.
     *
     * @param state the state
     * @return future resolving to the runs
     */
    @NotNull CompletableFuture<List<MigrationRun>> runsIn(@NotNull MigrationRun.State state);

    /**
     * Stores a run's progress.
     *
     * @param id       the run
     * @param state    the state
     * @param imported the entries imported so far
     * @param failed   the entries that failed so far
     * @return future that completes when stored
     */
    @NotNull CompletableFuture<Void> progress(long id, @NotNull MigrationRun.State state, int imported, int failed);

    /**
     * The entries a source already has for a currency.
     *
     * @param sourceId   the source
     * @param currencyId the currency
     * @return future resolving to the rows
     */
    @NotNull CompletableFuture<List<MigrationEntry>> entries(@NotNull String sourceId, @NotNull String currencyId);

    /**
     * The entries of a run in a state.
     *
     * @param runId the run
     * @param state the state
     * @return future resolving to the rows
     */
    @NotNull CompletableFuture<List<MigrationEntry>> entries(long runId, @NotNull MigrationEntry.State state);

    /**
     * Stores new and changed entries in one transaction.
     *
     * @param entries the rows
     * @return future that completes when stored
     */
    @NotNull CompletableFuture<Void> save(@NotNull Collection<MigrationEntry> entries);

    /**
     * The current balances of a currency; a player without a positive balance is missing.
     *
     * @param currencyId the currency
     * @return future resolving to the balances
     */
    @NotNull CompletableFuture<Map<UUID, BigDecimal>> balances(@NotNull String currencyId);

    /**
     * The current balances of some players, as {@link #balances(String)} reports them.
     *
     * @param currencyId the currency
     * @param players    the players
     * @return future resolving to the balances
     */
    @NotNull CompletableFuture<Map<UUID, BigDecimal>> balances(@NotNull String currencyId,
                                                               @NotNull Collection<UUID> players);

    /**
     * The players with a transaction history in a currency.
     *
     * @param currencyId the currency
     * @return future resolving to the players
     */
    @NotNull CompletableFuture<Set<UUID>> playersWithHistory(@NotNull String currencyId);
}
