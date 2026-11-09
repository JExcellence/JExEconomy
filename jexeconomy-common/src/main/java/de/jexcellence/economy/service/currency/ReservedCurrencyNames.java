package de.jexcellence.economy.service.currency;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Identifiers no currency may use, because {@code /balance <currency>} would never reach them: the subcommands
 * {@code all}, {@code top} and {@code help} of {@code /balance} always win over the currency argument. The check
 * ignores case and surrounding spaces.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public final class ReservedCurrencyNames {

    private static final List<String> NAMES = List.of("all", "top", "help");

    private ReservedCurrencyNames() {
    }

    /**
     * Whether {@code identifier} is reserved.
     *
     * @param identifier the currency identifier, may be {@code null}
     * @return {@code true} when no currency may use it
     */
    public static boolean isReserved(@Nullable String identifier) {
        return identifier != null && NAMES.contains(identifier.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * The reserved names, in the order {@code /balance} lists its subcommands.
     *
     * @return the names
     */
    public static @NotNull List<String> names() {
        return NAMES;
    }
}
