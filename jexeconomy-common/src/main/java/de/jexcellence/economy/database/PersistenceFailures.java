package de.jexcellence.economy.database;

import jakarta.persistence.OptimisticLockException;
import org.hibernate.StaleStateException;
import org.hibernate.exception.ConstraintViolationException;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Locale;

/**
 * Classifies database failures by exception type. JEHibernate wraps every persistence failure in its own
 * {@code TransactionException} and futures wrap it again in a {@code CompletionException}, so each check walks the
 * cause chain.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public final class PersistenceFailures {

    private static final String SQL_STATE_UNIQUE = "23505";
    private static final int MYSQL_DUPLICATE_KEY = 1062;
    private static final int MAX_CAUSE_DEPTH = 16;

    private PersistenceFailures() {
    }

    /**
     * Whether the failure is an optimistic-lock conflict: the row changed since it was read.
     *
     * @param failure the failure, may be {@code null}
     * @return {@code true} for a stale-version conflict
     */
    public static boolean isOptimisticLock(@Nullable Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof OptimisticLockException || current instanceof StaleStateException) {
                return true;
            }
            current = nextCause(current);
        }
        return false;
    }

    /**
     * Whether the failure is a unique-constraint violation, the signal that another writer inserted the same key
     * first. Foreign-key and not-null violations do not count.
     *
     * @param failure the failure, may be {@code null}
     * @return {@code true} for a duplicate key
     */
    public static boolean isUniqueViolation(@Nullable Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (isUniqueConstraint(current)) {
                return true;
            }
            current = nextCause(current);
        }
        return false;
    }

    private static boolean isUniqueConstraint(@Nullable Throwable failure) {
        if (failure instanceof ConstraintViolationException violation
                && violation.getKind() == ConstraintViolationException.ConstraintKind.UNIQUE) {
            return true;
        }
        if (failure instanceof SQLException sql) {
            return SQL_STATE_UNIQUE.equals(sql.getSQLState())
                    || sql.getErrorCode() == MYSQL_DUPLICATE_KEY
                    || (sql instanceof SQLIntegrityConstraintViolationException && mentionsUnique(sql));
        }
        return false;
    }

    private static boolean mentionsUnique(@Nullable Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("unique") || lower.contains("duplicate");
    }

    private static @Nullable Throwable nextCause(@Nullable Throwable current) {
        Throwable cause = current == null ? null : current.getCause();
        return cause == current ? null : cause;
    }
}
