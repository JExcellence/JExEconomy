package de.jexcellence.economy.database.repository;

import de.jexcellence.economy.database.PersistenceFailures;
import de.jexcellence.economy.database.PlayerNames;
import de.jexcellence.economy.database.entity.EconomyPlayer;
import jakarta.persistence.EntityManagerFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Repository for {@link EconomyPlayer} entities.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class PlayerRepository extends LoggedCrudRepository<EconomyPlayer> {

    private static final String FIELD_UNIQUE_ID = "uniqueId";

    /**
     * Creates a player repository.
     *
     * @param executor    the executor for async operations
     * @param emf         the entity manager factory
     * @param entityClass the entity class
     */
    public PlayerRepository(@NotNull ExecutorService executor,
                            @NotNull EntityManagerFactory emf,
                            @NotNull Class<EconomyPlayer> entityClass) {
        super(executor, emf, entityClass);
    }

    /**
     * Finds a player by UUID.
     *
     * @param uuid the player's UUID
     * @return the player, or empty
     */
    public @NotNull Optional<EconomyPlayer> findByUuid(@NotNull UUID uuid) {
        return query().and(FIELD_UNIQUE_ID, uuid).first();
    }

    /**
     * Finds a player by UUID asynchronously.
     *
     * @param uuid the player's UUID
     * @return future resolving to the player, or empty
     */
    public @NotNull CompletableFuture<Optional<EconomyPlayer>> findByUuidAsync(@NotNull UUID uuid) {
        return query().and(FIELD_UNIQUE_ID, uuid).firstAsync();
    }

    /**
     * Finds or creates a player record and keeps the stored name current. Two threads creating the same player at
     * once both end with the one row: the loser of the insert race reads the winner's row.
     *
     * @param uuid       the player's UUID
     * @param playerName the player's current name, {@code null} when unknown; names are cut to 16 characters, an
     *                   unknown name stores the UUID fallback on a new row and never renames an existing one
     * @return the existing or newly created player
     */
    public @NotNull EconomyPlayer findOrCreate(@NotNull UUID uuid, @Nullable String playerName) {
        var existing = findByUuid(uuid);
        if (existing.isPresent()) {
            return renamed(existing.get(), playerName);
        }
        try {
            return create(new EconomyPlayer(uuid, PlayerNames.storable(uuid, playerName)));
        } catch (RuntimeException failure) {
            if (!PersistenceFailures.isUniqueViolation(failure)) {
                throw failure;
            }
            return findByUuid(uuid)
                    .map(player -> renamed(player, playerName))
                    .orElseThrow(() -> failure);
        }
    }

    private @NotNull EconomyPlayer renamed(@NotNull EconomyPlayer player, @Nullable String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return player;
        }
        String stored = PlayerNames.clamp(playerName);
        if (player.getPlayerName().equals(stored)) {
            return player;
        }
        player.setPlayerName(stored);
        return update(player);
    }

    /**
     * Finds or creates a player record asynchronously on the repository executor.
     *
     * @param uuid       the player's UUID
     * @param playerName the player's current name, {@code null} when unknown
     * @return future resolving to the existing or newly created player
     */
    public @NotNull CompletableFuture<EconomyPlayer> findOrCreateAsync(@NotNull UUID uuid,
                                                                       @Nullable String playerName) {
        return async(() -> findOrCreate(uuid, playerName));
    }
}
