package de.jexcellence.economy.database.repository;

import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.database.entity.TransactionLog;
import jakarta.persistence.EntityManagerFactory;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Repository for {@link TransactionLog} entities. Queries that feed views fetch the currency with the log row; all
 * asynchronous work runs on the repository executor.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class TransactionLogRepository extends LoggedCrudRepository<TransactionLog> {

    private static final String FIELD_CURRENCY = "currency";
    private static final String FIELD_PLAYER_UUID = "playerUuid";
    private static final String FIELD_TIMESTAMP = "timestamp";
    private static final String PARAM_UUID = "uuid";

    /**
     * Creates a transaction log repository.
     *
     * @param executor    the executor for async operations
     * @param emf         the entity manager factory
     * @param entityClass the entity class
     */
    public TransactionLogRepository(@NotNull ExecutorService executor,
                                    @NotNull EntityManagerFactory emf,
                                    @NotNull Class<TransactionLog> entityClass) {
        super(executor, emf, entityClass);
    }

    /**
     * The players with at least one log entry in a currency.
     *
     * @param currency the currency
     * @return future resolving to the players
     */
    public @NotNull CompletableFuture<Set<UUID>> findPlayersWithHistoryAsync(@NotNull Currency currency) {
        return async(() -> Set.copyOf(withReadOnly(session -> session.getEntityManager()
                .createQuery("SELECT DISTINCT t.playerUuid FROM TransactionLog t "
                        + "WHERE t.currency = :currency AND t.playerUuid IS NOT NULL", UUID.class)
                .setParameter(FIELD_CURRENCY, currency)
                .getResultList())));
    }

    /**
     * Finds one log entry with its currency.
     *
     * @param id the log id
     * @return future resolving to the entry, or empty
     */
    public @NotNull CompletableFuture<Optional<TransactionLog>> findByIdWithCurrencyAsync(long id) {
        return async(() -> query().fetchLeft(FIELD_CURRENCY).and("id", id).first());
    }

    /**
     * Finds recent transaction logs for a player asynchronously.
     *
     * @param playerUuid the player's UUID
     * @param limit      maximum results
     * @return future resolving to ordered list (newest first)
     */
    public @NotNull CompletableFuture<List<TransactionLog>> findByPlayerUuidAsync(@NotNull UUID playerUuid,
                                                                                  int limit) {
        return async(() -> query()
                .fetchLeft(FIELD_CURRENCY)
                .and(FIELD_PLAYER_UUID, playerUuid)
                .orderByDesc(FIELD_TIMESTAMP)
                .limit(limit)
                .list());
    }

    /**
     * Counts all transaction logs for a player, for the page count of the log view.
     *
     * @param playerUuid the player's UUID
     * @return future resolving to the total number of log rows
     */
    public @NotNull CompletableFuture<Long> countByPlayerUuidAsync(@NotNull UUID playerUuid) {
        return async(() -> withReadOnly(ctx -> ctx.getEntityManager()
                .createQuery("SELECT COUNT(t) FROM TransactionLog t WHERE t.playerUuid = :uuid", Long.class)
                .setParameter(PARAM_UUID, playerUuid)
                .getSingleResult()));
    }

    /**
     * Fetches one page of a player's transaction logs (newest first) with their currencies.
     *
     * @param playerUuid the player's UUID
     * @param offset     rows to skip (page index x page size)
     * @param limit      page size
     * @return future resolving to the page's ordered rows
     */
    public @NotNull CompletableFuture<List<TransactionLog>> findByPlayerUuidPageAsync(@NotNull UUID playerUuid,
                                                                                      int offset, int limit) {
        return async(() -> withReadOnly(ctx -> ctx.getEntityManager()
                .createQuery("SELECT t FROM TransactionLog t LEFT JOIN FETCH t.currency "
                        + "WHERE t.playerUuid = :uuid ORDER BY t.timestamp DESC", TransactionLog.class)
                .setParameter(PARAM_UUID, playerUuid)
                .setFirstResult(Math.max(0, offset))
                .setMaxResults(limit)
                .getResultList()));
    }

    /**
     * Finds recent transaction logs whose cached {@code playerName} matches case-insensitively, for admin views
     * where the UUID is unknown.
     *
     * @param playerName the player's current Minecraft name
     * @param limit      maximum results
     * @return future resolving to ordered list
     */
    public @NotNull CompletableFuture<List<TransactionLog>> findByPlayerNameAsync(@NotNull String playerName,
                                                                                  int limit) {
        return async(() -> withReadOnly(ctx -> ctx.getEntityManager()
                .createQuery("SELECT t FROM TransactionLog t LEFT JOIN FETCH t.currency "
                        + "WHERE lower(t.playerName) = lower(:name) ORDER BY t.timestamp DESC", TransactionLog.class)
                .setParameter("name", playerName)
                .setMaxResults(limit)
                .getResultList()));
    }

    /**
     * Finds recent transaction logs for a currency asynchronously.
     *
     * @param currencyId the currency database ID
     * @param limit      maximum results
     * @return future resolving to ordered list
     */
    public @NotNull CompletableFuture<List<TransactionLog>> findByCurrencyAsync(long currencyId, int limit) {
        return async(() -> query()
                .fetchLeft(FIELD_CURRENCY)
                .and("currency.id", currencyId)
                .orderByDesc(FIELD_TIMESTAMP)
                .limit(limit)
                .list());
    }

    /**
     * Deletes all transaction logs strictly older than the cutoff with one JPQL {@code DELETE}. Callers clamp the
     * cutoff to a safe retention window.
     *
     * @param cutoff any timestamp earlier than this is purged
     * @return future resolving to the number of deleted rows
     */
    public @NotNull CompletableFuture<Integer> deleteOlderThanAsync(@NotNull Instant cutoff) {
        return async(() -> withSession(ctx -> ctx.getEntityManager()
                .createQuery("DELETE FROM TransactionLog t WHERE t.timestamp < :cutoff")
                .setParameter("cutoff", cutoff)
                .executeUpdate()));
    }
}
