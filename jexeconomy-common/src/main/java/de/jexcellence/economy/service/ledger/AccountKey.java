package de.jexcellence.economy.service.ledger;

import de.jexcellence.economy.database.PlayerNames;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Identifies one account in the ledger: a player and a currency. The name keeps the stored player name current and
 * labels log entries and events; it is not part of the identity.
 *
 * @param playerId   the owner
 * @param playerName the owner's name, at most 16 characters; the UUID fallback when the name is unknown
 * @param currencyId the currency identifier
 * @param nameKnown  whether {@code playerName} is the real name; an unknown name never renames the stored player
 * @author JExcellence
 * @since 3.1.0
 */
public record AccountKey(@NotNull UUID playerId, @NotNull String playerName, @NotNull String currencyId,
                         boolean nameKnown) {

    /**
     * Creates a key and cuts the name to the 16 characters the player table allows.
     *
     * @param playerId   the owner
     * @param playerName the owner's name
     * @param currencyId the currency identifier
     * @param nameKnown  whether the name is the real name
     */
    public AccountKey {
        playerName = PlayerNames.clamp(playerName);
    }

    /**
     * Creates a key for a player with a known name.
     *
     * @param playerId   the owner
     * @param playerName the owner's name
     * @param currencyId the currency identifier
     */
    public AccountKey(@NotNull UUID playerId, @NotNull String playerName, @NotNull String currencyId) {
        this(playerId, playerName, currencyId, true);
    }

    /**
     * Creates a key from a name that may be unknown.
     *
     * @param playerId   the owner
     * @param playerName the owner's name, {@code null} or blank when unknown
     * @param currencyId the currency identifier
     * @return the key; an unknown name becomes the UUID fallback with {@code nameKnown == false}
     */
    public static @NotNull AccountKey of(@NotNull UUID playerId, @Nullable String playerName,
                                         @NotNull String currencyId) {
        boolean known = playerName != null && !playerName.isBlank();
        return new AccountKey(playerId, PlayerNames.storable(playerId, playerName), currencyId, known);
    }

    /**
     * The name to store, {@code null} when it is unknown and the stored name must stay.
     *
     * @return the known name or {@code null}
     */
    public @Nullable String storedName() {
        return nameKnown ? playerName : null;
    }

    /**
     * The key of the per-account queue.
     *
     * @return {@code uuid:currency}
     */
    public @NotNull String queueKey() {
        return playerId + ":" + currencyId;
    }
}
