package de.jexcellence.economy.database.entity;

import de.jexcellence.economy.api.ChangeType;
import de.jexcellence.economy.money.Money;
import de.jexcellence.economy.type.LogLevel;
import de.jexcellence.economy.type.LogType;
import de.jexcellence.jehibernate.entity.base.LongIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Audit record for a balance change or economy operation. Rows are written once through {@link #builder(ChangeType)}
 * and never changed afterwards. The currency loads lazily; repository queries that feed the log views fetch it.
 *
 * @author JExcellence
 * @since 3.0.0
 */
@Entity
@Table(name = "economy_transaction_log", indexes = {
        @Index(name = "idx_txlog_timestamp", columnList = "timestamp"),
        @Index(name = "idx_txlog_player", columnList = "player_uuid"),
        @Index(name = "idx_txlog_player_time", columnList = "player_uuid, timestamp"),
        @Index(name = "idx_txlog_currency", columnList = "currency_id"),
        @Index(name = "idx_txlog_type", columnList = "change_type")
})
public class TransactionLog extends LongIdEntity {

    private static final int MONEY_PRECISION = 64;
    private static final int PLAYER_NAME_LENGTH = 16;

    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    @Column(name = "player_uuid")
    private UUID playerUuid;

    @Column(name = "player_name", length = PLAYER_NAME_LENGTH)
    private String playerName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "currency_id")
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, columnDefinition = "varchar(16)")
    private ChangeType changeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "log_type", nullable = false, length = 16)
    private LogType logType;

    @Enumerated(EnumType.STRING)
    @Column(name = "log_level", nullable = false, length = 16)
    private LogLevel logLevel;

    @Column(name = "old_balance", precision = MONEY_PRECISION, scale = Money.SCALE)
    private BigDecimal oldBalance;

    @Column(name = "new_balance", precision = MONEY_PRECISION, scale = Money.SCALE)
    private BigDecimal newBalance;

    @Column(name = "amount", precision = MONEY_PRECISION, scale = Money.SCALE)
    private BigDecimal amount;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "initiator_uuid")
    private UUID initiatorUuid;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    /** JPA constructor. */
    protected TransactionLog() {
    }

    private TransactionLog(@NotNull Builder builder) {
        this.timestamp = Instant.now();
        this.playerUuid = builder.playerUuid;
        this.playerName = clampName(builder.playerName);
        this.currency = builder.currency;
        this.changeType = builder.changeType;
        this.logType = LogType.TRANSACTION;
        this.logLevel = builder.errorMessage == null ? LogLevel.INFO : LogLevel.ERROR;
        this.oldBalance = Money.of(builder.oldBalance);
        this.newBalance = Money.of(builder.newBalance);
        this.amount = Money.of(builder.amount);
        this.reason = builder.reason;
        this.initiatorUuid = builder.initiatorUuid;
        this.success = builder.errorMessage == null;
        this.errorMessage = builder.errorMessage;
    }

    /**
     * Starts a log entry.
     *
     * @param changeType the type of change
     * @return the builder
     */
    public static @NotNull Builder builder(@NotNull ChangeType changeType) {
        return new Builder(changeType);
    }

    private static @Nullable String clampName(@Nullable String name) {
        return name != null && name.length() > PLAYER_NAME_LENGTH ? name.substring(0, PLAYER_NAME_LENGTH) : name;
    }

    /**
     * Returns the timestamp.
     *
     * @return the timestamp
     */
    public @NotNull Instant getTimestamp() {
        return timestamp;
    }

    /**
     * Returns the player UUID.
     *
     * @return the player UUID, or null
     */
    public @Nullable UUID getPlayerUuid() {
        return playerUuid;
    }

    /**
     * Returns the player name.
     *
     * @return the player name, or null
     */
    public @Nullable String getPlayerName() {
        return playerName;
    }

    /**
     * Returns the currency. Only usable when the loading query fetched it.
     *
     * @return the currency, or null
     */
    public @Nullable Currency getCurrency() {
        return currency;
    }

    /**
     * Returns the change type.
     *
     * @return the change type
     */
    public @NotNull ChangeType getChangeType() {
        return changeType;
    }

    /**
     * Returns the log type.
     *
     * @return the log type
     */
    public @NotNull LogType getLogType() {
        return logType;
    }

    /**
     * Returns the log level.
     *
     * @return the log level
     */
    public @NotNull LogLevel getLogLevel() {
        return logLevel;
    }

    /**
     * Returns the balance before the operation.
     *
     * @return the old balance
     */
    public @NotNull BigDecimal getOldBalance() {
        return Money.of(oldBalance);
    }

    /**
     * Returns the balance after the operation.
     *
     * @return the new balance
     */
    public @NotNull BigDecimal getNewBalance() {
        return Money.of(newBalance);
    }

    /**
     * Returns the amount involved.
     *
     * @return the amount
     */
    public @NotNull BigDecimal getAmount() {
        return Money.of(amount);
    }

    /**
     * Returns the reason for the operation.
     *
     * @return the reason, or null
     */
    public @Nullable String getReason() {
        return reason;
    }

    /**
     * Returns the initiator's UUID.
     *
     * @return the initiator UUID, or null
     */
    public @Nullable UUID getInitiatorUuid() {
        return initiatorUuid;
    }

    /**
     * Returns whether the operation succeeded.
     *
     * @return true if successful
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Returns the error message, if any.
     *
     * @return the error message, or null
     */
    public @Nullable String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Returns the net balance change (new - old).
     *
     * @return the change amount
     */
    public @NotNull BigDecimal getChangeAmount() {
        return getNewBalance().subtract(getOldBalance());
    }

    @Override
    public String toString() {
        return "TransactionLog[" + changeType + " " + amount + " " + playerName + "]";
    }

    /** Collects the values of one log entry. */
    public static final class Builder {

        private final ChangeType changeType;
        private UUID playerUuid;
        private String playerName;
        private Currency currency;
        private BigDecimal oldBalance = Money.ZERO;
        private BigDecimal newBalance = Money.ZERO;
        private BigDecimal amount = Money.ZERO;
        private String reason;
        private UUID initiatorUuid;
        private String errorMessage;

        private Builder(@NotNull ChangeType changeType) {
            this.changeType = changeType;
        }

        /**
         * Sets the player the entry belongs to.
         *
         * @param uuid the player UUID
         * @param name the player name, cut to 16 characters
         * @return this builder
         */
        public @NotNull Builder player(@Nullable UUID uuid, @Nullable String name) {
            this.playerUuid = uuid;
            this.playerName = name;
            return this;
        }

        /**
         * Sets the currency.
         *
         * @param currency the currency
         * @return this builder
         */
        public @NotNull Builder currency(@Nullable Currency currency) {
            this.currency = currency;
            return this;
        }

        /**
         * Sets the balances before and after the change.
         *
         * @param before the balance before
         * @param after  the balance after
         * @return this builder
         */
        public @NotNull Builder balances(@NotNull BigDecimal before, @NotNull BigDecimal after) {
            this.oldBalance = before;
            this.newBalance = after;
            return this;
        }

        /**
         * Sets the moved amount.
         *
         * @param amount the amount
         * @return this builder
         */
        public @NotNull Builder amount(@NotNull BigDecimal amount) {
            this.amount = amount;
            return this;
        }

        /**
         * Sets the reason.
         *
         * @param reason the reason
         * @return this builder
         */
        public @NotNull Builder reason(@Nullable String reason) {
            this.reason = reason;
            return this;
        }

        /**
         * Sets who started the change.
         *
         * @param initiator the initiator UUID
         * @return this builder
         */
        public @NotNull Builder initiator(@Nullable UUID initiator) {
            this.initiatorUuid = initiator;
            return this;
        }

        /**
         * Marks the entry as failed.
         *
         * @param error the error message
         * @return this builder
         */
        public @NotNull Builder failed(@NotNull String error) {
            this.errorMessage = error;
            return this;
        }

        /**
         * Builds the entry.
         *
         * @return the log entry
         */
        public @NotNull TransactionLog build() {
            return new TransactionLog(this);
        }
    }
}
