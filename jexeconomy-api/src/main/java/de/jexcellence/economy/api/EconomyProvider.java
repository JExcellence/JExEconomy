package de.jexcellence.economy.api;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Public API for the JExEconomy multi-currency economy system.
 *
 * <p>Third-party plugins should depend on this interface rather than
 * the internal {@code EconomyService}. Retrieve via Bukkit's
 * {@link org.bukkit.plugin.ServicesManager} or the convenience
 * accessor {@link JExEconomyAPI#get()}.
 *
 * <p>All mutating operations are asynchronous and return
 * {@link CompletableFuture}s. Never call {@code .join()} on the
 * main thread - use {@code .thenAccept()} or similar instead.
 *
 * <h2>Example usage:</h2>
 * <pre>{@code
 * EconomyProvider api = JExEconomyAPI.get();
 *
 * // Check balance
 * api.getBalance(player, "coins").thenAccept(balance ->
 *     player.sendMessage("You have " + balance + " coins"));
 *
 * // Deposit
 * api.deposit(player, "coins", 100.0, null, "quest reward")
 *     .thenAccept(result -> {
 *         if (result.isSuccess()) {
 *             player.sendMessage("Received 100 coins!");
 *         }
 *     });
 * }</pre>
 *
 * @author JExcellence
 * @since 3.0.0
 */
public interface EconomyProvider {

    /**
     * Returns all registered currencies, keyed by identifier.
     *
     * @return unmodifiable map of currency identifier → snapshot
     */
    @NotNull Map<String, CurrencySnapshot> getCurrencies();

    /**
     * Looks up a currency by its unique identifier.
     *
     * @param identifier the currency identifier (e.g. "coins")
     * @return the currency snapshot, or empty if not found
     */
    @NotNull Optional<CurrencySnapshot> getCurrency(@NotNull String identifier);

    /**
     * Returns the behaviour flags of a currency.
     *
     * @param identifier the currency identifier
     * @return the policy, {@link CurrencyPolicy#DEFAULT} for unknown or unflagged currencies
     */
    default @NotNull CurrencyPolicy getPolicy(@NotNull String identifier) {
        return CurrencyPolicy.DEFAULT;
    }

    /**
     * Ensures a currency exists and applies its policy. Idempotent: an existing currency keeps
     * its operator-edited symbol, prefix, suffix and icon; only the policy is (re)applied.
     *
     * @param definition the currency definition
     * @return future resolving to {@code true} once the currency exists with the policy applied
     */
    default @NotNull CompletableFuture<Boolean> registerCurrency(@NotNull CurrencyDefinition definition) {
        return CompletableFuture.completedFuture(false);
    }

    /**
     * Sets every account of a resettable currency to zero. Each account is withdrawn and logged
     * individually, then a {@link de.jexcellence.economy.api.event.CurrencyResetEvent} fires.
     *
     * @param identifier the currency identifier
     * @param reason     human-readable reason stored in the transaction log
     * @return future resolving to the number of accounts that held a balance, or {@code -1}
     *         when the currency is unknown or not resettable
     */
    default @NotNull CompletableFuture<Integer> resetCurrency(@NotNull String identifier,
                                                              @Nullable String reason) {
        return CompletableFuture.completedFuture(-1);
    }

    /**
     * Retrieves the current balance for a player and currency.
     *
     * @param player     the player
     * @param currencyId the currency identifier
     * @return future resolving to the balance, or {@code 0.0} if no account exists
     */
    @NotNull CompletableFuture<Double> getBalance(@NotNull OfflinePlayer player,
                                                  @NotNull String currencyId);

    /**
     * Checks whether a player has an account for the given currency.
     *
     * @param player     the player
     * @param currencyId the currency identifier
     * @return future resolving to {@code true} if an account exists
     */
    @NotNull CompletableFuture<Boolean> hasAccount(@NotNull OfflinePlayer player,
                                                   @NotNull String currencyId);

    /**
     * Checks whether a player has at least the specified amount.
     *
     * @param player     the player
     * @param currencyId the currency identifier
     * @param amount     the amount to check
     * @return future resolving to {@code true} if the balance is sufficient
     */
    @NotNull CompletableFuture<Boolean> has(@NotNull OfflinePlayer player,
                                            @NotNull String currencyId,
                                            double amount);

    /**
     * Deposits an amount into a player's account.
     *
     * @param player     the target player
     * @param currencyId the currency identifier
     * @param amount     the amount to deposit (must be positive)
     * @param initiator  the player who initiated the deposit, or {@code null} for server-initiated
     * @param reason     optional human-readable reason
     * @return future resolving to the transaction result
     */
    @NotNull CompletableFuture<TransactionResult> deposit(@NotNull OfflinePlayer player,
                                                          @NotNull String currencyId,
                                                          double amount,
                                                          @Nullable Player initiator,
                                                          @Nullable String reason);

    /**
     * Deposits an amount with an explicit {@link ChangeType} (e.g. {@code SHOP_SELL}
     * instead of the default {@code DEPOSIT}).
     */
    default @NotNull CompletableFuture<TransactionResult> deposit(@NotNull OfflinePlayer player,
                                                                   @NotNull String currencyId,
                                                                   double amount,
                                                                   @Nullable Player initiator,
                                                                   @Nullable String reason,
                                                                   @NotNull ChangeType changeType) {
        return deposit(player, currencyId, amount, initiator, reason);
    }

    /**
     * Withdraws an amount from a player's account.
     *
     * @param player     the target player
     * @param currencyId the currency identifier
     * @param amount     the amount to withdraw (must be positive)
     * @param initiator  the player who initiated the withdrawal, or {@code null} for server-initiated
     * @param reason     optional human-readable reason
     * @return future resolving to the transaction result
     */
    @NotNull CompletableFuture<TransactionResult> withdraw(@NotNull OfflinePlayer player,
                                                           @NotNull String currencyId,
                                                           double amount,
                                                           @Nullable Player initiator,
                                                           @Nullable String reason);

    /**
     * Deposits the same amount into many players' accounts in one call - for a
     * server-wide gift, compensation, or event payout. Each deposit is processed
     * and logged individually through {@link #deposit}; the returned future
     * completes once all have settled.
     *
     * <p>Default implementation fans out to {@link #deposit} per player and
     * gathers the results; implementations may override for batch efficiency.</p>
     *
     * <p>A deposit that completes exceptionally becomes a failed result in its slot, so the returned future always
     * completes normally and every other player's result stays visible; callers count successes per slot.</p>
     *
     * @param players    the target players
     * @param currencyId the currency identifier
     * @param amount     the amount to deposit into each account (must be positive)
     * @param initiator  the player who initiated the grant, or {@code null} for server-initiated
     * @param reason     optional human-readable reason
     * @return future resolving to the per-player results, in iteration order
     */
    default @NotNull CompletableFuture<List<TransactionResult>> depositAll(
            @NotNull Collection<? extends OfflinePlayer> players,
            @NotNull String currencyId,
            double amount,
            @Nullable Player initiator,
            @Nullable String reason) {
        final List<CompletableFuture<TransactionResult>> futures = new ArrayList<>();
        for (OfflinePlayer player : players) {
            futures.add(deposit(player, currencyId, amount, initiator, reason)
                    .exceptionally(failure -> TransactionResult.failure(amount, 0.0,
                            "Storage error: " + failure.getMessage())));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(ignored -> futures.stream().map(CompletableFuture::join).collect(Collectors.toList()));
    }

    /**
     * Transfers an amount from one player to another.
     *
     * <p>If the withdrawal succeeds but the deposit fails, the withdrawn
     * amount is automatically rolled back to the sender.
     *
     * @param sender     the sending player
     * @param recipient  the receiving player
     * @param currencyId the currency identifier
     * @param amount     the amount to transfer (must be positive)
     * @return future resolving to the transaction result
     */
    @NotNull CompletableFuture<TransactionResult> transfer(@NotNull Player sender,
                                                           @NotNull OfflinePlayer recipient,
                                                           @NotNull String currencyId,
                                                           double amount);

    /**
     * The exact balance of a player, stored with two decimal places. Works for players the server has never seen.
     *
     * @param player     the player's UUID
     * @param currencyId the currency identifier
     * @return future resolving to the balance, zero if no account exists
     */
    @NotNull CompletableFuture<BigDecimal> getExactBalance(@NotNull UUID player, @NotNull String currencyId);

    /**
     * Deposits an exact amount by UUID. Amounts are rounded to two decimal places.
     *
     * @param player     the target player's UUID
     * @param currencyId the currency identifier
     * @param amount     the amount to deposit (must book at least one cent)
     * @param initiator  the UUID of who initiated the deposit, or {@code null} for server-initiated
     * @param reason     optional human-readable reason
     * @return future resolving to the transaction result
     */
    @NotNull CompletableFuture<TransactionResult> deposit(@NotNull UUID player,
                                                          @NotNull String currencyId,
                                                          @NotNull BigDecimal amount,
                                                          @Nullable UUID initiator,
                                                          @Nullable String reason);

    /**
     * Withdraws an exact amount by UUID. Amounts are rounded to two decimal places.
     *
     * @param player     the target player's UUID
     * @param currencyId the currency identifier
     * @param amount     the amount to withdraw (must book at least one cent)
     * @param initiator  the UUID of who initiated the withdrawal, or {@code null} for server-initiated
     * @param reason     optional human-readable reason
     * @return future resolving to the transaction result
     */
    @NotNull CompletableFuture<TransactionResult> withdraw(@NotNull UUID player,
                                                           @NotNull String currencyId,
                                                           @NotNull BigDecimal amount,
                                                           @Nullable UUID initiator,
                                                           @Nullable String reason);

    /**
     * Returns the top accounts for a currency, ordered by balance descending.
     *
     * @param currencyId the currency identifier
     * @param limit      maximum number of results
     * @return future resolving to ordered list of account snapshots (highest balance first)
     */
    @NotNull CompletableFuture<List<AccountSnapshot>> getTopAccounts(@NotNull String currencyId,
                                                                     int limit);
}
