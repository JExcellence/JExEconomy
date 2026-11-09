package de.jexcellence.economy.coupon;

import de.jexcellence.economy.api.extension.ItemUse;
import de.jexcellence.economy.extension.ExtensionHost;
import de.jexcellence.economy.text.EconomyText;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.logging.Level;

/**
 * Right-click handler that redeems a coupon item via {@link CouponService}: the click is cancelled at once, the
 * service does the duplicate-protected insert and the payouts, and only a successful redeem consumes the stack.
 * The outcome is reported with one chat line from {@code economy-v2.coupon.redeem.*}.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public final class CouponItemListener implements Listener {

    private static final String KEY = EconomyText.ROOT + "coupon.redeem.";
    private static final String KEY_ERROR = KEY + "error";
    private static final String PARAM_TEMPLATE = "template";

    private final Plugin plugin;
    private final CouponService service;
    private final ExtensionHost extensions;
    private final PlatformScheduler scheduler;

    /**
     * Creates the listener.
     *
     * @param plugin    the plugin, for the PDC namespace and console commands
     * @param service   the coupon service
     * @param extensions decides who may redeem an item
     * @param scheduler the platform scheduler for main-thread inventory work
     */
    public CouponItemListener(@NotNull Plugin plugin, @NotNull CouponService service,
                              @NotNull ExtensionHost extensions, @NotNull PlatformScheduler scheduler) {
        this.plugin = plugin;
        this.service = service;
        this.extensions = extensions;
        this.scheduler = scheduler;
    }

    /**
     * Redeems a coupon on a main-hand right-click.
     *
     * @param event the interact event
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(@NotNull PlayerInteractEvent event) {
        ItemStack held = couponInHand(event);
        if (held == null) {
            return;
        }
        String couponId = CouponItemFactory.readCouponId(plugin, held);
        String templateId = CouponItemFactory.readTemplateId(plugin, held);
        if (couponId == null || templateId == null) {
            return;
        }
        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);
        Player player = event.getPlayer();
        ItemUse use = extensions.current().checkItemUse(held, player.getUniqueId());
        if (use != ItemUse.ALLOWED) {
            EconomyText.send(player, KEY + use.key());
            return;
        }
        service.redeem(player, couponId, templateId, null)
                .whenComplete((result, ex) -> scheduler.runAtEntity(player,
                        () -> handleResult(player, couponId, result, ex),
                        () -> finishForAbsentPlayer(player, couponId, result, ex)));
    }

    private static @Nullable ItemStack couponInHand(@NotNull PlayerInteractEvent event) {
        boolean rightClick = event.getAction() == Action.RIGHT_CLICK_AIR
                || event.getAction() == Action.RIGHT_CLICK_BLOCK;
        ItemStack held = event.getItem();
        if (event.getHand() != EquipmentSlot.HAND || !rightClick || held == null || held.getType().isAir()) {
            return null;
        }
        return held;
    }

    private void handleResult(@NotNull Player player, @NotNull String couponId,
                              @Nullable CouponService.RedeemResult result, @Nullable Throwable ex) {
        if (ex != null || result == null) {
            EconomyText.send(player, KEY_ERROR);
            return;
        }
        switch (result.status()) {
            case OK -> completeRedeem(player, couponId, result);
            case ALREADY_REDEEMED -> EconomyText.send(player, KEY + "already");
            case TEMPLATE_MISSING -> EconomyText.send(player, EconomyText.msg(KEY + "template-missing")
                    .with(PARAM_TEMPLATE, result.templateId()));
            case ERROR -> EconomyText.send(player, KEY_ERROR);
            default -> throw new IllegalStateException("Unexpected redeem status: " + result.status());
        }
    }

    /**
     * The player left before the result arrived. A paid coupon still runs its console commands; its item stays in
     * the inventory but cannot be redeemed again.
     */
    private void finishForAbsentPlayer(@NotNull Player player, @NotNull String couponId,
                                       @Nullable CouponService.RedeemResult result, @Nullable Throwable ex) {
        if (ex != null || result == null || result.status() != CouponService.Status.OK) {
            return;
        }
        plugin.getLogger().log(Level.WARNING, () -> "Coupon " + couponId + " of " + player.getName()
                + " was redeemed while the player left; its item could not be removed");
        runCommands(player.getName(), result.template());
    }

    private void completeRedeem(@NotNull Player player, @NotNull String couponId,
                                @NotNull CouponService.RedeemResult result) {
        if (!removeCoupon(player.getInventory(), couponId)) {
            plugin.getLogger().log(Level.WARNING, () -> "Coupon " + couponId + " of " + player.getName()
                    + " was redeemed, but the item was no longer in the inventory");
        }
        runCommands(player.getName(), result.template());
        EconomyText.send(player, EconomyText.msg(KEY + "done").with(PARAM_TEMPLATE, result.templateId()));
    }

    /**
     * Removes one item of exactly this coupon: the main hand first, otherwise the first inventory slot holding it
     * (the player may have moved it while the redemption ran). Another coupon in the hand is never touched.
     */
    private boolean removeCoupon(@NotNull PlayerInventory inventory, @NotNull String couponId) {
        if (isCoupon(inventory.getItemInMainHand(), couponId)) {
            inventory.setItemInMainHand(decremented(inventory.getItemInMainHand()));
            return true;
        }
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (isCoupon(contents[slot], couponId)) {
                inventory.setItem(slot, decremented(contents[slot]));
                return true;
            }
        }
        return false;
    }

    private boolean isCoupon(@Nullable ItemStack stack, @NotNull String couponId) {
        return stack != null && !stack.getType().isAir()
                && couponId.equals(CouponItemFactory.readCouponId(plugin, stack));
    }

    private static @Nullable ItemStack decremented(@NotNull ItemStack stack) {
        if (stack.getAmount() <= 1) {
            return null;
        }
        stack.setAmount(stack.getAmount() - 1);
        return stack;
    }

    /**
     * Runs a redeemed coupon's console commands ({@code {player}} becomes the redeemer's name) on the thread console
     * commands need, the main thread or on Folia the global region thread. They run after the currency payout;
     * failures are logged, not shown, because the coupon is already consumed.
     */
    private void runCommands(@NotNull String playerName, @Nullable CouponTemplate template) {
        if (template == null) {
            return;
        }
        for (String raw : template.commands()) {
            if (raw != null && !raw.isBlank()) {
                String command = raw.replace("{player}", playerName);
                scheduler.runSync(() -> dispatch(command));
            }
        }
    }

    private void dispatch(@NotNull String command) {
        try {
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), command);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, () -> "Coupon command failed '" + command + "': " + ex.getMessage());
        }
    }
}
