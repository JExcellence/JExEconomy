package de.jexcellence.economy.service.ledger;

import de.jexcellence.economy.api.ChangeType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One balance change the ledger is about to write or has written.
 *
 * @param key        the account
 * @param oldBalance the balance before
 * @param newBalance the balance after
 * @param amount     the moved amount, never negative
 * @param changeType the kind of change
 * @param reason     the human-readable reason, may be {@code null}
 * @param initiator  who started the change, {@code null} for the server
 * @author JExcellence
 * @since 3.1.0
 */
public record BalanceChange(@NotNull AccountKey key,
                            @NotNull BigDecimal oldBalance,
                            @NotNull BigDecimal newBalance,
                            @NotNull BigDecimal amount,
                            @NotNull ChangeType changeType,
                            @Nullable String reason,
                            @Nullable UUID initiator) {
}
