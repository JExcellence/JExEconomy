package de.jexcellence.economy.money;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money arithmetic of the economy: every balance and amount is a {@link BigDecimal} with two decimals, rounded
 * {@link RoundingMode#HALF_UP}. The columns are {@code DECIMAL(64,2)}, so the scale matches what the database stores.
 * Doubles appear only at the public API edge ({@code EconomyProvider}, events, Vault) and are converted here.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public final class Money {

    /** Decimals of every stored amount. */
    public static final int SCALE = 2;
    /** Rounding of every conversion and calculation. */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    /** Zero with the money scale. */
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, ROUNDING);

    private Money() {
    }

    /**
     * Converts an API double into money. {@link BigDecimal#valueOf(double)} uses the shortest decimal form of the
     * double, so {@code 0.1} stays {@code 0.10} instead of picking up binary noise.
     *
     * @param amount the amount; NaN and infinity are rejected
     * @return the amount with two decimals
     */
    public static @NotNull BigDecimal of(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount)) {
            throw new IllegalArgumentException("Amount must be a finite number: " + amount);
        }
        return BigDecimal.valueOf(amount).setScale(SCALE, ROUNDING);
    }

    /**
     * Normalises a stored or computed value to the money scale; {@code null} becomes zero.
     *
     * @param amount the value, may be {@code null} for a column without a value
     * @return the value with two decimals
     */
    public static @NotNull BigDecimal of(@Nullable BigDecimal amount) {
        return amount == null ? ZERO : amount.setScale(SCALE, ROUNDING);
    }

    /**
     * Converts money to the API double.
     *
     * @param amount the money value, {@code null} counts as zero
     * @return the double value
     */
    public static double toDouble(@Nullable BigDecimal amount) {
        return amount == null ? 0.0 : amount.doubleValue();
    }

    /**
     * Whether an amount is above zero.
     *
     * @param amount the amount
     * @return {@code true} for a positive amount
     */
    public static boolean isPositive(@NotNull BigDecimal amount) {
        return amount.signum() > 0;
    }

    /**
     * Whether an API amount books at least one cent: finite and above zero after rounding to the money scale. An
     * amount such as {@code 0.004} rounds to {@code 0.00} and is not bookable.
     *
     * @param amount the API amount
     * @return {@code true} when the amount can be booked as a deposit, withdrawal or transfer
     */
    public static boolean isBookable(double amount) {
        return !Double.isNaN(amount) && !Double.isInfinite(amount) && isPositive(of(amount));
    }
}
