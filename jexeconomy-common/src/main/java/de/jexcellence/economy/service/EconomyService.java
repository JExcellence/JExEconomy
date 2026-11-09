package de.jexcellence.economy.service;

import de.jexcellence.economy.api.AccountSnapshot;
import de.jexcellence.economy.api.ChangeType;
import de.jexcellence.economy.api.CurrencyDefinition;
import de.jexcellence.economy.api.CurrencyPolicy;
import de.jexcellence.economy.api.CurrencySnapshot;
import de.jexcellence.economy.api.EconomyProvider;
import de.jexcellence.economy.api.TransactionResult;
import de.jexcellence.economy.api.extension.DepositRedirect;
import de.jexcellence.economy.api.extension.TransferRoute;
import de.jexcellence.economy.database.entity.Account;
import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.database.entity.EconomyPlayer;
import de.jexcellence.economy.money.Money;
import de.jexcellence.economy.service.currency.CurrencyConfig;
import de.jexcellence.economy.service.currency.CurrencyLifecycle;
import de.jexcellence.economy.service.currency.CurrencyRegistry;
import de.jexcellence.economy.service.ledger.AccountKey;
import de.jexcellence.economy.service.ledger.BalanceLedger;
import de.jexcellence.economy.service.ledger.LedgerRequest;
import de.jexcellence.jexplatform.logging.JExLogger;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

/**
 * The economy facade: implements {@link EconomyProvider} for other plugins (registered as a Bukkit service under
 * {@code EconomyProvider.class}) and offers the entity-based operations the commands and views use.
 *
 * <p>The work lives in focused collaborators, wired by {@link EconomyServices}: the {@link CurrencyRegistry},
 * {@link CurrencyLifecycle}, {@link AccountDirectory}, {@link WalletService} (on top of the per-account balance
 * ledger) and {@link TransferService}. Every mutating operation runs off the caller's thread and returns a future;
 * Bukkit events are dispatched on the main thread by {@link EconomyEvents}.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class EconomyService implements EconomyProvider {

    /** Error text of a transfer refused by the transfer guard. */
    public static final String TRANSFER_BLOCKED = FailureReason.TRADE_BLOCKED.text();

    /** {@code /pay} amounts from this value on (in coins as players see them) ask for a confirmation first. */
    public static final double DEFAULT_PAY_CONFIRM_THRESHOLD = CurrencyConfig.DEFAULT_PAY_CONFIRM_THRESHOLD;

    private static final String SET_BALANCE_REASON = "Set balance";
    private static final String RESET_BALANCE_REASON = "Balance reset";

    private final CurrencyRegistry currencies;
    private final CurrencyLifecycle lifecycle;
    private final AccountDirectory accounts;
    private final WalletService wallets;
    private final TransferService transfers;
    private final OnlineBalances onlineBalances;
    private final TransferJournal journal;
    private final Executor executor;
    private final JExLogger logger;
    private final EconomyDrain drain;

    /**
     * The collaborators of the facade.
     *
     * @param currencies the currency registry
     * @param lifecycle  currency create, edit, delete and reset
     * @param accounts   account reads
     * @param wallets    wallet bookings
     * @param transfers  player-to-player transfers
     * @param onlineBalances the Vault balances of online players
     * @param runtime    the executor, the logger and the shutdown drain
     */
    public record Parts(@NotNull CurrencyRegistry currencies, @NotNull CurrencyLifecycle lifecycle,
                        @NotNull AccountDirectory accounts, @NotNull WalletService wallets,
                        @NotNull TransferService transfers, @NotNull OnlineBalances onlineBalances,
                        @NotNull Infrastructure runtime) {
    }

    /**
     * The infrastructure of the facade.
     *
     * @param executor the economy executor
     * @param logger   the plugin logger
     * @param drain    finishes queued changes on shutdown
     * @param journal  the record of payments whose legs are booked separately
     */
    public record Infrastructure(@NotNull Executor executor, @NotNull JExLogger logger, @NotNull EconomyDrain drain,
                                 @NotNull TransferJournal journal) {
    }

    /**
     * Creates the facade; use {@link EconomyServices#create} to build the collaborators.
     *
     * @param parts the collaborators
     */
    public EconomyService(@NotNull Parts parts) {
        this.currencies = parts.currencies();
        this.lifecycle = parts.lifecycle();
        this.accounts = parts.accounts();
        this.wallets = parts.wallets();
        this.transfers = parts.transfers();
        this.onlineBalances = parts.onlineBalances();
        this.journal = parts.runtime().journal();
        this.executor = parts.runtime().executor();
        this.logger = parts.runtime().logger();
        this.drain = parts.runtime().drain();
    }

    /**
     * Loads and seeds the currencies off the main thread, see {@link CurrencyRegistry#bootstrap()}.
     *
     * @return future that completes when the registry is ready
     */
    public @NotNull CompletableFuture<Void> bootstrap() {
        return CompletableFuture.runAsync(currencies::bootstrap, executor);
    }

    /**
     * Finishes the queued balance changes before the database closes, see {@link EconomyDrain}.
     *
     * @param timeout the longest wait
     * @return the number of accounts that still had a queued change
     */
    public int drain(@NotNull Duration timeout) {
        return drain.drain(timeout);
    }

    /**
     * Reloads {@code currencies.yml} and the currency cache, creating newly configured currencies.
     *
     * @return future that completes when the cache has been refreshed
     */
    public @NotNull CompletableFuture<Void> reload() {
        return CompletableFuture.runAsync(currencies::reload, executor);
    }

    /**
     * All cached currency entities keyed by identifier. Internal use; other plugins use {@link #getCurrencies()}.
     *
     * @return unmodifiable currency entity map
     */
    public @NotNull Map<String, Currency> getAllCurrencies() {
        return currencies.all();
    }

    /**
     * Looks up a cached currency entity. Internal use; other plugins use {@link #getCurrency(String)}.
     *
     * @param identifier the currency identifier
     * @return the currency entity, or empty
     */
    public @NotNull Optional<Currency> findCurrency(@NotNull String identifier) {
        return currencies.find(identifier);
    }

    /**
     * The currency {@code /pay} uses when the sender omits one.
     *
     * @return the default pay currency, or empty without currencies
     */
    public @NotNull Optional<Currency> defaultPayCurrency() {
        return currencies.defaultPayCurrency();
    }

    /**
     * Whether a currency may be transferred between players via {@code /pay}.
     *
     * @param identifier the currency identifier
     * @return {@code false} for real-money or blacklisted currencies
     */
    public boolean isPayable(@NotNull String identifier) {
        return currencies.policy(identifier).payable();
    }

    /**
     * Whether a currency is shown without decimals.
     *
     * @param currency the currency
     * @return {@code true} for whole-number currencies
     */
    public boolean isWholeNumbers(@NotNull Currency currency) {
        return currencies.policy(currency.getIdentifier()).wholeNumbers();
    }

    /**
     * {@code /pay} amounts from this value on ask for a confirmation ({@code pay-confirm-threshold}).
     *
     * @return the threshold
     */
    public double payConfirmThreshold() {
        return currencies.payConfirmThreshold();
    }

    @Override
    public @NotNull Map<String, CurrencySnapshot> getCurrencies() {
        return currencies.snapshots();
    }

    @Override
    public @NotNull Optional<CurrencySnapshot> getCurrency(@NotNull String identifier) {
        return currencies.find(identifier).map(Currency::toSnapshot);
    }

    @Override
    public @NotNull CurrencyPolicy getPolicy(@NotNull String identifier) {
        return currencies.policy(identifier);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> registerCurrency(@NotNull CurrencyDefinition definition) {
        currencies.registerPolicy(definition.identifier(), definition.policy());
        if (currencies.find(definition.identifier()).isPresent()) {
            return CompletableFuture.completedFuture(true);
        }
        var seed = new CurrencyConfig.Seed(definition.identifier(), definition.symbol(), definition.prefix(),
                definition.suffix(), definition.icon());
        return CompletableFuture.supplyAsync(() -> currencies.register(seed), executor);
    }

    @Override
    public @NotNull CompletableFuture<Integer> resetCurrency(@NotNull String identifier, @Nullable String reason) {
        return lifecycle.reset(identifier, reason);
    }

    @Override
    public @NotNull CompletableFuture<Double> getBalance(@NotNull OfflinePlayer player, @NotNull String currencyId) {
        return currencies.find(currencyId)
                .map(currency -> getBalance(player, currency))
                .orElseGet(() -> CompletableFuture.completedFuture(0.0));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> hasAccount(@NotNull OfflinePlayer player, @NotNull String currencyId) {
        return currencies.find(currencyId)
                .map(currency -> accounts.account(player, currency).thenApply(Optional::isPresent))
                .orElseGet(() -> CompletableFuture.completedFuture(false));
    }

    @Override
    public @NotNull CompletableFuture<Boolean> has(@NotNull OfflinePlayer player, @NotNull String currencyId,
                                                   double amount) {
        return getBalance(player, currencyId).thenApply(balance -> balance >= amount);
    }

    @Override
    public @NotNull CompletableFuture<TransactionResult> deposit(@NotNull OfflinePlayer player,
                                                                 @NotNull String currencyId, double amount,
                                                                 @Nullable Player initiator,
                                                                 @Nullable String reason) {
        return deposit(player, currencyId, amount, initiator, reason, ChangeType.DEPOSIT);
    }

    @Override
    public @NotNull CompletableFuture<TransactionResult> deposit(@NotNull OfflinePlayer player,
                                                                 @NotNull String currencyId, double amount,
                                                                 @Nullable Player initiator, @Nullable String reason,
                                                                 @NotNull ChangeType changeType) {
        return currencies.find(currencyId)
                .map(currency -> deposit(player, currency, amount, initiator, reason, changeType))
                .orElseGet(() -> unknownCurrency(currencyId, amount));
    }

    @Override
    public @NotNull CompletableFuture<TransactionResult> withdraw(@NotNull OfflinePlayer player,
                                                                  @NotNull String currencyId, double amount,
                                                                  @Nullable Player initiator,
                                                                  @Nullable String reason) {
        return currencies.find(currencyId)
                .map(currency -> withdraw(player, currency, amount, initiator, reason))
                .orElseGet(() -> unknownCurrency(currencyId, amount));
    }

    @Override
    public @NotNull CompletableFuture<TransactionResult> transfer(@NotNull Player sender,
                                                                  @NotNull OfflinePlayer recipient,
                                                                  @NotNull String currencyId, double amount) {
        return currencies.find(currencyId)
                .map(currency -> transfer(sender, recipient, currency, amount))
                .orElseGet(() -> unknownCurrency(currencyId, amount));
    }

    @Override
    public @NotNull CompletableFuture<List<AccountSnapshot>> getTopAccounts(@NotNull String currencyId, int limit) {
        return currencies.find(currencyId)
                .map(currency -> getTopAccounts(currency, limit)
                        .thenApply(top -> top.stream().map(Account::toSnapshot).toList()))
                .orElseGet(() -> CompletableFuture.completedFuture(List.of()));
    }

    private static @NotNull CompletableFuture<TransactionResult> unknownCurrency(@NotNull String currencyId,
                                                                                double amount) {
        return CompletableFuture.completedFuture(FailureReason.CURRENCY_NOT_FOUND.result(amount, 0, currencyId));
    }

    /**
     * The balance of a player, zero without an account.
     *
     * @param player   the player
     * @param currency the currency entity
     * @return future resolving to the balance
     */
    public @NotNull CompletableFuture<Double> getBalance(@NotNull OfflinePlayer player, @NotNull Currency currency) {
        return accounts.balance(player, currency);
    }

    /**
     * The balance to show a player, see {@link AccountDirectory#displayBalance(OfflinePlayer, Currency)}.
     *
     * @param player   the player
     * @param currency the currency
     * @return future resolving to the shown balance
     */
    public @NotNull CompletableFuture<Double> getDisplayBalance(@NotNull OfflinePlayer player,
                                                                @NotNull Currency currency) {
        return accounts.displayBalance(player, currency);
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
        return accounts.displayBalance(player, currency, actual);
    }

    /**
     * Registers the source of display balances for currencies held outside the player's own account.
     *
     * @param source the source
     */
    public void setDisplayBalanceSource(@NotNull BiFunction<UUID, String, OptionalDouble> source) {
        accounts.setDisplayBalanceSource(source);
    }

    /**
     * Whether the display balance is held outside the wallet. Admin views and
     * {@code /eco set} keep the raw wallet and never ask.
     *
     * @param player   the player
     * @param currency the currency
     * @return {@code true} for an external balance
     */
    public boolean isExternalBalance(@NotNull UUID player, @NotNull Currency currency) {
        return accounts.isExternalBalance(player, currency);
    }

    /**
     * Registers the check behind {@link #isExternalBalance(UUID, Currency)}.
     *
     * @param source the check
     */
    public void setExternalBalanceSource(@NotNull BiPredicate<UUID, String> source) {
        accounts.setExternalBalanceSource(source);
    }

    /**
     * Registers where deposits may go instead of the wallet (an extension's own balances). Withdrawals, balance sets
     * and resets keep using the wallet.
     *
     * @param redirect the redirect
     */
    public void setDepositRedirect(@NotNull DepositRedirect redirect) {
        wallets.setRedirect(redirect);
    }

    /**
     * Installs the guard that refuses player-to-player transfers (both directions), e.g. an extension's trade rules.
     * The guard runs off the main thread and gets the sender and recipient UUIDs.
     *
     * @param guard returns {@code true} to refuse
     */
    public void setTransferGuard(@NotNull BiPredicate<UUID, UUID> guard) {
        transfers.setGuard(guard);
    }

    /**
     * Registers where transfer legs book their money instead of the wallets (an extension's own balances).
     *
     * @param route the route
     */
    public void setTransferRoute(@NotNull TransferRoute route) {
        transfers.setRoute(route);
    }

    /**
     * Deposits an amount into a player's account.
     *
     * @param player    the target player
     * @param currency  the currency entity
     * @param amount    the amount to deposit (must be positive)
     * @param initiator the player who initiated the deposit, or {@code null}
     * @param reason    optional human-readable reason
     * @return future resolving to the transaction result
     */
    public @NotNull CompletableFuture<TransactionResult> deposit(@NotNull OfflinePlayer player,
                                                                 @NotNull Currency currency, double amount,
                                                                 @Nullable Player initiator,
                                                                 @Nullable String reason) {
        return deposit(player, currency, amount, initiator, reason, ChangeType.DEPOSIT);
    }

    private @NotNull CompletableFuture<TransactionResult> deposit(@NotNull OfflinePlayer player,
                                                                  @NotNull Currency currency, double amount,
                                                                  @Nullable Player initiator,
                                                                  @Nullable String reason,
                                                                  @NotNull ChangeType changeType) {
        if (!Money.isBookable(amount)) {
            return CompletableFuture.completedFuture(FailureReason.AMOUNT_NOT_POSITIVE.result(amount, 0));
        }
        return wallets.deposit(booking(player, currency, amount, initiator, reason), changeType);
    }

    /**
     * Deposits like {@link #deposit(OfflinePlayer, String, double, Player, String)} and returns where the money went,
     * so the deposit can be {@link #reverse reversed} exactly (wallet part from the wallet, external share from
     * the bank that received it).
     *
     * @param player     the target player
     * @param currencyId the currency identifier
     * @param amount     the amount to deposit (must book at least one cent)
     * @param initiator  the player who initiated the deposit, or {@code null}
     * @param reason     optional human-readable reason
     * @return future resolving to the receipt; it never completes exceptionally
     */
    public @NotNull CompletableFuture<WalletService.Receipt> depositReversible(@NotNull OfflinePlayer player,
                                                                              @NotNull String currencyId,
                                                                              double amount,
                                                                              @Nullable Player initiator,
                                                                              @Nullable String reason) {
        var currency = currencies.find(currencyId);
        if (currency.isEmpty() || !Money.isBookable(amount)) {
            var refused = currency.isEmpty()
                    ? FailureReason.CURRENCY_NOT_FOUND.result(amount, 0, currencyId)
                    : FailureReason.AMOUNT_NOT_POSITIVE.result(amount, 0);
            return CompletableFuture.completedFuture(WalletService.Receipt.walletOnly(refused, Money.ZERO));
        }
        return wallets.depositWithReceipt(booking(player, currency.get(), amount, initiator, reason),
                ChangeType.DEPOSIT);
    }

    /**
     * Reverses a deposit made with {@link #depositReversible}.
     *
     * @param player     the player of the deposit
     * @param currencyId the currency identifier
     * @param receipt    the receipt of the deposit
     * @param reason     the logged reason of the reversal
     * @return future resolving to {@code true} when every part was taken back
     */
    public @NotNull CompletableFuture<Boolean> reverse(@NotNull OfflinePlayer player, @NotNull String currencyId,
                                                       @NotNull WalletService.Receipt receipt,
                                                       @NotNull String reason) {
        var currency = currencies.find(currencyId);
        if (currency.isEmpty() || !receipt.result().isSuccess()) {
            return CompletableFuture.completedFuture(!receipt.result().isSuccess());
        }
        return wallets.reverse(booking(player, currency.get(), receipt.result().amount(), null, reason), receipt);
    }

    /**
     * Withdraws an amount from a player's account.
     *
     * @param player    the target player
     * @param currency  the currency entity
     * @param amount    the amount to withdraw (must be positive)
     * @param initiator the player who initiated the withdrawal, or {@code null}
     * @param reason    optional human-readable reason
     * @return future resolving to the transaction result
     */
    public @NotNull CompletableFuture<TransactionResult> withdraw(@NotNull OfflinePlayer player,
                                                                  @NotNull Currency currency, double amount,
                                                                  @Nullable Player initiator,
                                                                  @Nullable String reason) {
        if (!Money.isBookable(amount)) {
            return CompletableFuture.completedFuture(FailureReason.AMOUNT_NOT_POSITIVE.result(amount, 0));
        }
        return wallets.withdraw(booking(player, currency, amount, initiator, reason));
    }

    /**
     * Transfers an amount from one player to another; a failed deposit is refunded to the sender.
     *
     * @param sender    the sending player
     * @param recipient the receiving player
     * @param currency  the currency entity
     * @param amount    the amount to transfer (must be positive)
     * @return future resolving to the transaction result
     */
    public @NotNull CompletableFuture<TransactionResult> transfer(@NotNull Player sender,
                                                                  @NotNull OfflinePlayer recipient,
                                                                  @NotNull Currency currency, double amount) {
        return transfers.transfer(sender, recipient, currency, amount);
    }

    /**
     * Sets an absolute balance inside the account's queue, so a concurrent deposit is never lost. Fires the usual
     * events with the resulting difference; a value equal to the current balance changes nothing.
     *
     * @param player     the target player
     * @param currency   the currency entity
     * @param newBalance the absolute balance to set (must be {@code >= 0})
     * @param initiator  the player who initiated the change, or {@code null}
     * @param reason     optional human-readable reason
     * @return future resolving to the transaction result
     */
    public @NotNull CompletableFuture<TransactionResult> setBalance(@NotNull OfflinePlayer player,
                                                                    @NotNull Currency currency, double newBalance,
                                                                    @Nullable Player initiator,
                                                                    @Nullable String reason) {
        if (newBalance < 0 || Double.isNaN(newBalance) || Double.isInfinite(newBalance)) {
            return CompletableFuture.completedFuture(FailureReason.BALANCE_NEGATIVE.result(newBalance, 0));
        }
        return wallets.set(booking(player, currency, newBalance, initiator,
                reason != null ? reason : SET_BALANCE_REASON));
    }

    /**
     * Resets a player's balance for a currency to zero.
     *
     * @param player    the target player
     * @param currency  the currency entity
     * @param initiator the player who initiated the reset, or {@code null}
     * @return future resolving to the transaction result
     */
    public @NotNull CompletableFuture<TransactionResult> resetBalance(@NotNull OfflinePlayer player,
                                                                      @NotNull Currency currency,
                                                                      @Nullable Player initiator) {
        return setBalance(player, currency, 0.0, initiator, RESET_BALANCE_REASON);
    }

    @Override
    public @NotNull CompletableFuture<BigDecimal> getExactBalance(@NotNull UUID player, @NotNull String currencyId) {
        return currencies.find(currencyId)
                .map(currency -> offlinePlayer(player)
                        .thenCompose(owner -> accounts.account(owner, currency))
                        .thenApply(found -> found.map(Account::getBalance).orElse(Money.ZERO)))
                .orElseGet(() -> CompletableFuture.completedFuture(Money.ZERO));
    }

    @Override
    public @NotNull CompletableFuture<TransactionResult> deposit(@NotNull UUID player, @NotNull String currencyId,
                                                                 @NotNull BigDecimal amount, @Nullable UUID initiator,
                                                                 @Nullable String reason) {
        return refusalOf(currencyId, amount).orElseGet(() -> offlinePlayer(player).thenCompose(owner -> wallets.deposit(
                new WalletService.Booking(owner, currencies.find(currencyId).orElseThrow(), Money.of(amount),
                        initiator, reason),
                ChangeType.DEPOSIT)));
    }

    @Override
    public @NotNull CompletableFuture<TransactionResult> withdraw(@NotNull UUID player, @NotNull String currencyId,
                                                                  @NotNull BigDecimal amount, @Nullable UUID initiator,
                                                                  @Nullable String reason) {
        return refusalOf(currencyId, amount).orElseGet(() -> offlinePlayer(player).thenCompose(owner -> wallets.withdraw(
                new WalletService.Booking(owner, currencies.find(currencyId).orElseThrow(), Money.of(amount),
                        initiator, reason))));
    }

    private @NotNull Optional<CompletableFuture<TransactionResult>> refusalOf(@NotNull String currencyId,
                                                                              @NotNull BigDecimal amount) {
        double requested = amount.doubleValue();
        if (currencies.find(currencyId).isEmpty()) {
            return Optional.of(unknownCurrency(currencyId, requested));
        }
        return Money.isPositive(Money.of(amount))
                ? Optional.empty()
                : Optional.of(CompletableFuture.completedFuture(FailureReason.AMOUNT_NOT_POSITIVE.result(requested, 0)));
    }

    /**
     * Resolves a UUID off the caller's thread, because reading the name of an offline player can touch the disk.
     */
    private @NotNull CompletableFuture<OfflinePlayer> offlinePlayer(@NotNull UUID player) {
        return CompletableFuture.supplyAsync(() -> Bukkit.getOfflinePlayer(player), executor);
    }

    /**
     * Whether the server has seen this player. An online player answers at once and an offline one by its economy
     * row. Only a player without a row reads the player file, off the caller's thread: someone who played before
     * JExEconomy was installed has no row yet.
     *
     * @param player the player
     * @return future resolving to {@code true} for a known player; a failed lookup counts as unknown
     */
    public @NotNull CompletableFuture<Boolean> isKnownPlayer(@NotNull OfflinePlayer player) {
        if (player.isOnline()) {
            return CompletableFuture.completedFuture(true);
        }
        return accounts.isRegistered(player.getUniqueId())
                .thenCompose(registered -> Boolean.TRUE.equals(registered)
                        ? CompletableFuture.completedFuture(true)
                        : CompletableFuture.supplyAsync(player::hasPlayedBefore, executor))
                .exceptionally(failure -> {
                    logger.warn("Could not look up player {}: {}", player.getUniqueId(), failure.getMessage());
                    return false;
                });
    }

    /**
     * Starts caching the Vault balance of a player who joined.
     *
     * @param player the player
     */
    public void trackOnlineBalance(@NotNull OfflinePlayer player) {
        vaultCurrency().ifPresent(currency -> onlineBalances.track(player, currency));
    }

    /**
     * Stops caching the Vault balance of a player who left.
     *
     * @param player the player's id
     */
    public void forgetOnlineBalance(@NotNull UUID player) {
        onlineBalances.forget(player);
    }

    /**
     * The balance of an online player from memory, for callers that may not wait.
     *
     * @param player   the player
     * @param currency the currency
     * @return the balance, empty when it is not cached
     */
    public @NotNull OptionalDouble cachedBalance(@NotNull OfflinePlayer player, @NotNull Currency currency) {
        return onlineBalances.balance(player.getUniqueId(), currency.getIdentifier());
    }

    /**
     * Passes a prepared balance change to the ledger, without a deposit redirect. Used by the balance import.
     *
     * @param request the change
     * @return future resolving to the result
     */
    public @NotNull CompletableFuture<TransactionResult> submitLedgerRequest(@NotNull LedgerRequest request) {
        return wallets.submit(request);
    }

    /** Reports payments a crash cut off between their legs, see {@link TransferJournal}. */
    public void reportInterruptedTransfers() {
        journal.reportInterrupted();
    }

    /**
     * The one currency Vault plugins see, {@code vault.currency} in {@code currencies.yml}.
     *
     * @return the currency, empty when it does not exist
     */
    public @NotNull Optional<Currency> vaultCurrency() {
        return currencies.vaultCurrency();
    }

    /**
     * Withdraws for a caller that blocks until the change is done.
     *
     * @param player        the account owner
     * @param currency      the currency
     * @param amount        the positive amount
     * @param skipsPreEvent {@code true} when the caller waits on a server thread
     * @return future resolving to the transaction result
     */
    public @NotNull CompletableFuture<TransactionResult> withdrawForWaitingCaller(@NotNull OfflinePlayer player,
                                                                                  @NotNull Currency currency,
                                                                                  double amount,
                                                                                  boolean skipsPreEvent) {
        return wallets.withdraw(new WalletService.Booking(player, currency, Money.of(amount), null, null,
                skipsPreEvent));
    }

    /**
     * Deposits for a caller that blocks until the change is done, and tells where the money went so the deposit can
     * be {@link #reverse reversed}.
     *
     * @param player        the account owner
     * @param currency      the currency
     * @param amount        the positive amount
     * @param skipsPreEvent {@code true} when the caller waits on a server thread
     * @return future resolving to the receipt
     */
    public @NotNull CompletableFuture<WalletService.Receipt> depositForWaitingCaller(@NotNull OfflinePlayer player,
                                                                                     @NotNull Currency currency,
                                                                                     double amount,
                                                                                     boolean skipsPreEvent) {
        return wallets.depositWithReceipt(new WalletService.Booking(player, currency, Money.of(amount), null, null,
                skipsPreEvent), ChangeType.DEPOSIT);
    }

    /**
     * Adds an amount straight to the wallet, without the deposit redirect; used to take back a withdrawal.
     *
     * @param player   the account owner
     * @param currency the currency
     * @param amount   the positive amount
     * @param reason   the logged reason
     * @return future resolving to the transaction result
     */
    public @NotNull CompletableFuture<TransactionResult> creditWallet(@NotNull OfflinePlayer player,
                                                                      @NotNull Currency currency, double amount,
                                                                      @NotNull String reason) {
        return wallets.credit(new WalletService.Booking(player, currency, Money.of(amount), null, reason),
                ChangeType.DEPOSIT);
    }

    /**
     * Records that the thread dispatching pre-events now waits for a change of this account, so an earlier change
     * of that account does not hold it up.
     *
     * @param player   the account owner
     * @param currency the currency
     * @return closes the park
     */
    public @NotNull BalanceLedger.Parking parkDispatchThread(@NotNull OfflinePlayer player,
                                                             @NotNull Currency currency) {
        return wallets.parkDispatchThread(AccountKey.of(player.getUniqueId(), null, currency.getIdentifier()));
    }

    private static @NotNull WalletService.Booking booking(@NotNull OfflinePlayer player, @NotNull Currency currency,
                                                          double amount, @Nullable Player initiator,
                                                          @Nullable String reason) {
        return new WalletService.Booking(player, currency, Money.of(amount),
                initiator != null ? initiator.getUniqueId() : null, reason);
    }

    /**
     * Creates a new currency, firing pre/post events.
     *
     * @param currency the currency entity to create
     * @param creator  the player creating the currency, or {@code null} if server-initiated
     * @return future resolving to {@code true} if the currency was created
     */
    public @NotNull CompletableFuture<Boolean> createCurrency(@NotNull Currency currency, @Nullable Player creator) {
        return lifecycle.create(currency, creator != null ? creator.getUniqueId() : null);
    }

    /**
     * Deletes a currency with its accounts and transaction logs, firing pre/post events with impact data.
     *
     * @param identifier the currency identifier
     * @param deleter    the player deleting the currency, or {@code null} if server-initiated
     * @return future resolving to {@code true} if the currency was deleted
     */
    public @NotNull CompletableFuture<Boolean> deleteCurrency(@NotNull String identifier, @Nullable Player deleter) {
        return lifecycle.delete(identifier, deleter != null ? deleter.getUniqueId() : null)
                .exceptionally(failure -> {
                    logger.error("Deleting currency '{}' failed, nothing was removed: {}", identifier,
                            failure.getMessage());
                    return false;
                });
    }

    /**
     * Updates a currency's properties; the cache receives the saved copy only after the database accepted it.
     *
     * @param originalIdentifier the current identifier used to locate the currency
     * @param updated            value holder carrying the new field values
     * @param editor             the player making the change, or {@code null} if server-initiated
     * @return future resolving to {@code true} if the update was persisted
     */
    public @NotNull CompletableFuture<Boolean> updateCurrency(@NotNull String originalIdentifier,
                                                              @NotNull Currency updated, @Nullable Player editor) {
        return lifecycle.update(originalIdentifier, updated);
    }

    /**
     * The account of a player for a currency.
     *
     * @param player   the player
     * @param currency the currency entity
     * @return future resolving to the account, or empty
     */
    public @NotNull CompletableFuture<Optional<Account>> getAccount(@NotNull OfflinePlayer player,
                                                                    @NotNull Currency currency) {
        return accounts.account(player, currency);
    }

    /**
     * Finds or creates the account of an economy player.
     *
     * @param econPlayer the economy player
     * @param currency   the currency entity
     * @return future resolving to the account
     */
    public @NotNull CompletableFuture<Account> getOrCreateAccount(@NotNull EconomyPlayer econPlayer,
                                                                  @NotNull Currency currency) {
        return accounts.getOrCreate(econPlayer, currency);
    }

    /**
     * Finds or creates player and account by UUID; a new player row stores the start of the UUID, an existing
     * row keeps its name.
     *
     * @param playerUuid the player's UUID
     * @param currency   the currency entity
     * @return future resolving to the account
     */
    public @NotNull CompletableFuture<Account> getOrCreateAccount(@NotNull UUID playerUuid,
                                                                  @NotNull Currency currency) {
        return accounts.getOrCreate(playerUuid, null, currency);
    }

    /**
     * Finds or creates player and account with the given name.
     *
     * @param playerUuid the player's UUID
     * @param playerName the player's current name
     * @param currency   the currency entity
     * @return future resolving to the account
     */
    public @NotNull CompletableFuture<Account> getOrCreateAccount(@NotNull UUID playerUuid,
                                                                  @NotNull String playerName,
                                                                  @NotNull Currency currency) {
        return accounts.getOrCreate(playerUuid, playerName, currency);
    }

    /**
     * All accounts of a player across currencies.
     *
     * @param player the player
     * @return future resolving to the accounts
     */
    public @NotNull CompletableFuture<List<Account>> getAccounts(@NotNull OfflinePlayer player) {
        return accounts.accounts(player);
    }

    /**
     * The top accounts of a currency, highest balance first.
     *
     * @param currency the currency entity
     * @param limit    maximum number of results
     * @return future resolving to the accounts
     */
    public @NotNull CompletableFuture<List<Account>> getTopAccounts(@NotNull Currency currency, int limit) {
        return accounts.top(currency, limit);
    }
}
