package de.jexcellence.economy.service.currency;

import de.jexcellence.economy.api.ChangeType;
import de.jexcellence.economy.api.TransactionResult;
import de.jexcellence.economy.api.event.CurrencyCreateEvent;
import de.jexcellence.economy.api.event.CurrencyCreatedEvent;
import de.jexcellence.economy.api.event.CurrencyDeleteEvent;
import de.jexcellence.economy.api.event.CurrencyDeletedEvent;
import de.jexcellence.economy.api.event.CurrencyResetEvent;
import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.database.repository.AccountRepository;
import de.jexcellence.economy.database.repository.CurrencyRepository;
import de.jexcellence.economy.money.Money;
import de.jexcellence.economy.service.EconomyEvents;
import de.jexcellence.economy.service.FailureReason;
import de.jexcellence.economy.service.ledger.AccountKey;
import de.jexcellence.economy.service.ledger.BalanceLedger;
import de.jexcellence.economy.service.ledger.BalanceOperation;
import de.jexcellence.economy.service.ledger.LedgerRequest;
import de.jexcellence.jexplatform.logging.JExLogger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Creating, editing, deleting and resetting currencies, with their pre- and post-events.
 *
 * <p>Edits work on a copy of the cached currency; the cache only receives the saved copy (with its new row version),
 * so a failed update leaves the cache as it was. A deletion removes the currency's transaction logs and accounts in
 * the same transaction as the currency row, so it can no longer fail on the foreign keys after its pre-event fired;
 * the post-event fires only when the transaction committed.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public final class CurrencyLifecycle {

    private static final String DEFAULT_RESET_REASON = "Currency reset";
    private static final int RESET_BATCH = 100;

    private final CurrencyRegistry registry;
    private final CurrencyRepository currencies;
    private final AccountRepository accounts;
    private final BalanceLedger ledger;
    private final EconomyEvents events;
    private final JExLogger logger;
    private final Executor executor;

    /** Repositories the lifecycle writes to. */
    public record Stores(@NotNull CurrencyRepository currencies, @NotNull AccountRepository accounts) {
    }

    /**
     * Creates the lifecycle.
     *
     * @param registry the currency registry
     * @param stores   the currency and account repositories
     * @param ledger   the balance ledger, for resets
     * @param events   the event dispatcher
     * @param logger   the plugin logger
     * @param executor runs the blocking work off the main thread
     */
    public CurrencyLifecycle(@NotNull CurrencyRegistry registry, @NotNull Stores stores, @NotNull BalanceLedger ledger,
                             @NotNull EconomyEvents events, @NotNull JExLogger logger, @NotNull Executor executor) {
        this.registry = registry;
        this.currencies = stores.currencies();
        this.accounts = stores.accounts();
        this.ledger = ledger;
        this.events = events;
        this.logger = logger;
        this.executor = executor;
    }

    /**
     * Creates a currency after its {@link CurrencyCreateEvent} passed.
     *
     * @param currency the new currency
     * @param creator  who creates it, {@code null} for the server
     * @return future resolving to {@code true} when it was created
     */
    public @NotNull CompletableFuture<Boolean> create(@NotNull Currency currency, @Nullable UUID creator) {
        if (ReservedCurrencyNames.isReserved(currency.getIdentifier())) {
            logger.warn("Currency creation refused for '{}': reserved name", currency.getIdentifier());
            return CompletableFuture.completedFuture(false);
        }
        return CompletableFuture.supplyAsync(() -> {
            var preEvent = new CurrencyCreateEvent(currency.toSnapshot(), creator);
            if (events.fireCancellable(preEvent)) {
                logger.warn("Currency creation cancelled for '{}': {}", currency.getIdentifier(),
                        preEvent.getCancellationReason());
                return false;
            }
            var saved = currencies.create(currency);
            registry.put(saved);
            events.fire(new CurrencyCreatedEvent(saved.toSnapshot(), creator));
            logger.info("Currency '{}' created", saved.getIdentifier());
            return true;
        }, executor);
    }

    /**
     * Applies the edited fields to a copy of the cached currency, saves it and caches the saved copy.
     *
     * @param originalIdentifier the identifier before the edit
     * @param updated            value holder with the new fields; blank identifier, symbol and icon keep the old
     * @return future resolving to {@code true} when the edit was saved
     */
    public @NotNull CompletableFuture<Boolean> update(@NotNull String originalIdentifier, @NotNull Currency updated) {
        if (renamesToReserved(originalIdentifier, updated)) {
            logger.warn("Currency update refused for '{}': reserved name '{}'", originalIdentifier,
                    updated.getIdentifier());
            return CompletableFuture.completedFuture(false);
        }
        return CompletableFuture.supplyAsync(() -> {
            var cached = registry.find(originalIdentifier);
            if (cached.isEmpty()) {
                logger.warn("Cannot update unknown currency '{}'", originalIdentifier);
                return false;
            }
            var saved = currencies.update(edited(cached.get(), updated));
            registry.replace(originalIdentifier, saved);
            logger.info("Currency '{}' updated (was '{}')", saved.getIdentifier(), originalIdentifier);
            return true;
        }, executor);
    }

    private static boolean renamesToReserved(@NotNull String originalIdentifier, @NotNull Currency updated) {
        String identifier = updated.getIdentifier();
        return !identifier.isEmpty() && !identifier.equalsIgnoreCase(originalIdentifier)
                && ReservedCurrencyNames.isReserved(identifier);
    }

    private static @NotNull Currency edited(@NotNull Currency cached, @NotNull Currency updated) {
        var copy = cached.copy();
        if (!updated.getIdentifier().isEmpty()) {
            copy.setIdentifier(updated.getIdentifier());
        }
        if (!updated.getSymbol().isEmpty()) {
            copy.setSymbol(updated.getSymbol());
        }
        copy.setPrefix(updated.getPrefix());
        copy.setSuffix(updated.getSuffix());
        if (!updated.getIcon().isEmpty()) {
            copy.setIcon(updated.getIcon());
        }
        return copy;
    }

    /**
     * Deletes a currency with all its accounts and transaction logs after its {@link CurrencyDeleteEvent} passed.
     *
     * @param identifier the identifier
     * @param deleter    who deletes it, {@code null} for the server
     * @return future resolving to {@code true} when it was deleted
     */
    public @NotNull CompletableFuture<Boolean> delete(@NotNull String identifier, @Nullable UUID deleter) {
        return CompletableFuture.supplyAsync(() -> {
            var cached = registry.find(identifier);
            if (cached.isEmpty()) {
                logger.warn("Cannot delete unknown currency '{}'", identifier);
                return false;
            }
            var currency = cached.get();
            int affected = (int) accounts.countByCurrency(currency);
            double total = Money.toDouble(accounts.sumByCurrency(currency));
            var snapshot = currency.toSnapshot();
            var preEvent = new CurrencyDeleteEvent(snapshot, deleter, affected, total);
            if (events.fireCancellable(preEvent)) {
                logger.warn("Currency deletion cancelled for '{}': {}", identifier, preEvent.getCancellationReason());
                return false;
            }
            var purged = currencies.purge(currency.getId());
            registry.remove(identifier);
            events.fire(new CurrencyDeletedEvent(snapshot, deleter, affected, total));
            logger.info("Currency '{}' deleted ({} accounts, {} log entries)", identifier, purged.accounts(),
                    purged.logs());
            return true;
        }, executor);
    }

    /**
     * Sets every positive account of a resettable currency to zero through the ledger, one logged change per
     * account, in batches of 100 accounts, then fires a {@link CurrencyResetEvent} with the accounts
     * that were really reset and the amount really removed. Failed accounts are logged and keep their balance.
     *
     * @param identifier the identifier
     * @param reason     the logged reason, {@code null} for a default
     * @return future resolving to the number of accounts that were reset, {@code -1} when refused
     */
    public @NotNull CompletableFuture<Integer> reset(@NotNull String identifier, @Nullable String reason) {
        var cached = registry.find(identifier);
        if (cached.isEmpty() || !registry.policy(identifier).resettable()) {
            logger.warn("Refused reset of currency '{}': unknown or not resettable", identifier);
            return CompletableFuture.completedFuture(-1);
        }
        var currency = cached.get();
        String resolvedReason = reason != null ? reason : DEFAULT_RESET_REASON;
        return accounts.findHoldersAsync(currency).thenCompose(holders -> {
            ResetTally tally = new ResetTally();
            CompletableFuture<Void> batches = CompletableFuture.completedFuture(null);
            for (int start = 0; start < holders.size(); start += RESET_BATCH) {
                List<AccountRepository.Holder> batch = holders.subList(start,
                        Math.min(holders.size(), start + RESET_BATCH));
                batches = batches.thenCompose(previous -> resetBatch(batch, currency, resolvedReason, tally));
            }
            return batches.thenApply(done -> finishReset(currency, resolvedReason, holders.size(), tally));
        });
    }

    private @NotNull CompletableFuture<Void> resetBatch(@NotNull List<AccountRepository.Holder> batch,
                                                        @NotNull Currency currency, @NotNull String reason,
                                                        @NotNull ResetTally tally) {
        List<CompletableFuture<Void>> resets = batch.stream()
                .map(holder -> zero(holder, currency, reason).thenAccept(tally::record))
                .toList();
        return CompletableFuture.allOf(resets.toArray(CompletableFuture[]::new));
    }

    private int finishReset(@NotNull Currency currency, @NotNull String reason, int holders,
                            @NotNull ResetTally tally) {
        int applied = tally.applied();
        BigDecimal removed = tally.removed();
        events.fire(new CurrencyResetEvent(currency.toSnapshot(), applied, Money.toDouble(removed), reason));
        if (applied < holders) {
            logger.warn("Reset currency '{}': {} of {} account(s) reset, {} removed; the others keep their balance",
                    currency.getIdentifier(), applied, holders, removed.toPlainString());
        } else {
            logger.info("Reset currency '{}': {} account(s), {} removed", currency.getIdentifier(), applied,
                    removed.toPlainString());
        }
        return applied;
    }

    /** Counts the accounts a reset really changed; updated from several ledger workers. */
    private static final class ResetTally {

        private final AtomicInteger applied = new AtomicInteger();
        private final AtomicReference<BigDecimal> removed = new AtomicReference<>(Money.ZERO);

        void record(@NotNull TransactionResult result) {
            if (result.isSuccess()) {
                applied.incrementAndGet();
                removed.accumulateAndGet(Money.of(Math.abs(result.amount())), BigDecimal::add);
            }
        }

        int applied() {
            return applied.get();
        }

        @NotNull BigDecimal removed() {
            return removed.get();
        }
    }

    private @NotNull CompletableFuture<TransactionResult> zero(@NotNull AccountRepository.Holder holder,
                                                               @NotNull Currency currency, @NotNull String reason) {
        var key = new AccountKey(holder.playerUuid(), holder.playerName(), currency.getIdentifier());
        return ledger.submit(new LedgerRequest(key, BalanceOperation.absolute(Money.ZERO), ChangeType.WITHDRAW,
                        reason, null))
                .exceptionally(failure -> {
                    logger.error("Failed to reset '{}' for {}: {}", currency.getIdentifier(), holder.playerUuid(),
                            failure.getMessage());
                    double balance = Money.toDouble(holder.balance());
                    return FailureReason.RESET_FAILED.result(balance, balance);
                });
    }
}
