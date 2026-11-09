package de.jexcellence.economy.service;

import de.jexcellence.economy.database.entity.OpenTransfer;
import de.jexcellence.economy.database.repository.OpenTransferRepository;
import de.jexcellence.jexplatform.logging.JExLogger;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A finished payment does not stay behind because of one failed delete, and a cut-off payment is reported once.
 */
class JpaTransferJournalTest {

    private static final long ENTRY = 9L;
    private static final long RETRY_WAIT_MS = 5_000L;

    private final OpenTransferRepository repository = mock(OpenTransferRepository.class);
    private final JpaTransferJournal journal = new JpaTransferJournal(repository,
            JExLogger.of("JpaTransferJournalTest"), Runnable::run);

    @Test
    void aFailedDeleteIsTriedOnceMoreAfterAShortDelay() {
        when(repository.deleteAsync(ENTRY))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("connection lost")))
                .thenReturn(CompletableFuture.completedFuture(null));

        journal.close(ENTRY);

        verify(repository, times(1)).deleteAsync(ENTRY);
        verify(repository, timeout(RETRY_WAIT_MS).times(2)).deleteAsync(ENTRY);
    }

    @Test
    void aSuccessfulDeleteIsNotRepeated() {
        when(repository.deleteAsync(ENTRY)).thenReturn(CompletableFuture.completedFuture(null));

        journal.close(ENTRY);

        verify(repository, times(1)).deleteAsync(ENTRY);
    }

    @Test
    void aCutOffPaymentIsMarkedReported() {
        var row = new OpenTransfer(UUID.randomUUID(), UUID.randomUUID(), "coins", new BigDecimal("5.00"));
        when(repository.findUnreportedAsync(any())).thenReturn(CompletableFuture.completedFuture(List.of(row)));

        journal.reportInterrupted();

        verify(repository).updateAsync(row);
    }
}
