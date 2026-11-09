package de.jexcellence.economy.database;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Player names as the {@code economy_player.player_name} column stores them: at most 16 characters. A player whose
 * name is unknown (an offline player that never joined, a Vault call by UUID) is stored under the first 16 characters
 * of the UUID, and such a fallback never replaces a real stored name.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public final class PlayerNames {

    /** Length of the {@code player_name} column. */
    public static final int MAX_LENGTH = 16;

    private PlayerNames() {
    }

    /**
     * Cuts a name to the column length.
     *
     * @param name the name
     * @return the name, at most {@value #MAX_LENGTH} characters
     */
    public static @NotNull String clamp(@NotNull String name) {
        return name.length() > MAX_LENGTH ? name.substring(0, MAX_LENGTH) : name;
    }

    /**
     * The stored name of a player without a known name.
     *
     * @param uuid the player
     * @return the first {@value #MAX_LENGTH} characters of the UUID
     */
    public static @NotNull String fallback(@NotNull UUID uuid) {
        return clamp(uuid.toString());
    }

    /**
     * The name to store: the clamped name, or the UUID fallback when the name is unknown or blank.
     *
     * @param uuid the player
     * @param name the known name, {@code null} when unknown
     * @return the name to store
     */
    public static @NotNull String storable(@NotNull UUID uuid, @Nullable String name) {
        return name == null || name.isBlank() ? fallback(uuid) : clamp(name);
    }
}
