package de.jexcellence.economy.command;

import de.jexcellence.economy.service.EconomyService;
import de.jexcellence.economy.text.EconomyText;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

/**
 * The one check commands run before they act on a named player. The lookup reads the database off the server
 * thread, so {@code action} can run on a worker thread for an offline player.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public final class KnownPlayers {

    private static final String NOT_FOUND_KEY = EconomyText.ROOT + "error.player-not-found";

    private KnownPlayers() {
    }

    /**
     * Runs {@code action} when the server knows {@code target}; otherwise tells {@code viewer} no such player exists.
     *
     * @param service the economy service
     * @param viewer  who receives the refusal
     * @param target  the named player
     * @param action  what to do for a known player
     */
    public static void whenKnown(@NotNull EconomyService service, @NotNull CommandSender viewer,
                                 @NotNull OfflinePlayer target, @NotNull Runnable action) {
        service.isKnownPlayer(target).thenAccept(known -> {
            if (Boolean.TRUE.equals(known)) {
                action.run();
            } else {
                EconomyText.send(viewer, EconomyText.msg(NOT_FOUND_KEY)
                        .with("player", EconomyText.nameOf(viewer, target)));
            }
        });
    }
}
