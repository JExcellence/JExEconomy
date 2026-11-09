package de.jexcellence.economy.api.extension;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Takes a deposit out of the wallet path, for example into a bank an extension keeps itself.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public interface DepositRedirect {

    /** Keeps every deposit in the wallet. */
    DepositRedirect NONE = (player, currencyId, amount, reason) -> Redirected.NONE;

    /**
     * Offers a deposit to the target.
     *
     * @param player     the receiving player
     * @param currencyId the currency
     * @param amount     the deposit
     * @param reason     the deposit reason
     * @return what the target took over; {@link Redirected#NONE} when the deposit stays in the wallet
     */
    @NotNull Redirected redirect(@NotNull UUID player, @NotNull String currencyId, double amount,
                                 @NotNull String reason);

    /**
     * Takes a redirected amount back out of exactly the target that received it, used when the wallet part of the
     * same deposit failed or a coupon payout is reversed, so the reversal never touches another target.
     *
     * @param player     the receiving player
     * @param currencyId the currency
     * @param redirected what {@link #redirect} returned
     * @return {@code true} when the amount was taken back
     */
    default boolean undo(@NotNull UUID player, @NotNull String currencyId, @NotNull Redirected redirected) {
        return false;
    }

    /**
     * The part of a deposit a target took over.
     *
     * @param amount   the whole units taken over, {@code 0} or less for none
     * @param targetId the target that holds them, {@code null} when unknown; an unknown target
     *                 cannot be undone
     * @param exact    whether the target itself reported {@code targetId}; otherwise it was inferred around the
     *                 deposit
     */
    record Redirected(long amount, @Nullable Long targetId, boolean exact) {

        /** Nothing was taken over. */
        public static final Redirected NONE = new Redirected(-1L, null, false);

        /**
         * A share whose target was inferred, not reported.
         *
         * @param amount   the units taken over
         * @param targetId the inferred target, {@code null} when unknown
         */
        public Redirected(long amount, @Nullable Long targetId) {
            this(amount, targetId, false);
        }

        /**
         * Whether a share was taken over.
         *
         * @return {@code true} for a positive amount
         */
        public boolean taken() {
            return amount > 0L;
        }
    }
}
