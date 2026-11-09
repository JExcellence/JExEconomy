package de.jexcellence.economy.service;

import de.jexcellence.economy.api.CurrencySnapshot;
import de.jexcellence.economy.api.event.BalanceChangeEvent;
import de.jexcellence.economy.api.event.BalanceChangedEvent;
import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.database.entity.TransactionLog;
import de.jexcellence.economy.money.Money;
import de.jexcellence.economy.service.currency.CurrencyRegistry;
import de.jexcellence.economy.service.ledger.BalanceChange;
import de.jexcellence.economy.service.ledger.LedgerHooks;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Ledger hooks of the plugin: a {@link BalanceChangeEvent} that listeners may cancel before the write, then a
 * transaction log entry and a {@link BalanceChangedEvent} after it. The log row is written after the balance
 * transaction, so a crash in that moment can leave a booked change without its log row.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public final class BalanceChangeNotifier implements LedgerHooks {

    private final EconomyEvents events;
    private final TransactionLogger transactionLogger;
    private final CurrencyRegistry currencies;
    private final OnlineBalances onlineBalances;

    /**
     * Creates the notifier.
     *
     * @param events            the event dispatcher
     * @param transactionLogger the transaction log writer
     * @param currencies        the currency registry
     * @param onlineBalances    the cached balances of online players
     */
    public BalanceChangeNotifier(@NotNull EconomyEvents events, @NotNull TransactionLogger transactionLogger,
                                 @NotNull CurrencyRegistry currencies, @NotNull OnlineBalances onlineBalances) {
        this.events = events;
        this.transactionLogger = transactionLogger;
        this.currencies = currencies;
        this.onlineBalances = onlineBalances;
    }

    @Override
    public boolean vetoRequired() {
        return BalanceChangeEvent.getHandlerList().getRegisteredListeners().length > 0;
    }

    @Override
    public @NotNull Optional<String> veto(@NotNull BalanceChange change, @NotNull BooleanSupplier dispatchBlocked) {
        var event = new BalanceChangeEvent(change.key().playerId(), change.key().playerName(), snapshot(change),
                Money.toDouble(change.oldBalance()), Money.toDouble(change.newBalance()), change.changeType(),
                change.reason(), change.initiator());
        if (!events.fireCancellable(event, dispatchBlocked)) {
            return Optional.empty();
        }
        String reason = event.getCancellationReason();
        return Optional.of(reason != null ? reason : FailureReason.CANCELLED.text());
    }

    @Override
    public void committed(@NotNull BalanceChange change) {
        onlineBalances.committed(change.key().playerId(), change.key().currencyId(), change.newBalance());
        transactionLogger.log(TransactionLog.builder(change.changeType())
                .player(change.key().playerId(), change.key().playerName())
                .currency(currency(change))
                .balances(change.oldBalance(), change.newBalance())
                .amount(change.amount())
                .reason(change.reason())
                .initiator(change.initiator())
                .build());
        events.fire(new BalanceChangedEvent(change.key().playerId(), change.key().playerName(), snapshot(change),
                Money.toDouble(change.oldBalance()), Money.toDouble(change.newBalance()), change.changeType(),
                change.reason(), change.initiator()));
    }

    private @NotNull CurrencySnapshot snapshot(@NotNull BalanceChange change) {
        return currency(change).toSnapshot();
    }

    private @NotNull Currency currency(@NotNull BalanceChange change) {
        return currencies.find(change.key().currencyId())
                .orElseThrow(() -> new IllegalStateException("Unknown currency " + change.key().currencyId()));
    }
}
