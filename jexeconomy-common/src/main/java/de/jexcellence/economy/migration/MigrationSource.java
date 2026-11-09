package de.jexcellence.economy.migration;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Somewhere balances can be imported from. A source is only read, never changed.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public interface MigrationSource {

    /**
     * One player's balance in a source.
     *
     * @param player  the player
     * @param name    the player's name, {@code null} when unknown
     * @param balance the balance as the source holds it
     */
    record SourceBalance(@NotNull UUID player, @Nullable String name, @NotNull BigDecimal balance) {
    }

    /**
     * What a source delivered.
     *
     * @param balances   the balances
     * @param unreadable the players or lines that could not be read
     */
    record SourceRead(@NotNull List<SourceBalance> balances, int unreadable) {
    }

    /**
     * The id typed in the command, for example {@code vault:essentials}.
     *
     * @return the id
     */
    @NotNull String id();

    /**
     * Reads every balance, without blocking the calling thread.
     *
     * @return future resolving to what was read
     */
    @NotNull CompletableFuture<SourceRead> read();
}
