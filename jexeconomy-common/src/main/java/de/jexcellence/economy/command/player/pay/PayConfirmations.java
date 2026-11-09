package de.jexcellence.economy.command.player.pay;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The armed {@code /pay} confirmations: a payment from the threshold on waits here until the sender confirms it
 * (button, Bedrock form or the same command again) within {@link #WINDOW_MS}, or cancels it. Each sender has at
 * most one armed payment; arming a new one replaces the old, so a stale button can never pay.
 *
 * @author JExcellence
 * @since 3.0.0
 */
final class PayConfirmations {

    /** How long an armed payment can be confirmed. */
    static final long WINDOW_MS = 30_000L;

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    /**
     * An armed payment.
     *
     * @param target     the recipient
     * @param currencyId the currency
     * @param amount     the amount
     * @param armedAt    when it was armed, epoch millis
     */
    record Pending(@NotNull UUID target, @NotNull String currencyId, double amount, long armedAt) {

        boolean sameAs(@NotNull UUID otherTarget, @NotNull String otherCurrency, double otherAmount) {
            return target.equals(otherTarget) && currencyId.equals(otherCurrency)
                    && Double.compare(amount, otherAmount) == 0;
        }
    }

    PayConfirmations(@NotNull LongSupplier clock) {
        this.clock = clock;
    }

    /** Whether a payment of {@code amount} must be confirmed; a threshold of 0 turns the confirmation off. */
    static boolean needsConfirmation(double amount, double threshold) {
        return threshold > 0.0 && amount >= threshold;
    }

    /** Arms a payment for {@code sender}, replacing an older one. */
    @NotNull Pending arm(@NotNull UUID sender, @NotNull UUID target, @NotNull String currencyId, double amount) {
        Pending armed = new Pending(target, currencyId, amount, clock.getAsLong());
        pending.put(sender, armed);
        return armed;
    }

    /** Confirms exactly {@code expected} (a button or form): true once, while it is still armed and fresh. */
    boolean confirm(@NotNull UUID sender, @NotNull Pending expected) {
        return pending.remove(sender, expected) && fresh(expected);
    }

    /** Confirms by repeating the same command: true once when an armed, fresh payment matches. */
    boolean confirmRepeat(@NotNull UUID sender, @NotNull UUID target, @NotNull String currencyId, double amount) {
        Pending current = pending.get(sender);
        return current != null && current.sameAs(target, currencyId, amount) && confirm(sender, current);
    }

    /** Drops exactly {@code expected}; true when it was still armed and fresh. */
    boolean cancel(@NotNull UUID sender, @NotNull Pending expected) {
        return pending.remove(sender, expected) && fresh(expected);
    }

    private boolean fresh(@NotNull Pending armed) {
        return clock.getAsLong() - armed.armedAt() <= WINDOW_MS;
    }
}
