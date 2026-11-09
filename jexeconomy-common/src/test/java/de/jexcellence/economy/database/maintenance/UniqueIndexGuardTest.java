package de.jexcellence.economy.database.maintenance;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UniqueIndexGuardTest {

    private static final UniqueIndexGuard.Requirement COUPONS = UniqueIndexGuard.ECONOMY.stream()
            .filter(requirement -> requirement.table().equals("economy_redeemed_coupon")).findFirst().orElseThrow();
    private static final UniqueIndexGuard.Requirement PLAYERS = UniqueIndexGuard.ECONOMY.stream()
            .filter(requirement -> requirement.table().equals("economy_player")).findFirst().orElseThrow();

    private final List<String> warnings = new ArrayList<>();
    private JdbcDataSource dataSource;
    private UniqueIndexGuard guard;

    @BeforeEach
    void createDatabase() throws SQLException {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:guard-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        execute("CREATE TABLE economy_redeemed_coupon (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "coupon_id VARCHAR(36) NOT NULL, redeemer_uuid VARCHAR(36) NOT NULL)");
        execute("CREATE TABLE economy_player (id BIGINT AUTO_INCREMENT PRIMARY KEY, unique_id UUID NOT NULL, "
                + "player_name VARCHAR(16) NOT NULL)");
        guard = new UniqueIndexGuard(dataSource, warnings::add);
    }

    @Test
    void duplicateRedemptionsKeepTheOldestAndTheIndexIsCreated() throws SQLException {
        execute("INSERT INTO economy_redeemed_coupon (coupon_id, redeemer_uuid) VALUES ('c-1', 'first'), "
                + "('c-2', 'other'), ('c-1', 'second'), ('c-1', 'third')");

        assertEquals(UniqueIndexGuard.Outcome.CREATED, guard.ensure(COUPONS));

        assertEquals(List.of("first", "other"), redeemers());
        assertThrows(SQLException.class, () -> execute(
                "INSERT INTO economy_redeemed_coupon (coupon_id, redeemer_uuid) VALUES ('c-2', 'again')"));
        assertEquals(UniqueIndexGuard.Outcome.PRESENT, guard.ensure(COUPONS));
        assertTrue(warnings.stream().anyMatch(line -> line.contains("Removed 2 duplicate row(s)")), warnings::toString);
    }

    @Test
    void duplicatePlayersAreOnlyReported() throws SQLException {
        UUID player = UUID.randomUUID();
        execute("INSERT INTO economy_player (unique_id, player_name) VALUES ('" + player + "', 'A'), ('" + player
                + "', 'B')");

        assertEquals(UniqueIndexGuard.Outcome.BLOCKED_BY_DUPLICATES, guard.ensure(PLAYERS));

        assertEquals(2, count("economy_player"));
    }

    @Test
    void anExistingUniqueConstraintIsRecognised() throws SQLException {
        execute("ALTER TABLE economy_player ADD CONSTRAINT uk_existing UNIQUE (unique_id)");

        assertEquals(UniqueIndexGuard.Outcome.PRESENT, guard.ensure(PLAYERS));
        assertTrue(warnings.isEmpty(), warnings::toString);
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private List<String> redeemers() throws SQLException {
        List<String> redeemers = new ArrayList<>();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT redeemer_uuid FROM economy_redeemed_coupon ORDER BY id")) {
            while (rows.next()) {
                redeemers.add(rows.getString(1));
            }
        }
        return redeemers;
    }

    private int count(String table) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
