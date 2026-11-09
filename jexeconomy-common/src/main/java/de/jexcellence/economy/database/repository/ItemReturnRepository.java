package de.jexcellence.economy.database.repository;

import de.jexcellence.economy.database.entity.ItemReturn;
import jakarta.persistence.EntityManagerFactory;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Repository for {@link ItemReturn} rows.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public class ItemReturnRepository extends LoggedCrudRepository<ItemReturn> {

    /**
     * Creates the repository.
     *
     * @param executor    the executor for async operations
     * @param emf         the entity manager factory
     * @param entityClass the entity class
     */
    public ItemReturnRepository(@NotNull ExecutorService executor, @NotNull EntityManagerFactory emf,
                                @NotNull Class<ItemReturn> entityClass) {
        super(executor, emf, entityClass);
    }

    /**
     * The items waiting for a player.
     *
     * @param player the owner
     * @return future resolving to the rows
     */
    public @NotNull CompletableFuture<List<ItemReturn>> findByPlayerAsync(@NotNull UUID player) {
        return async(() -> query().and("playerUuid", player.toString()).list());
    }

    /**
     * Removes a row and reports whether this call removed it, so two servers on one database cannot both hand out
     * the same item.
     *
     * @param id the row id
     * @return future resolving to {@code true} when the caller may hand out the item
     */
    public @NotNull CompletableFuture<Boolean> claimAsync(long id) {
        return async(() -> withSession(session -> session.getEntityManager()
                .createQuery("delete from ItemReturn item where item.id = :id")
                .setParameter("id", id)
                .executeUpdate() == 1));
    }
}
