package de.jexcellence.economy.service.ledger;

import de.jexcellence.economy.api.ChangeType;
import de.jexcellence.economy.money.Money;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;

/**
 * What a ledger request does to a balance: add a signed amount, or set an absolute value. Both are computed from
 * the balance read inside the account's queue, so an absolute set can never race a deposit.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public sealed interface BalanceOperation {

    /**
     * Adds an amount; negative for a withdrawal.
     *
     * @param signedAmount the amount
     * @return the operation
     */
    static @NotNull BalanceOperation delta(@NotNull BigDecimal signedAmount) {
        return new Delta(Money.of(signedAmount));
    }

    /**
     * Sets the balance to a value.
     *
     * @param target the new balance, not negative
     * @return the operation
     */
    static @NotNull BalanceOperation absolute(@NotNull BigDecimal target) {
        if (target.signum() < 0) {
            throw new IllegalArgumentException("Balance cannot be negative");
        }
        return new Absolute(Money.of(target));
    }

    /**
     * Sets the balance to a value, but only while it still equals an expected value. Otherwise the ledger refuses the
     * change with {@code FailureReason.BALANCE_CHANGED}.
     *
     * @param expected the balance the change is based on
     * @param target   the new balance, not negative
     * @return the operation
     */
    static @NotNull BalanceOperation compareAndSet(@NotNull BigDecimal expected, @NotNull BigDecimal target) {
        if (target.signum() < 0) {
            throw new IllegalArgumentException("Balance cannot be negative");
        }
        return new CompareAndSet(Money.of(expected), new Absolute(Money.of(target)));
    }

    /**
     * Whether the operation may run on this balance.
     *
     * @param current the balance before
     * @return {@code false} when the ledger has to refuse it
     */
    default boolean allows(@NotNull BigDecimal current) {
        return true;
    }

    /**
     * The balance after the operation.
     *
     * @param current the balance before
     * @return the balance after, may be negative for a withdrawal that does not fit
     */
    @NotNull BigDecimal apply(@NotNull BigDecimal current);

    /**
     * The amount the change moves or, when refused, would have moved.
     *
     * @param current the balance before
     * @return the amount, not negative
     */
    @NotNull BigDecimal requested(@NotNull BigDecimal current);

    /**
     * The change type of the log entry and events.
     *
     * @param requested the type the caller asked for
     * @param current   the balance before
     * @return the type to record
     */
    @NotNull ChangeType changeType(@NotNull ChangeType requested, @NotNull BigDecimal current);

    /**
     * Whether a result equal to the current balance is reported as success without events or log entry.
     *
     * @return {@code true} for an absolute set
     */
    boolean skipsNoOp();

    /**
     * Adds a signed amount.
     *
     * @param signedAmount the amount, negative for a withdrawal
     */
    record Delta(@NotNull BigDecimal signedAmount) implements BalanceOperation {

        @Override
        public @NotNull BigDecimal apply(@NotNull BigDecimal current) {
            return current.add(signedAmount);
        }

        @Override
        public @NotNull BigDecimal requested(@NotNull BigDecimal current) {
            return signedAmount.abs();
        }

        @Override
        public @NotNull ChangeType changeType(@NotNull ChangeType requested, @NotNull BigDecimal current) {
            return requested;
        }

        @Override
        public boolean skipsNoOp() {
            return false;
        }
    }

    /**
     * Sets an absolute balance; recorded as a deposit when it raises the balance, else as a withdrawal.
     *
     * @param target the new balance
     */
    record Absolute(@NotNull BigDecimal target) implements BalanceOperation {

        @Override
        public @NotNull BigDecimal apply(@NotNull BigDecimal current) {
            return target;
        }

        @Override
        public @NotNull BigDecimal requested(@NotNull BigDecimal current) {
            return target.subtract(current).abs();
        }

        @Override
        public @NotNull ChangeType changeType(@NotNull ChangeType requested, @NotNull BigDecimal current) {
            return target.compareTo(current) >= 0 ? ChangeType.DEPOSIT : ChangeType.WITHDRAW;
        }

        @Override
        public boolean skipsNoOp() {
            return true;
        }
    }

    /**
     * An absolute set that only runs on an expected balance.
     *
     * @param expected the balance the change is based on
     * @param set      the set
     */
    record CompareAndSet(@NotNull BigDecimal expected, @NotNull Absolute set) implements BalanceOperation {

        @Override
        public boolean allows(@NotNull BigDecimal current) {
            return current.compareTo(expected) == 0;
        }

        @Override
        public @NotNull BigDecimal apply(@NotNull BigDecimal current) {
            return set.apply(current);
        }

        @Override
        public @NotNull BigDecimal requested(@NotNull BigDecimal current) {
            return set.requested(current);
        }

        @Override
        public @NotNull ChangeType changeType(@NotNull ChangeType requested, @NotNull BigDecimal current) {
            return set.changeType(requested, current);
        }

        @Override
        public boolean skipsNoOp() {
            return set.skipsNoOp();
        }
    }
}
