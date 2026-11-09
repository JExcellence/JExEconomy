package de.jexcellence.economy.database.repository;

import de.jexcellence.economy.database.entity.MigrationEntry;
import jakarta.persistence.EntityManagerFactory;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Repository for {@link MigrationEntry} rows.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public class MigrationEntryRepository extends LoggedCrudRepository<MigrationEntry> {

    /**
     * Creates the repository.
     *
     * @param executor    the executor for async operations
     * @param emf         the entity manager factory
     * @param entityClass the entity class
     */
    public MigrationEntryRepository(@NotNull ExecutorService executor, @NotNull EntityManagerFactory emf,
                                    @NotNull Class<MigrationEntry> entityClass) {
        super(executor, emf, entityClass);
    }

    /**
     * The entries a source already has for a currency.
     *
     * @param sourceId   the source
     * @param currencyId the currency
     * @return future resolving to the rows
     */
    public @NotNull CompletableFuture<List<MigrationEntry>> findBySourceAsync(@NotNull String sourceId,
                                                                              @NotNull String currencyId) {
        return async(() -> query().and("sourceId", sourceId).and("currencyId", currencyId).list());
    }

    /**
     * The entries of a run in a state.
     *
     * @param runId the run
     * @param state the state
     * @return future resolving to the rows
     */
    public @NotNull CompletableFuture<List<MigrationEntry>> findByRunAsync(long runId,
                                                                           @NotNull MigrationEntry.State state) {
        return async(() -> query().and("runId", runId).and("state", state).list());
    }

    /**
     * Stores new and changed rows in one transaction.
     *
     * @param entries the rows
     * @return future that completes when stored
     */
    public @NotNull CompletableFuture<Void> storeAsync(@NotNull Collection<MigrationEntry> entries) {
        return async(() -> {
            withSessionVoid(session -> {
                var manager = session.getEntityManager();
                for (MigrationEntry entry : entries) {
                    if (entry.getId() == null) {
                        manager.persist(entry);
                    } else {
                        manager.merge(entry);
                    }
                }
            });
            return null;
        });
    }
}
