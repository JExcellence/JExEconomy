package de.jexcellence.economy.service.ledger;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A payment between two wallets, booked by the ledger in one database transaction.
 *
 * @param from       the sender's account
 * @param to         the recipient's account, a different account than {@code from}
 * @param amount     the positive amount
 * @param fromReason the logged reason on the sender's side
 * @param toReason   the logged reason on the recipient's side
 * @param initiator  who started the transfer, {@code null} for the server
 * @author JExcellence
 * @since 3.2.0
 */
public record LedgerTransfer(@NotNull AccountKey from,
                             @NotNull AccountKey to,
                             @NotNull BigDecimal amount,
                             @Nullable String fromReason,
                             @Nullable String toReason,
                             @Nullable UUID initiator) {
}
