package de.jexcellence.economy.service;

import de.jexcellence.economy.api.ChangeType;
import de.jexcellence.economy.api.TransactionResult;
import de.jexcellence.economy.api.extension.DepositRedirect;
import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.money.Money;
import de.jexcellence.economy.api.extension.DepositRedirect.Redirected;
import de.jexcellence.economy.service.ledger.AccountKey;
import de.jexcellence.economy.service.ledger.BalanceLedger;
import de.jexcellence.economy.service.ledger.BalanceOperation;
import de.jexcellence.economy.service.ledger.LedgerRequest;
import de.jexcellence.economy.service.ledger.LedgerTransfer;
import de.jexcellence.jexplatform.logging.JExLogger;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wallet bookings on top of the {@link BalanceLedger}: deposits (first offered to the registered
 * {@link DepositRedirect}, an extension's own balances), plain credits, withdrawals and absolute sets.
 *
 * <p>A failed deposit books nothing: when the wallet part fails after the redirect took a share, also through an
 * exception of the ledger (database error, overflow, version conflicts, a stopped executor), the share is taken back
 * out of exactly the bank that received it and the deposit fails. Callers refund items, keys or bank balances on a
 * failed deposit, so a failure that left the share booked would pay it twice. When the share cannot be taken back
 * (the bank spent it, or the bank is unknown), the deposit is reported as a success whose {@code amount()} is the
 * booked share only, and both amounts are logged for a manual top-up. Withdrawals, credits and sets never use the
 * redirect.
 *
 * <p>{@link #depositWithReceipt} returns where the money went, so a caller can {@link #reverse} a deposit later
 * (coupon payouts).
 *
 * @author JExcellence
 * @since 3.1.0
 */
public final class WalletService {

    private final BalanceLedger ledger;
    private final AccountDirectory accounts;
    private final JExLogger logger;
    private final AtomicReference<DepositRedirect> redirect = new AtomicReference<>(DepositRedirect.NONE);

    /**
     * Where a deposit was booked.
     *
     * @param result     the result reported to the caller
     * @param walletPart the amount booked into the wallet, zero when none
     * @param redirected the share the redirect holds, {@link Redirected#NONE} when none
     */
    public record Receipt(@NotNull TransactionResult result, @NotNull BigDecimal walletPart,
                          @NotNull Redirected redirected) {

        static @NotNull Receipt walletOnly(@NotNull TransactionResult result, @NotNull BigDecimal amount) {
            return new Receipt(result, result.isSuccess() ? amount : Money.ZERO, Redirected.NONE);
        }
    }

    /**
     * Creates the service.
     *
     * @param ledger   the balance ledger
     * @param accounts the account reads, for the balance after a fully redirected deposit
     * @param logger   the plugin logger
     */
    public WalletService(@NotNull BalanceLedger ledger, @NotNull AccountDirectory accounts,
                         @NotNull JExLogger logger) {
        this.ledger = ledger;
        this.accounts = accounts;
        this.logger = logger;
    }

    /**
     * Registers where deposits may go instead of the wallet.
     *
     * @param target the redirect
     */
    public void setRedirect(@NotNull DepositRedirect target) {
        redirect.set(target);
    }

    /**
     * Deposits a positive amount, offering it to the redirect first.
     *
     * @param booking    who, what and how much
     * @param changeType the recorded type
     * @return future resolving to the result
     */
    public @NotNull CompletableFuture<TransactionResult> deposit(@NotNull Booking booking,
                                                                 @NotNull ChangeType changeType) {
        return depositWithReceipt(booking, changeType).thenApply(Receipt::result);
    }

    /**
     * Deposits like {@link #deposit} and tells where the money went.
     *
     * @param booking    who, what and how much
     * @param changeType the recorded type
     * @return future resolving to the receipt; it never completes exceptionally
     */
    public @NotNull CompletableFuture<Receipt> depositWithReceipt(@NotNull Booking booking,
                                                                  @NotNull ChangeType changeType) {
        BigDecimal amount = booking.amount();
        Redirected redirected = redirected(booking);
        if (!redirected.taken()) {
            return guarded(credit(booking, changeType), booking)
                    .thenApply(result -> Receipt.walletOnly(result, amount));
        }
        BigDecimal rest = amount.subtract(BigDecimal.valueOf(redirected.amount()));
        double requested = Money.toDouble(amount);
        if (rest.signum() <= 0) {
            return accounts.balance(booking.player(), booking.currency())
                    .exceptionally(failure -> 0.0)
                    .thenApply(balance -> new Receipt(TransactionResult.success(requested, balance), Money.ZERO,
                            redirected));
        }
        return guarded(ledger.submit(request(booking, BalanceOperation.delta(rest), changeType)), booking)
                .thenApply(result -> result.isSuccess()
                        ? new Receipt(TransactionResult.success(requested, result.balance()), rest, redirected)
                        : walletPartFailed(booking, redirected, result));
    }

    /**
     * Reverses a successful deposit: takes the wallet part out of the wallet and the redirected share out of the
     * bank that received it.
     *
     * @param booking the original booking (player, currency, reason of the reversal)
     * @param receipt the receipt of the deposit
     * @return future resolving to {@code true} when every part was taken back
     */
    public @NotNull CompletableFuture<Boolean> reverse(@NotNull Booking booking, @NotNull Receipt receipt) {
        boolean bankUndone = !receipt.redirected().taken() || undoRedirect(booking, receipt.redirected());
        if (!Money.isPositive(receipt.walletPart())) {
            return CompletableFuture.completedFuture(bankUndone);
        }
        Booking walletPart = new Booking(booking.player(), booking.currency(), receipt.walletPart(),
                booking.initiator(), booking.reason());
        return guarded(withdraw(walletPart), booking).thenApply(result -> bankUndone && result.isSuccess());
    }

    private @NotNull Receipt walletPartFailed(@NotNull Booking booking, @NotNull Redirected redirected,
                                              @NotNull TransactionResult walletResult) {
        UUID player = booking.player().getUniqueId();
        String currencyId = booking.currency().getIdentifier();
        String amount = booking.amount().toPlainString();
        double requested = Money.toDouble(booking.amount());
        if (undoRedirect(booking, redirected)) {
            logger.warn("Deposit of {} {} for {} failed, the {} already in the external balance were taken back: {}",
                    amount, currencyId, player, redirected.amount(), walletResult.error());
            return new Receipt(TransactionResult.failure(requested, walletResult.balance(),
                    String.valueOf(walletResult.error())), Money.ZERO, Redirected.NONE);
        }
        logger.error("Deposit of {} {} for {} only partly booked: {} stay in the external balance, the wallet part "
                + "failed ({}). Reported as paid with the booked share to prevent a double refund; top up the "
                + "wallet by hand.", amount, currencyId, player, redirected.amount(), walletResult.error());
        return new Receipt(TransactionResult.success(redirected.amount(), walletResult.balance()), Money.ZERO,
                redirected);
    }

    private boolean undoRedirect(@NotNull Booking booking, @NotNull Redirected redirected) {
        UUID player = booking.player().getUniqueId();
        String currencyId = booking.currency().getIdentifier();
        try {
            return redirect.get().undo(player, currencyId, redirected);
        } catch (RuntimeException failure) {
            logger.error("Taking back {} {} from the external balance of {} failed: {}", redirected.amount(), currencyId,
                    player, failure.getMessage());
            return false;
        }
    }

    private @NotNull CompletableFuture<TransactionResult> guarded(@NotNull CompletableFuture<TransactionResult> booking,
                                                                  @NotNull Booking request) {
        double amount = Money.toDouble(request.amount());
        return booking.exceptionally(failure -> {
            logger.error("Booking of {} {} for {} failed: {}", request.amount().toPlainString(),
                    request.currency().getIdentifier(), request.player().getUniqueId(), failure.getMessage());
            return FailureReason.STORAGE_FAILED.result(amount, 0.0);
        });
    }

    /**
     * Adds a positive amount to the wallet without the redirect, e.g. a refund.
     *
     * @param booking    who, what and how much
     * @param changeType the recorded type
     * @return future resolving to the result
     */
    public @NotNull CompletableFuture<TransactionResult> credit(@NotNull Booking booking,
                                                                @NotNull ChangeType changeType) {
        return ledger.submit(request(booking, BalanceOperation.delta(booking.amount()), changeType));
    }

    /**
     * Takes a positive amount from the wallet; refused with {@link FailureReason#INSUFFICIENT_FUNDS} when it does
     * not fit.
     *
     * @param booking who, what and how much
     * @return future resolving to the result
     */
    public @NotNull CompletableFuture<TransactionResult> withdraw(@NotNull Booking booking) {
        return ledger.submit(request(booking, BalanceOperation.delta(booking.amount().negate()), ChangeType.WITHDRAW));
    }

    /**
     * Sets the wallet balance inside the account's queue, so it cannot race a concurrent deposit.
     *
     * @param booking who, what and the new balance
     * @return future resolving to the result; its amount is the difference to the old balance
     */
    public @NotNull CompletableFuture<TransactionResult> set(@NotNull Booking booking) {
        return ledger.submit(request(booking, BalanceOperation.absolute(booking.amount()), ChangeType.DEPOSIT));
    }

    /**
     * Moves an amount between two wallets in one transaction.
     *
     * @param transfer the transfer
     * @return future resolving to the sender's result
     */
    public @NotNull CompletableFuture<TransactionResult> transfer(@NotNull LedgerTransfer transfer) {
        return ledger.submitTransfer(transfer);
    }

    /**
     * Passes a prepared change to the ledger unchanged, without a deposit redirect. Used by the balance import.
     *
     * @param request the change
     * @return future resolving to the result
     */
    public @NotNull CompletableFuture<TransactionResult> submit(@NotNull LedgerRequest request) {
        return ledger.submit(request);
    }

    /**
     * Records that the dispatch thread now waits for a change of this account, see
     * {@link BalanceLedger#parkDispatchThread}.
     *
     * @param key the account
     * @return closes the park
     */
    public @NotNull BalanceLedger.Parking parkDispatchThread(@NotNull AccountKey key) {
        return ledger.parkDispatchThread(key);
    }

    private static @NotNull LedgerRequest request(@NotNull Booking booking, @NotNull BalanceOperation operation,
                                                  @NotNull ChangeType changeType) {
        return new LedgerRequest(AccountKeys.of(booking.player(), booking.currency()), operation, changeType,
                booking.reason(), booking.initiator(), booking.skipsPreEvent());
    }

    private @NotNull Redirected redirected(@NotNull Booking booking) {
        UUID player = booking.player().getUniqueId();
        String currencyId = booking.currency().getIdentifier();
        try {
            Redirected taken = redirect.get().redirect(player, currencyId, Money.toDouble(booking.amount()),
                    booking.reason() != null ? booking.reason() : "");
            long capped = Math.min(taken.amount(), booking.amount().longValue());
            return capped == taken.amount() ? taken : new Redirected(capped, taken.targetId(), taken.exact());
        } catch (RuntimeException failure) {
            logger.error("Deposit redirect failed for {} ({} {}), paying into the wallet: {}",
                    player, booking.amount().toPlainString(), currencyId, failure.getMessage());
            return Redirected.NONE;
        }
    }

    /**
     * One wallet booking.
     *
     * @param player    the account owner
     * @param currency  the currency
     * @param amount    the amount (or the new balance for {@link #set(Booking)}), not negative
     * @param initiator who started it, {@code null} for the server
     * @param reason    the logged reason, may be {@code null}
     * @param skipsPreEvent the caller waits on a server thread and cannot wait a tick for listeners
     */
    public record Booking(@NotNull OfflinePlayer player, @NotNull Currency currency, @NotNull BigDecimal amount,
                          @Nullable UUID initiator, @Nullable String reason, boolean skipsPreEvent) {

        /**
         * Creates a booking whose pre-event listeners are asked.
         *
         * @param player    the account owner
         * @param currency  the currency
         * @param amount    the amount, not negative
         * @param initiator who started it, {@code null} for the server
         * @param reason    the logged reason, may be {@code null}
         */
        public Booking(@NotNull OfflinePlayer player, @NotNull Currency currency, @NotNull BigDecimal amount,
                       @Nullable UUID initiator, @Nullable String reason) {
            this(player, currency, amount, initiator, reason, false);
        }
    }
}
