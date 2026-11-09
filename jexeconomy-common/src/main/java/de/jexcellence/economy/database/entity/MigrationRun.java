package de.jexcellence.economy.database.entity;

import de.jexcellence.economy.migration.MigrationMode;
import de.jexcellence.economy.migration.MigrationReport;
import de.jexcellence.economy.money.Money;
import de.jexcellence.jehibernate.entity.base.LongIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * One balance import: a plan first, then a run with progress. The row keeps the plan's report.
 *
 * @author JExcellence
 * @since 3.2.0
 */
@Entity
@Table(
        name = "economy_migration_run",
        indexes = @Index(name = "ix_migration_run_state", columnList = "state")
)
public class MigrationRun extends LongIdEntity {

    /** Zone of {@link #getPlannedAt()}; the column stores a local date-time in this zone. */
    public static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private static final int MONEY_PRECISION = 64;

    /** Where a run stands. */
    public enum State {
        /** A dry run; nothing moved. */
        PLANNED,
        /** Balances are being written. */
        RUNNING,
        /** Stopped; can be resumed. */
        PAUSED,
        /** Every entry was handled. */
        DONE,
        /** A rollback started and has not finished. It can only be repeated. */
        ROLLING_BACK,
        /** The balances from before the run were restored. */
        ROLLED_BACK
    }

    @Column(name = "source_id", nullable = false, length = 128)
    private String sourceId;

    @Column(name = "currency_id", nullable = false, length = 64)
    private String currencyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 16)
    private MigrationMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private State state;

    @Column(name = "planned_at", nullable = false)
    private LocalDateTime plannedAt;

    @Column(name = "players", nullable = false)
    private int players;

    @Column(name = "import_total", nullable = false, precision = MONEY_PRECISION, scale = Money.SCALE)
    private BigDecimal importTotal;

    @Column(name = "imported", nullable = false)
    private int imported;

    @Column(name = "failed", nullable = false)
    private int failed;

    /** JPA constructor. */
    protected MigrationRun() {
    }

    /**
     * Records a finished dry run.
     *
     * @param sourceId   the source
     * @param currencyId the target currency
     * @param mode       the import mode
     * @param report     the dry run's report
     */
    public MigrationRun(@NotNull String sourceId, @NotNull String currencyId, @NotNull MigrationMode mode,
                        @NotNull MigrationReport report) {
        this.sourceId = sourceId;
        this.currencyId = currencyId;
        this.mode = mode;
        this.state = State.PLANNED;
        this.plannedAt = LocalDateTime.now(ZONE);
        this.players = report.players();
        this.importTotal = report.importTotal();
    }

    /**
     * Stores the progress.
     *
     * @param newState     the state
     * @param importedRows the entries imported so far
     * @param failedRows   the entries that failed so far
     */
    public void progress(@NotNull State newState, int importedRows, int failedRows) {
        this.state = newState;
        this.imported = importedRows;
        this.failed = failedRows;
    }

    /**
     * The source.
     *
     * @return the source id
     */
    public @NotNull String getSourceId() {
        return sourceId;
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
     * The import mode.
     *
     * @return the mode
     */
    public @NotNull MigrationMode getMode() {
        return mode;
    }

    /**
     * Where the run stands.
     *
     * @return the state
     */
    public @NotNull State getState() {
        return state;
    }

    /**
     * When the dry run finished, in Europe/Berlin.
     *
     * @return the time
     */
    public @NotNull LocalDateTime getPlannedAt() {
        return plannedAt;
    }

    /**
     * The players the dry run would import.
     *
     * @return the count
     */
    public int getPlayers() {
        return players;
    }

    /**
     * The entries imported so far.
     *
     * @return the count
     */
    public int getImported() {
        return imported;
    }

    /**
     * The entries that failed so far.
     *
     * @return the count
     */
    public int getFailed() {
        return failed;
    }
}
