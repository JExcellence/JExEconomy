package de.jexcellence.economy.api.extension;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Where the two legs of a player-to-player transfer ({@code /pay}) book their money when it is not the JExEconomy
 * wallet, for example a bank an extension keeps itself. A leg answers
 * {@link Outcome#WALLET} to keep the wallet path.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public interface TransferRoute {

    /** The route that keeps every leg on the wallet. */
    TransferRoute WALLET = new TransferRoute() {
        @Override
        public @NotNull Leg withdraw(@NotNull UUID sender, @NotNull UUID recipient, @NotNull String currencyId,
                                     double amount) {
            return Leg.wallet();
        }

        @Override
        public @NotNull Leg deposit(@NotNull UUID recipient, @NotNull UUID sender, @NotNull String currencyId,
                                    double amount) {
            return Leg.wallet();
        }

        @Override
        public boolean refund(@NotNull Leg withdrawn, @NotNull UUID recipient, double amount) {
            return false;
        }

        @Override
        public boolean walletOnly(@NotNull String currencyId) {
            return true;
        }
    };

    /**
     * Whether payments of this currency always stay in the wallets, with no leg and no deposit redirect going
     * anywhere else. Such payments are booked in one transaction.
     *
     * @param currencyId the currency
     * @return {@code true} when neither this route nor the deposit redirect can take the currency
     */
    default boolean walletOnly(@NotNull String currencyId) {
        return false;
    }

    /**
     * How transaction log entries name a routed balance.
     *
     * @return the name
     */
    default @NotNull String holderName() {
        return "external balance";
    }

    /** Takes the transfer amount from the sender outside the wallet. Runs off the main thread. */
    @NotNull Leg withdraw(@NotNull UUID sender, @NotNull UUID recipient, @NotNull String currencyId, double amount);

    /** Books the transfer amount for the recipient outside the wallet. Runs off the main thread. */
    @NotNull Leg deposit(@NotNull UUID recipient, @NotNull UUID sender, @NotNull String currencyId, double amount);

    /** Gives a routed withdrawal back to where it came from after the deposit failed; whether it was booked. */
    boolean refund(@NotNull Leg withdrawn, @NotNull UUID recipient, double amount);

    /** Outcome of one leg. */
    enum Outcome {
        /** Not routed: the wallet books this leg. */
        WALLET,
        /** The routed leg moved the money. */
        DONE,
        /** The sender's routed balance is too low. */
        INSUFFICIENT,
        /** The sender may not take money from the routed balance. */
        DENIED,
        /** The recipient's routed balance has no room for the whole amount. */
        BANK_FULL,
        /** The routed balance holds whole units only and the amount has a fraction. */
        WHOLE_COINS_ONLY,
        /** The routed leg threw; nothing is known to have moved for a deposit. */
        FAILED
    }

    /**
     * One leg of a transfer.
     *
     * @param outcome what happened
     * @param bankId  the routed holder, {@code -1} on the wallet
     * @param balance the routed balance after the leg
     */
    record Leg(@NotNull Outcome outcome, long bankId, double balance) {

        /** A leg that stays on the wallet. */
        public static @NotNull Leg wallet() {
            return new Leg(Outcome.WALLET, -1L, 0.0);
        }

        /** A routed leg that threw. */
        public static @NotNull Leg failed() {
            return new Leg(Outcome.FAILED, -1L, 0.0);
        }

        /** Whether the leg was booked outside the wallet. */
        public boolean routed() {
            return outcome != Outcome.WALLET;
        }
    }
}
