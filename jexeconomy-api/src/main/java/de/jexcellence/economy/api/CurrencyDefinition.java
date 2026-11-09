package de.jexcellence.economy.api;

import org.jetbrains.annotations.NotNull;

/**
 * A currency another plugin needs, registered through
 * {@link EconomyProvider#registerCurrency(CurrencyDefinition)}.
 *
 * @param identifier unique currency id (max 32 characters)
 * @param symbol     short symbol shown after the amount
 * @param prefix     text shown before the amount
 * @param suffix     text shown after the symbol
 * @param icon       Material name used as the GUI icon
 * @param policy     behaviour flags
 * @author JExcellence
 * @since 3.1.0
 */
public record CurrencyDefinition(@NotNull String identifier,
                                 @NotNull String symbol,
                                 @NotNull String prefix,
                                 @NotNull String suffix,
                                 @NotNull String icon,
                                 @NotNull CurrencyPolicy policy) {
}
