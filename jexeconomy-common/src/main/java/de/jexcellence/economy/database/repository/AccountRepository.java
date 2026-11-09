package de.jexcellence.economy.database.repository;

import de.jexcellence.economy.database.PersistenceFailures;
import de.jexcellence.economy.database.entity.Account;
import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.database.entity.EconomyPlayer;
import de.jexcellence.economy.database.maintenance.AccountMergePlan;
import de.jexcellence.economy.money.Money;
import de.jexcellence.jehibernate.repository.query.QueryBuilder;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Repository for {@link Account} entities. Every query that hands accounts to callers fetches owner and currency in
 * the same statement, so callers may read them after the session closed.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class AccountRepository extends LoggedCrudRepository<Account> {

    private static final String HOLDERS_QUERY = "SELECT p.uniqueId, p.playerName, a.balance FROM Account a "
            + "JOIN a.player p WHERE a.currency = :currency AND a.balance > 0";
    private static final String FIELD_PLAYER = "player";
    private static final String FIELD_CURRENCY = "currency";
    private static final String FIELD_BALANCE = "balance";
    private static final String PARAM_CURRENCY = "currency";

    /**
     * An account owner with a positive balance.
     *
     * @param playerUuid the owner
     * @param playerName the owner's last known name
     * @param balance    the balance
     */
    public record Holder(@NotNull UUID playerUuid, @NotNull String playerName, @NotNull BigDecimal balance) {
    }

    /**
     * Creates an account repository.
     *
     * @param executor    the executor for async operations
     * @param emf         the entity manager factory
     * @param entityClass the entity class
     */
    public AccountRepository(@NotNull ExecutorService executor,
                             @NotNull EntityManagerFactory emf,
                             @NotNull Class<Account> entityClass) {
        super(executor, emf, entityClass);
    }

    private @NotNull QueryBuilder<Account> fetched() {
        return query().fetch(FIELD_PLAYER).fetch(FIELD_CURRENCY);
    }

    /**
     * Finds an account by player and currency.
     *
     * @param player   the account owner
     * @param currency the currency
     * @return the account, or empty
     */
    public @NotNull Optional<Account> findByPlayerAndCurrency(@NotNull EconomyPlayer player,
                                                              @NotNull Currency currency) {
        return fetched().and(FIELD_PLAYER, player).and(FIELD_CURRENCY, currency).first();
    }

    /**
     * Finds an account by player and currency asynchronously.
     *
     * @param player   the account owner
     * @param currency the currency
     * @return future resolving to the account, or empty
     */
    public @NotNull CompletableFuture<Optional<Account>> findByPlayerAndCurrencyAsync(@NotNull EconomyPlayer player,
                                                                                     @NotNull Currency currency) {
        return async(() -> findByPlayerAndCurrency(player, currency));
    }

    /**
     * Finds the account of a player for a currency and creates it when missing. A concurrent creation of the same
     * account (the unique constraint on owner and currency) is answered with the row the other writer created.
     *
     * @param player   the account owner
     * @param currency the currency
     * @return the account
     */
    public @NotNull Account findOrCreate(@NotNull EconomyPlayer player, @NotNull Currency currency) {
        var existing = findByPlayerAndCurrency(player, currency);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            create(new Account(player, currency));
        } catch (RuntimeException failure) {
            if (!PersistenceFailures.isUniqueViolation(failure)) {
                throw failure;
            }
        }
        return findByPlayerAndCurrency(player, currency)
                .orElseThrow(() -> new IllegalStateException("Account vanished after creation: " + player.getId()));
    }

    /**
     * Asynchronous {@link #findOrCreate(EconomyPlayer, Currency)} on the repository executor.
     *
     * @param player   the account owner
     * @param currency the currency
     * @return future resolving to the account
     */
    public @NotNull CompletableFuture<Account> findOrCreateAsync(@NotNull EconomyPlayer player,
                                                                 @NotNull Currency currency) {
        return async(() -> findOrCreate(player, currency));
    }

    /**
     * Writes a new balance when the row still has the version the caller read. Hibernate checks the version again
     * on flush, so a writer on another server is detected as well.
     *
     * @param accountId       the account id
     * @param expectedVersion the version the caller read
     * @param balance         the new balance
     * @throws OptimisticLockException when the row changed since it was read
     */
    public void writeBalance(long accountId, int expectedVersion, @NotNull BigDecimal balance) {
        writeBalances(List.of(new VersionedBalance(accountId, expectedVersion, balance)));
    }

    /**
     * Writes several balances in one transaction: all are stored or none. Rows are updated in account id order, so
     * two transactions touching the same accounts cannot deadlock in the database.
     *
     * @param balances the new balances with the versions they were computed from
     * @throws OptimisticLockException when a row changed since it was read
     */
    public void writeBalances(@NotNull List<VersionedBalance> balances) {
        List<VersionedBalance> ordered = balances.stream()
                .sorted(Comparator.comparingLong(VersionedBalance::accountId))
                .toList();
        withSessionVoid(session -> {
            var entityManager = session.getEntityManager();
            for (VersionedBalance balance : ordered) {
                var account = entityManager.find(Account.class, balance.accountId());
                if (account == null) {
                    throw new IllegalStateException("Account #" + balance.accountId() + " does not exist");
                }
                if (account.getVersion() != balance.expectedVersion()) {
                    throw new OptimisticLockException("Account #" + balance.accountId() + " changed since it was read");
                }
                account.setBalance(balance.balance());
            }
        });
    }

    /**
     * A new balance and the row version it was computed from.
     *
     * @param accountId       the account id
     * @param expectedVersion the version the caller read
     * @param balance         the new balance
     */
    public record VersionedBalance(long accountId, int expectedVersion, @NotNull BigDecimal balance) {
    }

    /**
     * Finds all accounts for a player asynchronously.
     *
     * @param player the account owner
     * @return future resolving to the list of accounts
     */
    public @NotNull CompletableFuture<List<Account>> findByPlayerAsync(@NotNull EconomyPlayer player) {
        return async(() -> fetched().and(FIELD_PLAYER, player).list());
    }

    /**
     * Finds top accounts by balance for a currency asynchronously.
     *
     * @param currency the currency
     * @param limit    maximum number of results
     * @return future resolving to ordered list of accounts (highest balance first)
     */
    public @NotNull CompletableFuture<List<Account>> findTopByCurrencyAsync(@NotNull Currency currency, int limit) {
        return async(() -> fetched().and(FIELD_CURRENCY, currency).orderByDesc(FIELD_BALANCE).limit(limit).list());
    }

    /**
     * Every owner with a positive balance of a currency, without loading the account entities.
     *
     * @param currency the currency
     * @return future resolving to the holders
     */
    public @NotNull CompletableFuture<List<Holder>> findHoldersAsync(@NotNull Currency currency) {
        return async(() -> withReadOnly(session -> toHolders(session.getEntityManager()
                .createQuery(HOLDERS_QUERY, Object[].class)
                .setParameter(PARAM_CURRENCY, currency)
                .getResultList())));
    }

    /**
     * The owners among the given players with a positive balance of a currency.
     *
     * @param currency the currency
     * @param players  the players
     * @return future resolving to the holders
     */
    public @NotNull CompletableFuture<List<Holder>> findHoldersAsync(@NotNull Currency currency,
                                                                     @NotNull Collection<UUID> players) {
        return async(() -> withReadOnly(session -> toHolders(session.getEntityManager()
                .createQuery(HOLDERS_QUERY + " AND p.uniqueId IN :players", Object[].class)
                .setParameter(PARAM_CURRENCY, currency)
                .setParameter("players", players)
                .getResultList())));
    }

    private static @NotNull List<Holder> toHolders(@NotNull List<Object[]> rows) {
        return rows.stream()
                .map(row -> new Holder((UUID) row[0], (String) row[1], Money.of((BigDecimal) row[2])))
                .toList();
    }

    /**
     * Owner and currency ids that have more than one account.
     *
     * @return the duplicate keys, each as {@code [playerId, currencyId]}
     */
    public @NotNull List<long[]> findDuplicateKeys() {
        return withReadOnly(session -> session.getEntityManager()
                .createQuery("SELECT a.player.id, a.currency.id FROM Account a "
                        + "GROUP BY a.player.id, a.currency.id HAVING COUNT(a) > 1", Object[].class)
                .getResultList()
                .stream()
                .map(row -> new long[]{((Number) row[0]).longValue(), ((Number) row[1]).longValue()})
                .toList());
    }

    /**
     * Merges the duplicate accounts of one owner and currency into the oldest row in one transaction.
     *
     * @param playerId   the owner id
     * @param currencyId the currency id
     * @return the applied plan, empty when no duplicates are left
     */
    public @NotNull Optional<AccountMergePlan> mergeDuplicates(long playerId, long currencyId) {
        return withSession(session -> {
            var entityManager = session.getEntityManager();
            List<Account> rows = entityManager.createQuery("SELECT a FROM Account a "
                            + "WHERE a.player.id = :player AND a.currency.id = :currency ORDER BY a.id", Account.class)
                    .setParameter(FIELD_PLAYER, playerId)
                    .setParameter(PARAM_CURRENCY, currencyId)
                    .getResultList();
            if (rows.size() < 2) {
                return Optional.<AccountMergePlan>empty();
            }
            var plan = AccountMergePlan.of(rows.stream()
                    .map(row -> new AccountMergePlan.Row(row.getId(), row.getBalance()))
                    .toList());
            for (Account row : rows) {
                if (row.getId() == plan.keeperId()) {
                    row.setBalance(plan.total());
                } else {
                    entityManager.remove(row);
                }
            }
            return Optional.of(plan);
        });
    }

    /**
     * Counts accounts for a currency.
     *
     * @param currency the currency
     * @return the number of accounts
     */
    public long countByCurrency(@NotNull Currency currency) {
        return query().and(FIELD_CURRENCY, currency).count();
    }

    /**
     * Sums all balances of a currency in the database.
     *
     * @param currency the currency
     * @return the total, zero without accounts
     */
    public @NotNull BigDecimal sumByCurrency(@NotNull Currency currency) {
        return withReadOnly(session -> Money.of(session.getEntityManager()
                .createQuery("SELECT SUM(a.balance) FROM Account a WHERE a.currency = :currency", BigDecimal.class)
                .setParameter(PARAM_CURRENCY, currency)
                .getSingleResult()));
    }
}
