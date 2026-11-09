package de.jexcellence.economy.database.maintenance;

import de.jexcellence.economy.database.UniqueIndexes;
import org.jetbrains.annotations.NotNull;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Guarantees the unique indexes of the economy tables on existing installations. The schema update leaves unique
 * constraints of existing tables alone (strategy {@code SKIP}), so each start checks the JDBC metadata and creates a
 * missing index. Rows that would block it are handled per {@link Duplicates}: redemption rows of the same coupon are
 * reduced to the oldest one (the payouts stay, only the duplicate redemption rows go, each logged), every other table
 * only reports its duplicates and keeps running without the index.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public final class UniqueIndexGuard {

    /** What happens to rows that share the unique key. */
    public enum Duplicates {
        /** Log the groups and leave the index missing. */
        REPORT,
        /** Keep the row with the lowest id, delete and log the others. */
        KEEP_OLDEST
    }

    /**
     * One unique index.
     *
     * @param table      the table
     * @param columns    the key columns, in index order
     * @param indexName  the name of an index created here
     * @param duplicates how blocking rows are handled
     */
    public record Requirement(@NotNull String table, @NotNull List<String> columns, @NotNull String indexName,
                              @NotNull Duplicates duplicates) {
    }

    /** Outcome for one requirement. */
    public enum Outcome {
        PRESENT,
        CREATED,
        BLOCKED_BY_DUPLICATES,
        FAILED
    }

    /** The unique keys of JExEconomy that existed before 3.1.0, the owner/currency key of accounts and the import key. */
    public static final List<Requirement> ECONOMY = List.of(
            new Requirement("economy_player", List.of("unique_id"), "uk_economy_player_unique_id", Duplicates.REPORT),
            new Requirement("economy_currency", List.of("identifier"), "uk_economy_currency_identifier",
                    Duplicates.REPORT),
            new Requirement("economy_account", List.of("player_id", "currency_id"), "uk_account_player_currency",
                    Duplicates.REPORT),
            new Requirement("economy_redeemed_coupon", List.of("coupon_id"), "ix_coupon_id", Duplicates.KEEP_OLDEST),
            new Requirement("economy_migration_entry", List.of("source_id", "player_uuid", "currency_id"),
                    "uk_migration_entry", Duplicates.REPORT));

    private static final String ID = "id";

    private final DataSource dataSource;
    private final Consumer<String> warn;

    /**
     * Creates the guard.
     *
     * @param dataSource the economy database
     * @param warn       receives one line per repaired or blocking problem
     */
    public UniqueIndexGuard(@NotNull DataSource dataSource, @NotNull Consumer<String> warn) {
        this.dataSource = dataSource;
        this.warn = warn;
    }

    /**
     * Ensures one index. Blocking; call it off the main thread.
     *
     * @param requirement the index
     * @return what was found or done
     */
    public @NotNull Outcome ensure(@NotNull Requirement requirement) {
        try (Connection connection = dataSource.getConnection()) {
            if (UniqueIndexes.hasUniqueIndex(connection.getMetaData(), requirement.table(),
                    new HashSet<>(requirement.columns()))) {
                return Outcome.PRESENT;
            }
            if (!resolveDuplicates(connection, requirement)) {
                return Outcome.BLOCKED_BY_DUPLICATES;
            }
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE UNIQUE INDEX " + requirement.indexName() + " ON "
                        + requirement.table() + " (" + String.join(", ", requirement.columns()) + ")");
            }
            warn.accept("Created the missing unique index " + requirement.indexName() + " on " + requirement.table());
            return Outcome.CREATED;
        } catch (SQLException failure) {
            warn.accept("Could not ensure the unique index on " + requirement.table() + " "
                    + requirement.columns() + ": " + failure.getMessage());
            return Outcome.FAILED;
        }
    }

    private boolean resolveDuplicates(@NotNull Connection connection, @NotNull Requirement requirement)
            throws SQLException {
        List<Long> surplus = surplusRows(connection, requirement);
        if (surplus.isEmpty()) {
            return true;
        }
        if (requirement.duplicates() == Duplicates.REPORT) {
            warn.accept(requirement.table() + " has " + surplus.size() + " row(s) that repeat the unique key "
                    + requirement.columns() + " (ids " + surplus + "); the unique index stays missing until they "
                    + "are merged by hand");
            return false;
        }
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM " + requirement.table() + " WHERE " + ID + " = ?")) {
            for (long id : surplus) {
                delete.setLong(1, id);
                delete.executeUpdate();
            }
        }
        warn.accept("Removed " + surplus.size() + " duplicate row(s) from " + requirement.table() + " (ids "
                + surplus + "), the oldest row of each " + requirement.columns() + " stays");
        return true;
    }

    private static @NotNull List<Long> surplusRows(@NotNull Connection connection, @NotNull Requirement requirement)
            throws SQLException {
        String key = String.join(", ", requirement.columns());
        String sql = "SELECT " + ID + ", " + key + " FROM " + requirement.table() + " ORDER BY " + key + ", " + ID;
        List<Long> surplus = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            List<String> previous = null;
            while (rows.next()) {
                List<String> current = keyOf(rows, requirement.columns());
                if (current.equals(previous)) {
                    surplus.add(rows.getLong(ID));
                }
                previous = current;
            }
        }
        return surplus;
    }

    private static @NotNull List<String> keyOf(@NotNull ResultSet rows, @NotNull List<String> columns)
            throws SQLException {
        List<String> key = new ArrayList<>(columns.size());
        for (String column : columns) {
            String value = rows.getString(column);
            key.add(value == null ? null : value.toLowerCase(Locale.ROOT));
        }
        return key;
    }
}
