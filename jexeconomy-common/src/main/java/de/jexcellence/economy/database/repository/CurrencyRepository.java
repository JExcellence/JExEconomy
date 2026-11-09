package de.jexcellence.economy.database.repository;

import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.database.maintenance.CurrencyPurge;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Repository for {@link Currency} entities.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class CurrencyRepository extends LoggedCrudRepository<Currency> {

    private static final String PARAM_CURRENCY = "currency";
    private static final String PARAM_IDS = "ids";

    /**
     * Creates a currency repository.
     *
     * @param executor    the executor for async operations
     * @param emf         the entity manager factory
     * @param entityClass the entity class
     */
    public CurrencyRepository(@NotNull ExecutorService executor,
                              @NotNull EntityManagerFactory emf,
                              @NotNull Class<Currency> entityClass) {
        super(executor, emf, entityClass);
    }

    /**
     * Finds a currency by its unique identifier.
     *
     * @param identifier the currency identifier
     * @return the currency, or empty
     */
    public @NotNull Optional<Currency> findByIdentifier(@NotNull String identifier) {
        return query().and("identifier", identifier).first();
    }

    /**
     * Deletes a currency with its transaction logs and accounts in one transaction (see {@link CurrencyPurge}).
     *
     * @param currencyId the currency id
     * @return the deleted row counts
     */
    public @NotNull CurrencyPurge.Result purge(long currencyId) {
        return withSession(session -> CurrencyPurge.run(new JpaPurgeStore(session.getEntityManager()), currencyId,
                CurrencyPurge.BATCH_SIZE));
    }

    /**
     * Asynchronous {@link #purge(long)} on the repository executor.
     *
     * @param currencyId the currency id
     * @return future resolving to the deleted row counts
     */
    public @NotNull CompletableFuture<CurrencyPurge.Result> purgeAsync(long currencyId) {
        return async(() -> purge(currencyId));
    }

    private record JpaPurgeStore(@NotNull EntityManager entityManager) implements CurrencyPurge.Store {

        @Override
        public @NotNull List<Long> logIds(long currencyId, int limit) {
            return entityManager.createQuery(
                            "SELECT t.id FROM TransactionLog t WHERE t.currency.id = :currency ORDER BY t.id", Long.class)
                    .setParameter(PARAM_CURRENCY, currencyId)
                    .setMaxResults(limit)
                    .getResultList();
        }

        @Override
        public void deleteLogs(@NotNull List<Long> ids) {
            entityManager.createQuery("DELETE FROM TransactionLog t WHERE t.id IN :ids")
                    .setParameter(PARAM_IDS, ids)
                    .executeUpdate();
        }

        @Override
        public @NotNull List<Long> accountIds(long currencyId, int limit) {
            return entityManager.createQuery(
                            "SELECT a.id FROM Account a WHERE a.currency.id = :currency ORDER BY a.id", Long.class)
                    .setParameter(PARAM_CURRENCY, currencyId)
                    .setMaxResults(limit)
                    .getResultList();
        }

        @Override
        public void deleteAccounts(@NotNull List<Long> ids) {
            entityManager.createQuery("DELETE FROM Account a WHERE a.id IN :ids")
                    .setParameter(PARAM_IDS, ids)
                    .executeUpdate();
        }

        @Override
        public void deleteCurrency(long currencyId) {
            var currency = entityManager.find(Currency.class, currencyId);
            if (currency != null) {
                entityManager.remove(currency);
            }
        }
    }
}
