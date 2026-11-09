package de.jexcellence.economy.item;

import de.jexcellence.economy.api.EconomyProvider;
import de.jexcellence.economy.api.TransactionResult;
import de.jexcellence.economy.api.extension.ItemUse;
import de.jexcellence.economy.extension.ExtensionHost;
import de.jexcellence.economy.text.EconomyText;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.logging.Level;

/**
 * Right-click handler that redeems a physical currency item into the player's balance. The item's PDC (set by
 * {@link CurrencyItemFactory}) carries the currency identifier and the amount.
 *
 * <p>The item leaves the hand on the player's thread before the deposit starts, and only after its currency and
 * amount were checked again, so a second click cannot redeem the same item while the first deposit is still running.
 * A {@link RedemptionGuard} allows one redemption per player at a time. When the deposit fails, the item is given
 * back (dropped at the player's feet when the inventory is full). A player who left in the meantime gets it on the
 * next join, see {@link ItemReturns}. Every intercepted click ends in a chat line.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public final class CurrencyItemListener implements Listener {

    private static final String KEY = EconomyText.ROOT + "item.redeem.";
    private static final String PARAM_CURRENCY = "currency";
    private static final String PARAM_AMOUNT = "amount";
    private static final String REDEEM_REASON = "currency-item-redeem";

    private final Plugin plugin;
    private final EconomyProvider economy;
    private final ExtensionHost extensions;
    private final PlatformScheduler scheduler;
    private final ItemReturns returns;
    private final RedemptionGuard guard = new RedemptionGuard();

    /** The currency and amount of the redeemed item, and the single item that left the hand. */
    private record Redemption(@NotNull String currency, long amount, @NotNull ItemStack taken) {
    }

    /**
     * Creates the listener.
     *
     * @param plugin    the plugin, for the PDC namespace
     * @param economy   the economy provider
     * @param extensions decides who may redeem an item
     * @param scheduler the platform scheduler for inventory work on the player's thread
     * @param returns   keeps items of players who left before their redemption failed
     */
    public CurrencyItemListener(@NotNull Plugin plugin, @NotNull EconomyProvider economy,
                                @NotNull ExtensionHost extensions, @NotNull PlatformScheduler scheduler,
                                @NotNull ItemReturns returns) {
        this.plugin = plugin;
        this.economy = economy;
        this.extensions = extensions;
        this.scheduler = scheduler;
        this.returns = returns;
    }

    /**
     * Hands back items whose redemption failed while the player was away.
     *
     * @param event the join event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        returns.deliver(event.getPlayer());
    }

    /**
     * Redeems a currency item on a main-hand right-click.
     *
     * @param event the interact event
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(@NotNull PlayerInteractEvent event) {
        ItemStack held = redeemableItem(event);
        if (held == null) {
            return;
        }
        String currency = CurrencyItemFactory.readCurrency(plugin, held);
        long amount = CurrencyItemFactory.readAmount(plugin, held);
        if (currency == null || amount <= 0) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        ItemUse use = extensions.current().checkItemUse(held, player.getUniqueId());
        if (use != ItemUse.ALLOWED) {
            EconomyText.send(player, KEY + use.key());
            return;
        }
        if (!guard.tryBegin(player.getUniqueId())) {
            EconomyText.send(player, KEY + "busy");
            return;
        }
        ItemStack taken = takeOne(player, currency, amount);
        if (taken == null) {
            guard.finish(player.getUniqueId());
            return;
        }
        var redemption = new Redemption(currency, amount, taken);
        economy.deposit(player, currency, (double) amount, player, REDEEM_REASON)
                .whenComplete((result, failure) -> {
                    guard.finish(player.getUniqueId());
                    scheduler.runAtEntity(player, () -> settle(player, redemption, result, failure),
                            () -> reportAbsentPlayer(player, redemption, result, failure));
                });
    }

    private static @Nullable ItemStack redeemableItem(@NotNull PlayerInteractEvent event) {
        boolean rightClick = event.getAction() == Action.RIGHT_CLICK_AIR
                || event.getAction() == Action.RIGHT_CLICK_BLOCK;
        ItemStack held = event.getItem();
        if (event.getHand() != EquipmentSlot.HAND || !rightClick || held == null || held.getType().isAir()) {
            return null;
        }
        return held;
    }

    /**
     * Removes one item from the main hand when it still is the clicked currency item with the same amount.
     *
     * @return the removed single item, or {@code null} when the hand changed
     */
    private @Nullable ItemStack takeOne(@NotNull Player player, @NotNull String currency, long amount) {
        ItemStack current = player.getInventory().getItemInMainHand();
        if (current.getType().isAir()
                || !currency.equals(CurrencyItemFactory.readCurrency(plugin, current))
                || CurrencyItemFactory.readAmount(plugin, current) != amount) {
            return null;
        }
        ItemStack single = current.clone();
        single.setAmount(1);
        if (current.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            current.setAmount(current.getAmount() - 1);
        }
        return single;
    }

    private void settle(@NotNull Player player, @NotNull Redemption redemption, @Nullable TransactionResult result,
                        @Nullable Throwable failure) {
        if (failure == null && result != null && result.isSuccess()) {
            EconomyText.send(player, EconomyText.msg(KEY + "done")
                    .with(PARAM_CURRENCY, redemption.currency()).with(PARAM_AMOUNT, redemption.amount()));
            return;
        }
        giveBack(player, redemption);
        EconomyText.send(player, EconomyText.msg(KEY + "failed")
                .with(PARAM_CURRENCY, redemption.currency()).with(PARAM_AMOUNT, redemption.amount()));
    }

    /** The player left before the result arrived, so a failed deposit returns the item on the next join. */
    private void reportAbsentPlayer(@NotNull Player player, @NotNull Redemption redemption,
                                    @Nullable TransactionResult result, @Nullable Throwable failure) {
        if (failure == null && result != null && result.isSuccess()) {
            return;
        }
        plugin.getLogger().log(Level.INFO, () -> "Currency item redeem failed while " + player.getName()
                + " was offline; one " + redemption.currency() + " item worth " + redemption.amount()
                + " waits for the next join");
        returns.store(player.getUniqueId(), redemption.taken());
    }

    private void giveBack(@NotNull Player player, @NotNull Redemption redemption) {
        var leftovers = player.getInventory().addItem(redemption.taken());
        leftovers.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
    }
}
