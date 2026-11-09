package de.jexcellence.economy.service;

import de.jexcellence.economy.database.entity.Account;
import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.database.entity.EconomyPlayer;
import de.jexcellence.economy.database.repository.AccountRepository;
import de.jexcellence.economy.database.repository.PlayerRepository;
import de.jexcellence.economy.money.Money;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

/**
 * Read access to accounts for commands, views and the API. Returned accounts have owner and currency fetched.
 * Balance changes never go through here; they belong to the ledger.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public final class AccountDirectory {

    private final PlayerRepository players;
    private final AccountRepository accounts;
    private final AtomicReference<BiFunction<UUID, String, OptionalDouble>> displayBalanceSource =
            new AtomicReference<>((player, currencyId) -> OptionalDouble.empty());
    private final AtomicReference<BiPredicate<UUID, String>> externalBalanceSource =
            new AtomicReference<>((player, currencyId) -> false);

    /**
     * Creates the directory.
     *
     * @param players  the player repository
     * @param accounts the account repository
     */
    public AccountDirectory(@NotNull PlayerRepository players, @NotNull AccountRepository accounts) {
        this.players = players;
        this.accounts = accounts;
    }

    /**
     * The account of a player for a currency.
     *
     * @param player   the player
     * @param currency the currency
     * @return future resolving to the account, or empty
     */
    public @NotNull CompletableFuture<Optional<Account>> account(@NotNull OfflinePlayer player,
                                                                 @NotNull Currency currency) {
        return players.findByUuidAsync(player.getUniqueId())
                .thenCompose(found -> found
                        .map(owner -> accounts.findByPlayerAndCurrencyAsync(owner, currency))
                        .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty())));
    }

    /**
     * Whether the economy has a row for a player.
     *
     * @param player the player
     * @return future resolving to {@code true} when the row exists
     */
    public @NotNull CompletableFuture<Boolean> isRegistered(@NotNull UUID player) {
        return players.findByUuidAsync(player).thenApply(Optional::isPresent);
    }

    /**
     * The balance of a player, zero without an account.
     *
     * @param player   the player
     * @param currency the currency
     * @return future resolving to the balance
     */
    public @NotNull CompletableFuture<Double> balance(@NotNull OfflinePlayer player, @NotNull Currency currency) {
        return account(player, currency)
                .thenApply(found -> found.map(account -> Money.toDouble(account.getBalance())).orElse(0.0));
    }

    /**
     * The balance to show a player: the balance an extension reports when a source is
     * registered, else the player's own account. Display only.
     *
     * @param player   the player
     * @param currency the currency
     * @return future resolving to the shown balance
     */
    public @NotNull CompletableFuture<Double> displayBalance(@NotNull OfflinePlayer player,
                                                             @NotNull Currency currency) {
        return balance(player, currency).thenApply(actual -> displayBalance(player.getUniqueId(), currency, actual));
    }

    /**
     * The display balance for an already known account balance.
     *
     * @param player   the player
     * @param currency the currency
     * @param actual   the account balance
     * @return the shown balance
     */
    public double displayBalance(@NotNull UUID player, @NotNull Currency currency, double actual) {
        return displayBalanceSource.get().apply(player, currency.getIdentifier()).orElse(actual);
    }

    /**
     * Registers the source of display balances for currencies held outside the player's own account.
     *
     * @param source the source
     */
    public void setDisplayBalanceSource(@NotNull BiFunction<UUID, String, OptionalDouble> source) {
        displayBalanceSource.set(source);
    }

    /**
     * Whether the display balance is held outside the wallet.
     *
     * @param player   the player
     * @param currency the currency
     * @return {@code true} for an external balance
     */
    public boolean isExternalBalance(@NotNull UUID player, @NotNull Currency currency) {
        return externalBalanceSource.get().test(player, currency.getIdentifier());
    }

    /**
     * Registers the check behind {@link #isExternalBalance(UUID, Currency)}.
     *
     * @param source the check
     */
    public void setExternalBalanceSource(@NotNull BiPredicate<UUID, String> source) {
        externalBalanceSource.set(source);
    }

    /**
     * All accounts of a player.
     *
     * @param player the player
     * @return future resolving to the accounts
     */
    public @NotNull CompletableFuture<List<Account>> accounts(@NotNull OfflinePlayer player) {
        return players.findByUuidAsync(player.getUniqueId())
                .thenCompose(found -> found
                        .map(accounts::findByPlayerAsync)
                        .orElseGet(() -> CompletableFuture.completedFuture(List.of())));
    }

    /**
     * The richest accounts of a currency.
     *
     * @param currency the currency
     * @param limit    maximum number of results
     * @return future resolving to the accounts, highest balance first
     */
    public @NotNull CompletableFuture<List<Account>> top(@NotNull Currency currency, int limit) {
        return accounts.findTopByCurrencyAsync(currency, limit);
    }

    /**
     * Finds or creates the account of an economy player.
     *
     * @param owner    the economy player
     * @param currency the currency
     * @return future resolving to the account
     */
    public @NotNull CompletableFuture<Account> getOrCreate(@NotNull EconomyPlayer owner, @NotNull Currency currency) {
        return accounts.findOrCreateAsync(owner, currency);
    }

    /**
     * Finds or creates player and account.
     *
     * @param playerUuid the player
     * @param playerName the player's name, cut to 16 characters; {@code null} when unknown keeps the stored name
     * @param currency   the currency
     * @return future resolving to the account
     */
    public @NotNull CompletableFuture<Account> getOrCreate(@NotNull UUID playerUuid, @Nullable String playerName,
                                                           @NotNull Currency currency) {
        return players.findOrCreateAsync(playerUuid, playerName)
                .thenCompose(owner -> accounts.findOrCreateAsync(owner, currency));
    }
}
