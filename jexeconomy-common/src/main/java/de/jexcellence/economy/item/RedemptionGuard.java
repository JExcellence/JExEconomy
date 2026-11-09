package de.jexcellence.economy.item;

import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Allows one physical currency redemption per player at a time. A click that arrives while the player's previous
 * redemption still waits for its deposit is refused, so rapid clicks cannot book one item twice.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public final class RedemptionGuard {

    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * Claims the player's redemption slot.
     *
     * @param player the player
     * @return {@code true} when no other redemption of the player is running
     */
    public boolean tryBegin(@NotNull UUID player) {
        return inFlight.add(player);
    }

    /**
     * Releases the player's redemption slot.
     *
     * @param player the player
     */
    public void finish(@NotNull UUID player) {
        inFlight.remove(player);
    }

    /**
     * Whether the player has a redemption running.
     *
     * @param player the player
     * @return {@code true} while a redemption runs
     */
    public boolean isRunning(@NotNull UUID player) {
        return inFlight.contains(player);
    }
}
