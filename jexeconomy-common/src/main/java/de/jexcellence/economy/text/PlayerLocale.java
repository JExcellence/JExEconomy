package de.jexcellence.economy.text;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * The client locale of a player on every supported server. Paper's {@code Player.locale()} does not exist on
 * Spigot, so the locale is read from the client language code that Paper, Folia and Spigot all report.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public final class PlayerLocale {

    private PlayerLocale() {
    }

    /**
     * The locale of the player's client.
     *
     * @param player the player
     * @return the locale, {@link Locale#ROOT} when the client reports none
     */
    @SuppressWarnings("deprecation")
    public static @NotNull Locale of(@NotNull Player player) {
        String code = player.getLocale();
        if (code == null || code.isBlank()) {
            return Locale.ROOT;
        }
        return Locale.forLanguageTag(code.replace('_', '-'));
    }
}
