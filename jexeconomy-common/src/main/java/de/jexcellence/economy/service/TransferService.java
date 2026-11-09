package de.jexcellence.economy.service;

import de.jexcellence.economy.api.ChangeType;
import de.jexcellence.economy.api.TransactionResult;
import de.jexcellence.economy.api.extension.DepositRedirect;
import de.jexcellence.economy.api.extension.TransferRoute;
import de.jexcellence.economy.database.PlayerNames;
import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.database.entity.TransactionLog;
import de.jexcellence.economy.money.Money;
import de.jexcellence.economy.service.currency.CurrencyRegistry;
import de.jexcellence.economy.service.ledger.LedgerTransfer;
import de.jexcellence.jexplatform.logging.JExLogger;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;

/**
 * Player-to-player transfers ({@code /pay} and {@code EconomyProvider#transfer}). Checks payability and the transfer
 * guard of an extension. A payment that stays in the wallets is booked in one database transaction.
 * Any other runs the legs through {@link RoutedTransfer}: withdraw from the sender, deposit for the recipient, refund
 * the sender when the deposit fails, with a {@link TransferJournal} entry so a crash between the legs leaves a record.
 * Legs a {@link TransferRoute} booked outside the wallet get their own transaction log entries.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public final class TransferService {

    private static final String TRANSFER_TO = "Transfer to ";
    private static final String TRANSFER_FROM = "Transfer from ";

    /** Inputs of one transfer. */
    private record Transfer(@NotNull Player sender, @NotNull OfflinePlayer recipient, @NotNull Currency currency,
                            @NotNull BigDecimal amount) {
    }

    private final CurrencyRegistry currencies;
    private final WalletService wallets;
    private final TransactionLogger transactionLogger;
    private final JExLogger logger;
    private final Executor executor;
    private final TransferJournal journal;
    private final AtomicReference<BiPredicate<UUID, UUID>> guard = new AtomicReference<>((sender, target) -> false);
    private final AtomicReference<TransferRoute> route = new AtomicReference<>(TransferRoute.WALLET);
    private final RoutedTransfer transfers;

    /**
     * Creates the service.
     *
     * @param wallets           the wallet bookings of the legs the route leaves on the wallet
     * @param currencies        the currency registry, for payability
     * @param transactionLogger the transaction log writer for routed legs
     * @param logger            the plugin logger
     * @param executor          runs the guard and the routed legs off the main thread
     * @param journal           records payments whose legs are booked separately
     */
    public TransferService(@NotNull WalletService wallets, @NotNull CurrencyRegistry currencies,
                           @NotNull TransactionLogger transactionLogger, @NotNull JExLogger logger,
                           @NotNull Executor executor, @NotNull TransferJournal journal) {
        this.wallets = wallets;
        this.currencies = currencies;
        this.transactionLogger = transactionLogger;
        this.logger = logger;
        this.executor = executor;
        this.journal = journal;
        this.transfers = new RoutedTransfer(route::get, executor);
    }

    /**
     * Installs the guard that refuses transfers in both directions; it runs off the main thread.
     *
     * @param transferGuard returns {@code true} to refuse a transfer between the two players
     */
    public void setGuard(@NotNull BiPredicate<UUID, UUID> transferGuard) {
        guard.set(transferGuard);
    }

    /**
     * Registers where transfer legs book their money instead of the wallets.
     *
     * @param transferRoute the route
     */
    public void setRoute(@NotNull TransferRoute transferRoute) {
        route.set(transferRoute);
    }

    /**
     * Transfers an amount.
     *
     * @param sender    the paying player
     * @param recipient the receiving player
     * @param currency  the currency
     * @param amount    the amount, positive
     * @return future resolving to the sender's result
     */
    public @NotNull CompletableFuture<TransactionResult> transfer(@NotNull Player sender,
                                                                  @NotNull OfflinePlayer recipient,
                                                                  @NotNull Currency currency, double amount) {
        if (!Money.isBookable(amount)) {
            return CompletableFuture.completedFuture(FailureReason.AMOUNT_NOT_POSITIVE.result(amount, 0));
        }
        if (!currencies.policy(currency.getIdentifier()).payable()) {
            return CompletableFuture.completedFuture(
                    FailureReason.NOT_PAYABLE.result(amount, 0, currency.getIdentifier()));
        }
        var transfer = new Transfer(sender, recipient, currency, Money.of(amount));
        UUID senderId = sender.getUniqueId();
        UUID recipientId = recipient.getUniqueId();
        return CompletableFuture.supplyAsync(() -> guard.get().test(senderId, recipientId), executor)
                .thenCompose(blocked -> Boolean.TRUE.equals(blocked)
                        ? CompletableFuture.completedFuture(FailureReason.TRADE_BLOCKED.result(amount, 0))
                        : moveBetween(transfer));
    }

    private @NotNull CompletableFuture<TransactionResult> moveBetween(@NotNull Transfer transfer) {
        Player sender = transfer.sender();
        OfflinePlayer recipient = transfer.recipient();
        Currency currency = transfer.currency();
        BigDecimal amount = transfer.amount();
        double requested = Money.toDouble(amount);
        var request = new RoutedTransfer.Request(sender.getUniqueId(), recipient.getUniqueId(),
                currency.getIdentifier(), requested);
        return run(transfer, request)
                .thenApply(receipt -> settle(transfer, receipt))
                .exceptionally(error -> {
                    logger.error("Transfer of {} {} from {} to {} failed: {}", amount.toPlainString(),
                            currency.getIdentifier(), sender.getName(), recipient.getName(), error.getMessage());
                    return FailureReason.TRANSFER_FAILED.result(requested, 0);
                });
    }

    /**
     * Books a payment that stays in the wallets in one transaction. A payment that can reach an external balance runs
     * leg by leg, because the bank is not part of the economy's database transaction.
     */
    private @NotNull CompletableFuture<RoutedTransfer.Receipt> run(@NotNull Transfer transfer,
                                                                  @NotNull RoutedTransfer.Request request) {
        Player sender = transfer.sender();
        OfflinePlayer recipient = transfer.recipient();
        Currency currency = transfer.currency();
        BigDecimal amount = transfer.amount();
        UUID senderId = sender.getUniqueId();
        if (route.get().walletOnly(currency.getIdentifier()) && !senderId.equals(recipient.getUniqueId())) {
            var booking = new LedgerTransfer(AccountKeys.of(sender, currency), AccountKeys.of(recipient, currency),
                    amount, TRANSFER_TO + recipient.getName(), TRANSFER_FROM + sender.getName(), senderId);
            return transfers.executeInOneStep(request, () -> wallets.transfer(booking));
        }
        return journal.open(request).thenCompose(entry -> runLegs(transfer, request)
                .whenComplete((receipt, failure) -> {
                    if (failure == null && !receipt.lost()) {
                        journal.close(entry);
                    }
                }));
    }

    private @NotNull CompletableFuture<RoutedTransfer.Receipt> runLegs(@NotNull Transfer transfer,
                                                                      @NotNull RoutedTransfer.Request request) {
        Player sender = transfer.sender();
        OfflinePlayer recipient = transfer.recipient();
        Currency currency = transfer.currency();
        BigDecimal amount = transfer.amount();
        UUID senderId = sender.getUniqueId();
        var redirected = new AtomicReference<>(DepositRedirect.Redirected.NONE);
        return transfers.execute(request, new RoutedTransfer.WalletLegs(
                () -> wallets.withdraw(new WalletService.Booking(sender, currency, amount, senderId,
                        TRANSFER_TO + recipient.getName())),
                () -> wallets.depositWithReceipt(new WalletService.Booking(recipient, currency, amount, senderId,
                        TRANSFER_FROM + sender.getName()), ChangeType.DEPOSIT)
                        .thenApply(receipt -> {
                            redirected.set(receipt.redirected());
                            return receipt.result();
                        }),
                () -> wallets.credit(new WalletService.Booking(sender, currency, amount, null,
                        "Rollback: transfer to " + recipient.getName() + " failed"), ChangeType.DEPOSIT)))
                .thenApply(receipt -> {
                    logRedirectedShare(transfer, receipt, redirected.get());
                    return receipt;
                });
    }

    /**
     * Logs the share of a wallet deposit that a redirect moved to an external balance. The ledger only records the part
     * that stayed in the wallet. The bank's own balance is not known here, so the entry shows the share alone.
     */
    private void logRedirectedShare(@NotNull Transfer transfer, @NotNull RoutedTransfer.Receipt receipt,
                                    @NotNull DepositRedirect.Redirected share) {
        if (!receipt.result().isSuccess() || !share.taken()) {
            return;
        }
        OfflinePlayer recipient = transfer.recipient();
        BigDecimal amount = Money.of(BigDecimal.valueOf(share.amount()));
        String holder = route.get().holderName();
        String bank = share.targetId() != null ? " (" + holder + " #" + share.targetId() + ")" : " (" + holder + ")";
        transactionLogger.log(TransactionLog.builder(ChangeType.TRANSFER_IN)
                .player(recipient.getUniqueId(), PlayerNames.storable(recipient.getUniqueId(), recipient.getName()))
                .currency(transfer.currency())
                .balances(Money.ZERO, amount)
                .amount(amount)
                .reason(TRANSFER_FROM + transfer.sender().getName() + bank)
                .build());
    }

    private @NotNull TransactionResult settle(@NotNull Transfer transfer, @NotNull RoutedTransfer.Receipt receipt) {
        TransactionResult result = receipt.result();
        if (receipt.lost()) {
            logger.error("CRITICAL: transfer rollback FAILED - {} {} withdrawn from {} but neither delivered to {} "
                            + "nor refunded. Manual compensation required. error='{}'", result.amount(),
                    transfer.currency().getIdentifier(), transfer.sender().getName(), transfer.recipient().getName(),
                    result.error());
        }
        if (result.isSuccess()) {
            logBankLeg(transfer.sender(), transfer, receipt.withdrawn(), ChangeType.TRANSFER_OUT,
                    TRANSFER_TO + transfer.recipient().getName());
            logBankLeg(transfer.recipient(), transfer, receipt.deposited(), ChangeType.TRANSFER_IN,
                    TRANSFER_FROM + transfer.sender().getName());
        }
        return result;
    }

    private void logBankLeg(@NotNull OfflinePlayer owner, @NotNull Transfer transfer,
                            @Nullable TransferRoute.Leg leg, @NotNull ChangeType type, @NotNull String reason) {
        if (leg == null || leg.outcome() != TransferRoute.Outcome.DONE) {
            return;
        }
        BigDecimal after = Money.of(leg.balance());
        BigDecimal before = type == ChangeType.TRANSFER_OUT
                ? after.add(transfer.amount()) : after.subtract(transfer.amount());
        String name = PlayerNames.storable(owner.getUniqueId(), owner.getName());
        transactionLogger.log(TransactionLog.builder(type)
                .player(owner.getUniqueId(), name)
                .currency(transfer.currency())
                .balances(before, after)
                .amount(transfer.amount())
                .reason(reason + " (" + route.get().holderName() + " #" + leg.bankId() + ")")
                .build());
    }
}
