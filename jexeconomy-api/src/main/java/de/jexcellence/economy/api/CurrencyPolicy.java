package de.jexcellence.economy.api;

import org.jetbrains.annotations.NotNull;

/**
 * Behaviour flags for one currency.
 *
 * @param payable      whether players may transfer the currency to each other
 * @param resettable   whether {@link EconomyProvider#resetCurrency(String, String)} may zero every account
 * @param wholeNumbers whether balances are shown without decimals
 * @author JExcellence
 * @since 3.1.0
 */
public record CurrencyPolicy(boolean payable, boolean resettable, boolean wholeNumbers) {

    /** Policy of a plain currency: payable, never reset, shown with two decimals. */
    public static final CurrencyPolicy DEFAULT = new CurrencyPolicy(true, false, false);

    /**
     * Policy of a progression currency that stays with its owner.
     *
     * @param resettable whether the currency is wiped by a bulk reset (for example each season)
     * @return a non-payable, whole-number policy
     */
    public static @NotNull CurrencyPolicy ownerBound(boolean resettable) {
        return new CurrencyPolicy(false, resettable, true);
    }
}
