package de.jexcellence.economy.database.entity;

import de.jexcellence.jehibernate.entity.base.LongIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * A currency item waiting for its owner: the redemption failed after the player had left, so the item is handed back
 * on the next join. The row is removed before the item is handed out, so an item can never be delivered twice.
 *
 * @author JExcellence
 * @since 3.2.0
 */
@Entity
@Table(
        name = "economy_item_return",
        indexes = @Index(name = "ix_item_return_player", columnList = "player_uuid")
)
public class ItemReturn extends LongIdEntity {

    private static final int MAX_ITEM_BYTES = 65_535;

    @Column(name = "player_uuid", nullable = false, length = 36)
    private String playerUuid;

    @Lob
    @Column(name = "item_data", nullable = false, length = MAX_ITEM_BYTES)
    private byte[] itemData;

    /** JPA constructor. */
    protected ItemReturn() {
    }

    /**
     * Creates a waiting item.
     *
     * @param player   the owner
     * @param itemData the serialized item
     */
    public ItemReturn(@NotNull UUID player, byte @NotNull [] itemData) {
        this.playerUuid = player.toString();
        this.itemData = itemData.clone();
    }

    /**
     * The owner.
     *
     * @return the owner's id
     */
    public @NotNull UUID getPlayerUuid() {
        return UUID.fromString(playerUuid);
    }

    /**
     * The serialized item.
     *
     * @return a copy of the bytes
     */
    public byte @NotNull [] getItemData() {
        return itemData.clone();
    }
}
