package de.jexcellence.economy.example;

import de.jexcellence.economy.api.extension.ContextCard;
import de.jexcellence.economy.api.extension.EconomyExtension;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Overrides only what it needs; everything else keeps the JExEconomy defaults. The set is immutable, so the calls are
 * safe from any thread, as {@link EconomyExtension} requires.
 *
 * @param banned the players who may not trade
 * @param texts  the texts of the leaderboard block
 * @author JExcellence
 * @since 3.2.0
 */
record TradeBanExtension(@NotNull Set<UUID> banned, @NotNull Texts texts) implements EconomyExtension {

    /**
     * The texts of the leaderboard block.
     *
     * @param title   the block title
     * @param label   the row label
     * @param allowed the value of a player who may trade
     * @param denied  the value of a banned player
     */
    record Texts(@NotNull String title, @NotNull String label, @NotNull String allowed, @NotNull String denied) {
    }

    @Override
    public boolean isTradeBlocked(@NotNull UUID first, @NotNull UUID second) {
        return banned.contains(first) || banned.contains(second);
    }

    @Override
    public @NotNull Optional<ContextCard> leaderboardCard(@NotNull UUID owner, @NotNull String locale) {
        String state = banned.contains(owner) ? texts.denied() : texts.allowed();
        return Optional.of(new ContextCard(texts.title(), List.of(new ContextCard.Row(texts.label(), state))));
    }
}
