package de.jexcellence.economy.migration;

import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;

/**
 * What an import would do.
 *
 * @param players         the players that would be imported
 * @param sourceTotal     the sum of every valid balance in the source
 * @param importTotal     the sum the players would receive
 * @param roundingLoss    how much the rounding to the economy's precision changes in total
 * @param counts          the balances that are left out, by reason
 * @author JExcellence
 * @since 3.2.0
 */
public record MigrationReport(int players, @NotNull BigDecimal sourceTotal, @NotNull BigDecimal importTotal,
                              @NotNull BigDecimal roundingLoss, @NotNull LeftOut counts) {

    /**
     * Balances that are left out of an import.
     *
     * @param invalid         negative balances and repeated players
     * @param unreadable      players or lines the source could not deliver
     * @param empty           zero balances
     * @param existing        accounts {@link MigrationMode#SKIP_EXISTING} leaves alone
     * @param alreadyImported players an earlier run imported from this source
     */
    public record LeftOut(int invalid, int unreadable, int empty, int existing, int alreadyImported) {
    }
}
