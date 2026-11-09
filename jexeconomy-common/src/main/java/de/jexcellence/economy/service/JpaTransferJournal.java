package de.jexcellence.economy.service;

import de.jexcellence.economy.database.entity.OpenTransfer;
import de.jexcellence.economy.database.repository.OpenTransferRepository;
import de.jexcellence.economy.money.Money;
import de.jexcellence.jexplatform.logging.JExLogger;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the {@link TransferJournal} in {@code economy_open_transfer}. Only rows older than {@link #REPORT_AFTER} are
 * reported, so a payment still running on another server that shares the database is left alone.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public final class JpaTransferJournal implements TransferJournal {

    static final Duration REPORT_AFTER = Duration.ofMinutes(1L);

    private static final long DELETE_RETRY_SECONDS = 1L;

    private final OpenTransferRepository repository;
    private final JExLogger logger;
    private final Executor executor;

    /**
     * Creates the journal.
     *
     * @param repository the rows
     * @param logger     the plugin logger
     * @param executor   starts the delayed retry of a failed removal
     */
    public JpaTransferJournal(@NotNull OpenTransferRepository repository, @NotNull JExLogger logger,
                              @NotNull Executor executor) {
        this.repository = repository;
        this.logger = logger;
        this.executor = executor;
    }

    @Override
    public @NotNull CompletableFuture<Long> open(@NotNull RoutedTransfer.Request request) {
        var row = new OpenTransfer(request.sender(), request.recipient(), request.currencyId(),
                Money.of(request.amount()));
        return repository.createAsync(row).thenApply(OpenTransfer::getId);
    }

    @Override
    public void close(long entry) {
        repository.deleteAsync(entry).exceptionallyComposeAsync(firstFailure -> repository.deleteAsync(entry),
                CompletableFuture.delayedExecutor(DELETE_RETRY_SECONDS, TimeUnit.SECONDS, executor));
    }

    @Override
    public void reportInterrupted() {
        repository.findUnreportedAsync(LocalDateTime.now(OpenTransfer.ZONE).minus(REPORT_AFTER))
                .thenAccept(rows -> rows.forEach(this::report))
                .exceptionally(failure -> {
                    logger.warn("Interrupted payments could not be reported: {}", String.valueOf(failure.getMessage()));
                    return null;
                });
    }

    private void report(@NotNull OpenTransfer row) {
        logger.error("Payment of {} {} from {} to {}, started {}, was cut off. Check both balances by hand.",
                row.getAmount().toPlainString(), row.getCurrencyId(), row.getSenderUuid(), row.getRecipientUuid(),
                row.getOpenedAt());
        row.markReported();
        repository.updateAsync(row);
    }
}
