package de.jexcellence.economy.database.entity;

import de.jexcellence.economy.money.Money;
import de.jexcellence.jehibernate.entity.base.LongIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * A leg-by-leg payment that has started. The row is removed when the payment ends, so a row that stays behind names a
 * payment a crash cut off, whose legs may be only partly booked. It stays as the record after it has been reported.
 *
 * @author JExcellence
 * @since 3.2.0
 */
@Entity
@Table(
        name = "economy_open_transfer",
        indexes = @Index(name = "ix_open_transfer_reported", columnList = "reported_at")
)
public class OpenTransfer extends LongIdEntity {

    /** Zone of {@link #getOpenedAt()}; the column stores a local date-time in this zone. */
    public static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private static final int AMOUNT_PRECISION = 38;

    @Column(name = "sender_uuid", nullable = false, length = 36)
    private String senderUuid;

    @Column(name = "recipient_uuid", nullable = false, length = 36)
    private String recipientUuid;

    @Column(name = "currency_id", nullable = false, length = 64)
    private String currencyId;

    @Column(name = "amount", nullable = false, precision = AMOUNT_PRECISION, scale = Money.SCALE)
    private BigDecimal amount;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    @Column(name = "reported_at")
    private LocalDateTime reportedAt;

    /** JPA constructor. */
    protected OpenTransfer() {
    }

    /**
     * Records a payment that is about to start.
     *
     * @param sender     the paying player
     * @param recipient  the receiving player
     * @param currencyId the currency
     * @param amount     the amount
     */
    public OpenTransfer(@NotNull UUID sender, @NotNull UUID recipient, @NotNull String currencyId,
                        @NotNull BigDecimal amount) {
        this.senderUuid = sender.toString();
        this.recipientUuid = recipient.toString();
        this.currencyId = currencyId;
        this.amount = amount;
        this.openedAt = LocalDateTime.now(ZONE);
    }

    /**
     * The paying player.
     *
     * @return the player's id
     */
    public @NotNull UUID getSenderUuid() {
        return UUID.fromString(senderUuid);
    }

    /**
     * The receiving player.
     *
     * @return the player's id
     */
    public @NotNull UUID getRecipientUuid() {
        return UUID.fromString(recipientUuid);
    }

    /**
     * The currency.
     *
     * @return the currency identifier
     */
    public @NotNull String getCurrencyId() {
        return currencyId;
    }

    /**
     * The amount.
     *
     * @return the amount
     */
    public @NotNull BigDecimal getAmount() {
        return amount;
    }

    /**
     * When the payment started, in Europe/Berlin.
     *
     * @return the time
     */
    public @NotNull LocalDateTime getOpenedAt() {
        return openedAt;
    }

    /** Marks the row as reported to the server log, so a later start does not report it again. */
    public void markReported() {
        this.reportedAt = LocalDateTime.now(ZONE);
    }
}
