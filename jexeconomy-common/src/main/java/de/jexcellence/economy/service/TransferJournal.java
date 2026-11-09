package de.jexcellence.economy.service;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * The durable note of a payment that books its legs separately. A payment that can reach an external balance cannot be one
 * database transaction, because the bank belongs to another plugin. A crash between its legs must at least leave a
 * record of which payment to check.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public interface TransferJournal {

    /** Keeps no record. */
    TransferJournal NONE = new TransferJournal() {
        @Override
        public @NotNull CompletableFuture<Long> open(@NotNull RoutedTransfer.Request request) {
            return CompletableFuture.completedFuture(0L);
        }

        @Override
        public void close(long entry) {
            // Nothing was recorded, so there is nothing to remove.
        }

        @Override
        public void reportInterrupted() {
            // Nothing was recorded, so there is nothing to report.
        }
    };

    /**
     * Records a payment before its first leg runs. A failed future means the payment must not start.
     *
     * @param request the payment
     * @return future resolving to the entry to close
     */
    @NotNull CompletableFuture<Long> open(@NotNull RoutedTransfer.Request request);

    /**
     * Removes the record of a payment that ended, whether it went through or was given back.
     *
     * @param entry the entry from {@link #open}
     */
    void close(long entry);

    /** Writes every payment a crash cut off to the server log, once each. */
    void reportInterrupted();
}
