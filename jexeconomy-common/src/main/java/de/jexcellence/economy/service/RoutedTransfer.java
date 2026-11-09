package de.jexcellence.economy.service;

import de.jexcellence.economy.api.TransactionResult;
import de.jexcellence.economy.api.extension.TransferRoute;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Runs a player-to-player transfer through a {@link TransferRoute}: withdraw from the sender first, then deposit for
 * the recipient, and give the money back to where it came from when the deposit fails. One transfer per sender runs
 * at a time, so a double click or a repeated command cannot race the balance check. Neither side can go negative: a
 * withdrawal that does not fit is refused before anything moves.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public final class RoutedTransfer {

    /** Error of a transfer refused because the sender already has one running. */
    public static final String ERROR_IN_FLIGHT = FailureReason.TRANSFER_IN_FLIGHT.text();
    /** Error of a transfer the sender may not pay from the routed balance (permission of the holder). */
    public static final String ERROR_BANK_DENIED = FailureReason.BANK_DENIED.text();
    /** Error of a transfer the recipient's routed balance has no room for. */
    public static final String ERROR_RECIPIENT_FULL = FailureReason.RECIPIENT_BANK_FULL.text();
    /** Error of a transfer with a fractional amount from a whole-unit balance. */
    public static final String ERROR_WHOLE_COINS = FailureReason.WHOLE_COINS.text();
    /** Error of a transfer the sender cannot afford. */
    public static final String ERROR_INSUFFICIENT = FailureReason.INSUFFICIENT_FUNDS.text();

    private static final String ERROR_FAILED = FailureReason.TRANSFER_FAILED.text();

    private final Supplier<TransferRoute> route;
    private final Executor executor;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * The parties and amount of a transfer.
     *
     * @param sender     the paying player
     * @param recipient  the receiving player
     * @param currencyId the currency
     * @param amount     the positive amount
     */
    public record Request(@NotNull UUID sender, @NotNull UUID recipient, @NotNull String currencyId, double amount) {
    }

    /**
     * The wallet side of a transfer, used for every leg the route leaves on the wallet.
     *
     * @param withdraw takes the amount from the sender's wallet
     * @param deposit  pays the amount to the recipient
     * @param refund   gives the amount back to the sender's wallet
     */
    public record WalletLegs(@NotNull Supplier<CompletableFuture<TransactionResult>> withdraw,
                             @NotNull Supplier<CompletableFuture<TransactionResult>> deposit,
                             @NotNull Supplier<CompletableFuture<TransactionResult>> refund) {
    }

    /**
     * What a transfer did.
     *
     * @param result    the result for the sender; its balance is the sender's balance afterwards
     * @param withdrawn the sender leg, {@code null} when the transfer was refused before it
     * @param deposited the recipient leg, {@code null} when the transfer did not reach it
     * @param lost      {@code true} when the deposit failed and the money could not be given back either
     */
    public record Receipt(@NotNull TransactionResult result, @Nullable TransferRoute.Leg withdrawn,
                          @Nullable TransferRoute.Leg deposited, boolean lost) {

        static @NotNull Receipt refused(@NotNull TransactionResult result) {
            return new Receipt(result, null, null, false);
        }
    }

    /**
     * Creates the runner.
     *
     * @param route    the current route, read for every transfer
     * @param executor runs the route legs off the main thread
     */
    public RoutedTransfer(@NotNull Supplier<TransferRoute> route, @NotNull Executor executor) {
        this.route = route;
        this.executor = executor;
    }

    /**
     * Runs a transfer. Completes with a refused receipt at once while the sender has another transfer running.
     *
     * @param request the transfer
     * @param wallet  the wallet legs
     * @return the receipt
     */
    public @NotNull CompletableFuture<Receipt> execute(@NotNull Request request, @NotNull WalletLegs wallet) {
        UUID sender = request.sender();
        if (!inFlight.add(sender)) {
            return CompletableFuture.completedFuture(Receipt.refused(failure(request, 0.0, ERROR_IN_FLIGHT)));
        }
        TransferRoute current = route.get();
        CompletableFuture<Receipt> run;
        try {
            run = CompletableFuture.supplyAsync(() -> current.withdraw(sender, request.recipient(),
                            request.currencyId(), request.amount()), executor)
                    .exceptionally(error -> TransferRoute.Leg.failed())
                    .thenCompose(leg -> afterWithdraw(new Context(request, wallet, current), leg));
        } catch (RuntimeException rejected) {
            inFlight.remove(sender);
            throw rejected;
        }
        return run.whenComplete((receipt, error) -> inFlight.remove(sender));
    }

    /** Whether the sender has a transfer running. */
    /**
     * Runs a payment that is booked in one step, under the same one-transfer-per-sender guard as {@link #execute}.
     *
     * @param request the transfer
     * @param booking starts the booking
     * @return future resolving to the receipt
     */
    public @NotNull CompletableFuture<Receipt> executeInOneStep(
            @NotNull Request request, @NotNull Supplier<CompletableFuture<TransactionResult>> booking) {
        UUID sender = request.sender();
        if (!inFlight.add(sender)) {
            return CompletableFuture.completedFuture(Receipt.refused(failure(request, 0.0, ERROR_IN_FLIGHT)));
        }
        CompletableFuture<Receipt> run;
        try {
            run = booking.get().thenApply(result -> new Receipt(result, null, null, false));
        } catch (RuntimeException rejected) {
            inFlight.remove(sender);
            throw rejected;
        }
        return run.whenComplete((receipt, error) -> inFlight.remove(sender));
    }

    public boolean isInFlight(@NotNull UUID sender) {
        return inFlight.contains(sender);
    }

    private record Context(@NotNull Request request, @NotNull WalletLegs wallet, @NotNull TransferRoute route) {
    }

    private @NotNull CompletableFuture<Receipt> afterWithdraw(@NotNull Context context,
                                                             @NotNull TransferRoute.Leg withdrawn) {
        Request request = context.request();
        return switch (withdrawn.outcome()) {
            case WALLET -> context.wallet().withdraw().get().thenCompose(result -> result.isFailed()
                    ? CompletableFuture.completedFuture(new Receipt(result, withdrawn, null, false))
                    : deposit(context, withdrawn, result.balance()));
            case DONE -> deposit(context, withdrawn, withdrawn.balance());
            case INSUFFICIENT -> refusedAfter(withdrawn, failure(request, withdrawn.balance(), ERROR_INSUFFICIENT));
            case DENIED -> refusedAfter(withdrawn, failure(request, withdrawn.balance(), ERROR_BANK_DENIED));
            case WHOLE_COINS_ONLY -> refusedAfter(withdrawn, failure(request, withdrawn.balance(), ERROR_WHOLE_COINS));
            default -> refusedAfter(withdrawn, failure(request, withdrawn.balance(), ERROR_FAILED));
        };
    }

    private @NotNull CompletableFuture<Receipt> deposit(@NotNull Context context, @NotNull TransferRoute.Leg withdrawn,
                                                       double senderBalance) {
        Request request = context.request();
        return CompletableFuture.supplyAsync(() -> context.route().deposit(request.recipient(), request.sender(),
                        request.currencyId(), request.amount()), executor)
                .exceptionally(error -> TransferRoute.Leg.failed())
                .thenCompose(deposited -> switch (deposited.outcome()) {
                    case DONE -> CompletableFuture.completedFuture(new Receipt(
                            TransactionResult.success(request.amount(), senderBalance), withdrawn, deposited, false));
                    case WALLET -> walletDeposit(context).thenCompose(result -> result.isSuccess()
                            ? CompletableFuture.completedFuture(new Receipt(
                                    TransactionResult.success(request.amount(), senderBalance), withdrawn, deposited,
                                    false))
                            : giveBack(context, withdrawn, ERROR_FAILED + ": " + result.error()));
                    case BANK_FULL -> giveBack(context, withdrawn, ERROR_RECIPIENT_FULL);
                    default -> giveBack(context, withdrawn, ERROR_FAILED);
                });
    }

    private static @NotNull CompletableFuture<TransactionResult> walletDeposit(@NotNull Context context) {
        double amount = context.request().amount();
        return context.wallet().deposit().get()
                .exceptionally(error -> TransactionResult.failure(amount, 0.0, String.valueOf(error.getMessage())));
    }

    private @NotNull CompletableFuture<Receipt> giveBack(@NotNull Context context, @NotNull TransferRoute.Leg withdrawn,
                                                        @NotNull String error) {
        Request request = context.request();
        if (withdrawn.routed()) {
            boolean booked = context.route().refund(withdrawn, request.recipient(), request.amount());
            return CompletableFuture.completedFuture(new Receipt(
                    failure(request, withdrawn.balance() + request.amount(), error), withdrawn, null, !booked));
        }
        return context.wallet().refund().get().thenApply(refund -> new Receipt(
                failure(request, refund.balance(), error), withdrawn, null, refund.isFailed()));
    }

    private static @NotNull CompletableFuture<Receipt> refusedAfter(@NotNull TransferRoute.Leg withdrawn,
                                                                   @NotNull TransactionResult result) {
        return CompletableFuture.completedFuture(new Receipt(result, withdrawn, null, false));
    }

    private static @NotNull TransactionResult failure(@NotNull Request request, double balance, @NotNull String error) {
        return TransactionResult.failure(request.amount(), balance, error);
    }
}
