package de.jexcellence.economy.service.ledger;

import de.jexcellence.economy.api.ChangeType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * One balance change submitted to the ledger.
 *
 * @param key        the account
 * @param operation  what happens to the balance
 * @param changeType the recorded type of a delta; an absolute set picks deposit or withdraw itself
 * @param reason     the human-readable reason, may be {@code null}
 * @param initiator  who started the change, {@code null} for the server
 * @param skipsPreEvent the caller waits for this change on a server thread and cannot wait a tick for listeners
 * @author JExcellence
 * @since 3.1.0
 */
public record LedgerRequest(@NotNull AccountKey key,
                            @NotNull BalanceOperation operation,
                            @NotNull ChangeType changeType,
                            @Nullable String reason,
                            @Nullable UUID initiator,
                            boolean skipsPreEvent) {

    /**
     * Creates a request whose pre-event listeners are asked.
     *
     * @param key        the account
     * @param operation  what happens to the balance
     * @param changeType the recorded type of a delta
     * @param reason     the human-readable reason, may be {@code null}
     * @param initiator  who started the change, {@code null} for the server
     */
    public LedgerRequest(@NotNull AccountKey key, @NotNull BalanceOperation operation, @NotNull ChangeType changeType,
                         @Nullable String reason, @Nullable UUID initiator) {
        this(key, operation, changeType, reason, initiator, false);
    }
}
