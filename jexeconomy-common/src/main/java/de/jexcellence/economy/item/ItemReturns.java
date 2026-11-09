package de.jexcellence.economy.item;

import de.jexcellence.economy.database.entity.ItemReturn;
import de.jexcellence.economy.database.repository.ItemReturnRepository;
import de.jexcellence.economy.text.EconomyText;
import de.jexcellence.jexplatform.logging.JExLogger;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Keeps currency items whose redemption failed after the player left and hands them back on the next join. A row is
 * claimed before its item is handed out: a crash in between loses one item instead of duplicating value.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public final class ItemReturns {

    private static final String RETURNED_KEY = EconomyText.ROOT + "item.redeem.returned";

    private final ItemReturnRepository repository;
    private final JExLogger logger;
    private final Handover handover;

    /** Runs a task on a player's thread, or {@code retired} when the player has left. */
    @FunctionalInterface
    interface PlayerThread {
        void run(@NotNull Player player, @NotNull Runnable task, @NotNull Runnable retired);
    }

    /**
     * How a stored item becomes a real one in the player's hands.
     *
     * @param encoder      turns an item into the stored bytes
     * @param decoder      reads the stored bytes
     * @param giver        puts the item into the player's inventory, on the player's thread
     * @param playerThread reaches the player's thread
     */
    record Handover(@NotNull Function<ItemStack, byte[]> encoder, @NotNull Function<byte[], ItemStack> decoder,
                    @NotNull BiConsumer<Player, ItemStack> giver, @NotNull PlayerThread playerThread) {
    }

    /**
     * Creates the store.
     *
     * @param repository the waiting items
     * @param scheduler  the platform scheduler, for inventory work on the player's thread
     * @param logger     the plugin logger
     */
    public ItemReturns(@NotNull ItemReturnRepository repository, @NotNull PlatformScheduler scheduler,
                       @NotNull JExLogger logger) {
        this(repository, logger, new Handover(ItemCodec::encode, ItemCodec::decode, ItemReturns::give,
                scheduler::runAtEntity));
    }

    ItemReturns(@NotNull ItemReturnRepository repository, @NotNull JExLogger logger, @NotNull Handover handover) {
        this.repository = repository;
        this.logger = logger;
        this.handover = handover;
    }

    /**
     * Stores an item for its owner's next join. When that fails the log line is the only record left.
     *
     * @param player the owner
     * @param item   the item
     */
    public void store(@NotNull UUID player, @NotNull ItemStack item) {
        byte[] data;
        try {
            data = handover.encoder().apply(item);
        } catch (RuntimeException unwritable) {
            reportLost(player, item, String.valueOf(unwritable.getMessage()));
            return;
        }
        repository.createAsync(new ItemReturn(player, data)).exceptionally(failure -> {
            reportLost(player, item, String.valueOf(failure.getMessage()));
            return null;
        });
    }

    /**
     * Hands every waiting item to a player who just joined.
     *
     * @param player the player
     */
    public void deliver(@NotNull Player player) {
        repository.findByPlayerAsync(player.getUniqueId()).thenAccept(rows -> deliverAll(player, rows))
                .exceptionally(failure -> {
                    logger.warn("Waiting items of {} could not be read: {}", player.getUniqueId(),
                            String.valueOf(failure.getMessage()));
                    return null;
                });
    }

    private void deliverAll(@NotNull Player player, @NotNull List<ItemReturn> rows) {
        rows.forEach(row -> deliverRow(player, row));
    }

    private void deliverRow(@NotNull Player player, @NotNull ItemReturn row) {
        ItemStack item;
        try {
            item = handover.decoder().apply(row.getItemData());
        } catch (RuntimeException unreadable) {
            logger.warn("Waiting item #{} of {} cannot be read and stays stored: {}", row.getId(),
                    player.getUniqueId(), String.valueOf(unreadable.getMessage()));
            return;
        }
        repository.claimAsync(row.getId()).thenAccept(claimed -> {
            if (Boolean.TRUE.equals(claimed)) {
                handOver(player, item);
            }
        }).exceptionally(failure -> {
            logger.warn("Waiting item #{} of {} could not be claimed and stays stored: {}", row.getId(),
                    player.getUniqueId(), String.valueOf(failure.getMessage()));
            return null;
        });
    }

    private void handOver(@NotNull Player player, @NotNull ItemStack item) {
        try {
            handover.playerThread().run(player, () -> handover.giver().accept(player, item),
                    () -> store(player.getUniqueId(), item));
        } catch (RuntimeException rejected) {
            store(player.getUniqueId(), item);
        }
    }

    private static void give(@NotNull Player player, @NotNull ItemStack item) {
        player.getInventory().addItem(item).values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        EconomyText.send(player, RETURNED_KEY);
    }

    private void reportLost(@NotNull UUID player, @NotNull ItemStack item, @NotNull String cause) {
        logger.error("Currency item of {} could not be stored for its owner, give back one {} by hand: {}", player,
                item.getType().name(), cause);
    }
}
