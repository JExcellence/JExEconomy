package de.jexcellence.economy.database.repository;

import de.jexcellence.economy.database.entity.MigrationRun;
import jakarta.persistence.EntityManagerFactory;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Repository for {@link MigrationRun} rows.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public class MigrationRunRepository extends LoggedCrudRepository<MigrationRun> {

    /**
     * Creates the repository.
     *
     * @param executor    the executor for async operations
     * @param emf         the entity manager factory
     * @param entityClass the entity class
     */
    public MigrationRunRepository(@NotNull ExecutorService executor, @NotNull EntityManagerFactory emf,
                                  @NotNull Class<MigrationRun> entityClass) {
        super(executor, emf, entityClass);
    }

    /**
     * Stores a run's progress on the current row.
     *
     * @param id       the run
     * @param state    the state
     * @param imported the entries imported so far
     * @param failed   the entries that failed so far
     * @return future that completes when stored
     */
    public @NotNull CompletableFuture<Void> progressAsync(long id, @NotNull MigrationRun.State state, int imported,
                                                          int failed) {
        return async(() -> {
            withSessionVoid(session -> session.getEntityManager().find(MigrationRun.class, id)
                    .progress(state, imported, failed));
            return null;
        });
    }

    /**
     * The runs in a state, newest first.
     *
     * @param state the state
     * @return future resolving to the runs
     */
    public @NotNull CompletableFuture<List<MigrationRun>> findInStateAsync(@NotNull MigrationRun.State state) {
        return async(() -> query().and("state", state).orderByDesc("id").list());
    }

    /**
     * The newest run.
     *
     * @return future resolving to the run, empty when none exists
     */
    public @NotNull CompletableFuture<Optional<MigrationRun>> findLatestAsync() {
        return async(() -> query().orderByDesc("id").first());
    }

    /**
     * Finds a run.
     *
     * @param id the run
     * @return future resolving to the run, empty when none exists
     */
    public @NotNull CompletableFuture<Optional<MigrationRun>> findByRunIdAsync(long id) {
        return async(() -> query().and("id", id).first());
    }
}
