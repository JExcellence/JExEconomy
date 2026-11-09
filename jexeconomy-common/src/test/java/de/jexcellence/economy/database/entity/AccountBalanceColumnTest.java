package de.jexcellence.economy.database.entity;

import de.jexcellence.economy.money.Money;
import jakarta.persistence.Column;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the balance column against a mapping whose schema update H2 rejects at startup. */
class AccountBalanceColumnTest {

    @Test
    void balanceIsMappedByPrecisionAndScaleOnly() throws NoSuchFieldException {
        Column column = Account.class.getDeclaredField("balance").getAnnotation(Column.class);

        assertTrue(column.columnDefinition().isEmpty(), "a columnDefinition ends up inside ALTER COLUMN");
        assertEquals(64, column.precision());
        assertEquals(Money.SCALE, column.scale());
    }

    @Test
    void anOldDoubleBalanceColumnMigratesToTwoDecimals() throws SQLException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:balance-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE economy_account (id BIGINT PRIMARY KEY, balance DOUBLE NOT NULL)");
            statement.execute("INSERT INTO economy_account VALUES (1, 1234.567), (2, 0)");

            statement.execute("alter table if exists economy_account alter column balance set data type numeric(64,2)");

            try (ResultSet rows = statement.executeQuery("SELECT balance FROM economy_account ORDER BY id")) {
                rows.next();
                assertEquals(new BigDecimal("1234.57"), rows.getBigDecimal(1));
                rows.next();
                assertEquals(new BigDecimal("0.00"), rows.getBigDecimal(1));
            }
        }
    }
}
