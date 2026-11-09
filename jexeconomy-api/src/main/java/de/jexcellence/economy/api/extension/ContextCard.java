package de.jexcellence.economy.api.extension;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A titled block of {@code Label | value} rows an extension adds to a player's leaderboard card. The texts are final,
 * so an extension translates them itself for the locale it is given.
 *
 * @param title the block title
 * @param rows  the rows; a card without rows is not shown
 * @author JExcellence
 * @since 3.2.0
 */
public record ContextCard(@NotNull String title, @NotNull List<Row> rows) {

    /**
     * Copies the rows so the record stays immutable.
     *
     * @param title the block title
     * @param rows  the rows
     */
    public ContextCard {
        rows = List.copyOf(rows);
    }

    /**
     * One row.
     *
     * @param label the label
     * @param value the value, {@code null} or blank shows the "none" marker
     */
    public record Row(@NotNull String label, @Nullable String value) {
    }
}
