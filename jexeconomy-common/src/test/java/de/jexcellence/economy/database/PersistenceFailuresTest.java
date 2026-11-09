package de.jexcellence.economy.database;

import jakarta.persistence.OptimisticLockException;
import org.hibernate.StaleObjectStateException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the failure classification by exception type: wrapped optimistic-lock conflicts are retried, only unique
 * violations count as "someone else inserted it first".
 */
class PersistenceFailuresTest {

    @Test
    void wrappedOptimisticLockIsRecognised() {
        var wrapped = new CompletionException(new RuntimeException("Transaction failed",
                new OptimisticLockException("stale")));

        assertTrue(PersistenceFailures.isOptimisticLock(wrapped));
        assertTrue(PersistenceFailures.isOptimisticLock(new StaleObjectStateException("Account", 1L)));
        assertFalse(PersistenceFailures.isOptimisticLock(new IllegalStateException("database down")));
        assertFalse(PersistenceFailures.isOptimisticLock(null));
    }

    @Test
    void onlyUniqueViolationsCountAsDuplicates() {
        var unique = new ConstraintViolationException("dup", new SQLException("dup", "23505"),
                ConstraintViolationException.ConstraintKind.UNIQUE, "uk_account_player_currency");
        var foreignKey = new ConstraintViolationException("fk", new SQLException("fk", "23503"),
                ConstraintViolationException.ConstraintKind.FOREIGN_KEY, "fk_account_currency");
        var mysqlDuplicate = new RuntimeException(new SQLException("Duplicate entry", "23000", 1062));

        assertTrue(PersistenceFailures.isUniqueViolation(new CompletionException(unique)));
        assertTrue(PersistenceFailures.isUniqueViolation(mysqlDuplicate));
        assertFalse(PersistenceFailures.isUniqueViolation(new RuntimeException(new SQLException("fk", "23503"))));
        assertFalse(PersistenceFailures.isUniqueViolation(new CompletionException(new RuntimeException("timeout"))));
        assertFalse(PersistenceFailures.isUniqueViolation(new RuntimeException(foreignKey)));
    }

}
