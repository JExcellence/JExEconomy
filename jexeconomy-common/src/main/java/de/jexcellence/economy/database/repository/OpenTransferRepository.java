package de.jexcellence.economy.database.repository;

import de.jexcellence.economy.database.entity.OpenTransfer;
import jakarta.persistence.EntityManagerFactory;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Repository for {@link OpenTransfer} rows.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public class OpenTransferRepository extends LoggedCrudRepository<OpenTransfer> {

    private static final int MAX_REPORTED_AT_ONCE = 500;

    /**
     * Creates the repository.
     *
     * @param executor    the executor for async operations
     * @param emf         the entity manager factory
     * @param entityClass the entity class
     */
    public OpenTransferRepository(@NotNull ExecutorService executor, @NotNull EntityManagerFactory emf,
                                  @NotNull Class<OpenTransfer> entityClass) {
        super(executor, emf, entityClass);
    }

    /**
     * The payments opened before a moment that were never closed or reported.
     *
     * @param before only rows opened earlier count
     * @return future resolving to the rows, oldest first
     */
    public @NotNull CompletableFuture<List<OpenTransfer>> findUnreportedAsync(@NotNull LocalDateTime before) {
        return async(() -> withSession(session -> session.getEntityManager()
                .createQuery("select open from OpenTransfer open where open.reportedAt is null"
                        + " and open.openedAt < :before order by open.openedAt", OpenTransfer.class)
                .setParameter("before", before)
                .setMaxResults(MAX_REPORTED_AT_ONCE)
                .getResultList()));
    }
}
