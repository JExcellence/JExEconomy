package de.jexcellence.economy.database.entity;

import de.jexcellence.economy.money.Money;
import de.jexcellence.jehibernate.entity.base.LongIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One player's balance in an import. The row is unique per source, player and currency, so a player can never be
 * imported twice from the same source; a rolled-back or failed row is reused by a later run.
 *
 * @author JExcellence
 * @since 3.2.0
 */
@Entity
@Table(
        name = "economy_migration_entry",
        indexes = {
                @Index(name = "uk_migration_entry", columnList = "source_id, player_uuid, currency_id", unique = true),
                @Index(name = "ix_migration_entry_run", columnList = "run_id, state")
        }
)
public class MigrationEntry extends LongIdEntity {

    private static final int MONEY_PRECISION = 64;
    private static final int DETAIL_LENGTH = 255;

    /** Where an entry stands. */
    public enum State {
        /** Not handled yet. */
        PENDING,
        /** The balance was written. */
        DONE,
        /** The balance could not be written. */
        FAILED,
        /** The balance from before the run was restored. */
        ROLLED_BACK
    }

    @Column(name = "run_id", nullable = false)
    private long runId;

    @Column(name = "source_id", nullable = false, length = 128)
    private String sourceId;

    @Column(name = "player_uuid", nullable = false, length = 36)
    private String playerUuid;

    @Column(name = "player_name", length = 16)
    private String playerName;

    @Column(name = "currency_id", nullable = false, length = 64)
    private String currencyId;

    @Column(name = "source_balance", nullable = false, precision = MONEY_PRECISION, scale = Money.SCALE)
    private BigDecimal sourceBalance;

    @Column(name = "balance_before", nullable = false, precision = MONEY_PRECISION, scale = Money.SCALE)
    private BigDecimal balanceBefore;

    @Column(name = "balance_expected", nullable = false, precision = MONEY_PRECISION, scale = Money.SCALE)
    private BigDecimal balanceExpected;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private State state;

    @Column(name = "detail", length = DETAIL_LENGTH)
    private String detail;

    /** JPA constructor. */
    protected MigrationEntry() {
    }

    /**
     * Creates an entry for a player seen in a source for the first time.
     *
     * @param sourceId   the source
     * @param player     the player
     * @param currencyId the target currency
     */
    public MigrationEntry(@NotNull String sourceId, @NotNull UUID player, @NotNull String currencyId) {
        this.sourceId = sourceId;
        this.playerUuid = player.toString();
        this.currencyId = currencyId;
    }

    /**
     * Assigns the entry to a run.
     *
     * @param run  the run
     * @param name the player's name, {@code null} when unknown
     * @param from the balance in the source
     * @param before   the balance before the import
     * @param expected the balance after the import
     */
    public void assign(long run, @Nullable String name, @NotNull BigDecimal from, @NotNull BigDecimal before,
                       @NotNull BigDecimal expected) {
        this.runId = run;
        this.playerName = name;
        this.sourceBalance = from;
        this.balanceBefore = before;
        this.balanceExpected = expected;
        this.state = State.PENDING;
        this.detail = null;
    }

    /** Records that the balance was written. */
    public void markDone() {
        this.state = State.DONE;
    }

    /** Records that the balance from before the run was restored. */
    public void markRolledBack() {
        this.state = State.ROLLED_BACK;
    }

    /**
     * Records that the balance could not be written.
     *
     * @param reason why
     */
    public void markFailed(@NotNull String reason) {
        this.state = State.FAILED;
        this.detail = reason.length() > DETAIL_LENGTH ? reason.substring(0, DETAIL_LENGTH) : reason;
    }

    /**
     * The run the entry belongs to.
     *
     * @return the run id
     */
    public long getRunId() {
        return runId;
    }

    /**
     * The player.
     *
     * @return the player's id
     */
    public @NotNull UUID getPlayerUuid() {
        return UUID.fromString(playerUuid);
    }

    /**
     * The player's name.
     *
     * @return the name, {@code null} when unknown
     */
    public @Nullable String getPlayerName() {
        return playerName;
    }

    /**
     * The target currency.
     *
     * @return the currency id
     */
    public @NotNull String getCurrencyId() {
        return currencyId;
    }

    /**
     * The balance in the source.
     *
     * @return the balance
     */
    public @NotNull BigDecimal getSourceBalance() {
        return sourceBalance;
    }

    /**
     * The balance before the import.
     *
     * @return the balance
     */
    public @NotNull BigDecimal getBalanceBefore() {
        return balanceBefore;
    }

    /**
     * The balance after the import.
     *
     * @return the balance
     */
    public @NotNull BigDecimal getBalanceExpected() {
        return balanceExpected;
    }

    /**
     * Where the entry stands.
     *
     * @return the state
     */
    public @NotNull State getState() {
        return state;
    }
}
