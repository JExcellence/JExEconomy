package de.jexcellence.economy.database;

import org.jetbrains.annotations.NotNull;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads unique indexes from the JDBC metadata. Table and column names are matched ignoring case, because H2 reports
 * them upper case and MySQL/MariaDB as declared; index names are not compared, since a unique constraint created
 * with the table gets a database-specific index name.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public final class UniqueIndexes {

    private UniqueIndexes() {
    }

    /**
     * Whether {@code table} has a unique index on exactly {@code columns}.
     *
     * @param metadata the connection metadata
     * @param table    the table name
     * @param columns  the column names, lower case
     * @return {@code true} when such an index exists
     * @throws SQLException when the metadata cannot be read
     */
    public static boolean hasUniqueIndex(@NotNull DatabaseMetaData metadata, @NotNull String table,
                                         @NotNull Set<String> columns) throws SQLException {
        for (String name : Set.of(table, table.toUpperCase(Locale.ROOT))) {
            try (ResultSet rows = metadata.getIndexInfo(null, null, name, true, false)) {
                if (matches(uniqueIndexColumns(rows), columns)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static @NotNull Map<String, Set<String>> uniqueIndexColumns(@NotNull ResultSet rows) throws SQLException {
        Map<String, Set<String>> indexes = new HashMap<>();
        while (rows.next()) {
            String index = rows.getString("INDEX_NAME");
            String column = rows.getString("COLUMN_NAME");
            if (index != null && column != null && !rows.getBoolean("NON_UNIQUE")) {
                indexes.computeIfAbsent(index, ignored -> new HashSet<>()).add(column.toLowerCase(Locale.ROOT));
            }
        }
        return indexes;
    }

    static boolean matches(@NotNull Map<String, Set<String>> indexes, @NotNull Set<String> columns) {
        return indexes.values().stream().anyMatch(columns::equals);
    }
}
