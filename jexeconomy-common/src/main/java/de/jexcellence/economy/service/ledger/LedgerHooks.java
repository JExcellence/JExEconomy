package de.jexcellence.economy.service.ledger;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * What happens around a balance write: listeners may refuse it beforehand and are told after it was written.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public interface LedgerHooks {

    /** Hooks that never refuse and ignore committed changes. */
    LedgerHooks NONE = new LedgerHooks() {
        @Override
        public @NotNull Optional<String> veto(@NotNull BalanceChange change, @NotNull BooleanSupplier dispatchBlocked) {
            return Optional.empty();
        }

        @Override
        public boolean vetoRequired() {
            return false;
        }

        @Override
        public void committed(@NotNull BalanceChange change) {
            // Nothing to notify without listeners.
        }
    };

    /**
     * Asks whether the change may happen. Runs on the veto executor and may block until listeners answered.
     *
     * @param change          the planned change
     * @param dispatchBlocked {@code true} while the thread that would run the listeners waits for this account
     * @return the refusal reason, or empty to allow it
     */
    @NotNull Optional<String> veto(@NotNull BalanceChange change, @NotNull BooleanSupplier dispatchBlocked);

    /**
     * Whether anyone could refuse a change at all. Without listeners the ledger skips the wait for a server tick.
     *
     * @return {@code true} when {@link #veto} has to be asked
     */
    default boolean vetoRequired() {
        return true;
    }

    /**
     * Reports a written change.
     *
     * @param change the written change
     */
    void committed(@NotNull BalanceChange change);
}
