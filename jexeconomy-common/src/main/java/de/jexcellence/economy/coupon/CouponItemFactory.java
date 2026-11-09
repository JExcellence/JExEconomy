package de.jexcellence.economy.coupon;

import de.jexcellence.economy.format.CurrencyFormat;
import de.jexcellence.economy.service.EconomyService;
import de.jexcellence.economy.text.EconomyText;
import de.jexcellence.jexplatform.gui.component.CardLore;
import de.jexcellence.jexplatform.utility.item.ItemMetaText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds physical coupon items. Each item carries three PDC tags:
 * <ul>
 *   <li>{@code jexeconomy:coupon_id} - UUIDv4 string, globally unique</li>
 *   <li>{@code jexeconomy:coupon_template} - template identifier</li>
 *   <li>{@code jexeconomy:coupon_minted_at} - epoch millis</li>
 * </ul>
 *
 * <p>The lore is a card: the template's own lines, a {@code Contains} section with one {@code Currency | amount}
 * row per payout, and the redeem action. Vanilla tooltip noise is hidden.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public final class CouponItemFactory {

    public static final String KEY_COUPON_ID = "coupon_id";
    public static final String KEY_TEMPLATE = "coupon_template";
    public static final String KEY_MINTED_AT = "coupon_minted_at";

    private static final String KEY = EconomyText.ROOT + "coupon.item.";
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private CouponItemFactory() {
    }

    /**
     * Mints a new coupon with a fresh UUID. The caller gives the item to the player.
     *
     * @param plugin   plugin instance for PDC namespacing
     * @param template the coupon template defining display and payouts
     * @param economy  economy service, to format the payouts
     * @param viewer   the receiving player, for the lore locale; {@code null} for the default locale
     * @return the new coupon stack
     */
    public static @NotNull ItemStack mint(@NotNull Plugin plugin, @NotNull CouponTemplate template,
                                          @NotNull EconomyService economy, @Nullable Player viewer) {
        ItemStack stack = new ItemStack(template.icon());
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        ItemMetaText.setName(meta, item(MINI.deserialize(template.displayName())));
        ItemMetaText.setLore(meta, lore(template, economy, viewer));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        if (template.glint()) {
            meta.setEnchantmentGlintOverride(true);
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(new NamespacedKey(plugin, KEY_COUPON_ID), PersistentDataType.STRING, UUID.randomUUID().toString());
        pdc.set(new NamespacedKey(plugin, KEY_TEMPLATE), PersistentDataType.STRING, template.id());
        pdc.set(new NamespacedKey(plugin, KEY_MINTED_AT), PersistentDataType.LONG, System.currentTimeMillis());
        stack.setItemMeta(meta);
        return stack;
    }

    private static @NotNull List<Component> lore(@NotNull CouponTemplate template, @NotNull EconomyService economy,
                                                 @Nullable Player viewer) {
        List<Component> intro = new ArrayList<>();
        for (String line : template.lore()) {
            intro.add(item(MINI.deserialize(line)));
        }
        List<Component> payouts = new ArrayList<>();
        for (CouponTemplate.CouponPayout payout : template.payouts()) {
            payouts.add(payoutRow(payout, economy, viewer));
        }
        return CardLore.create()
                .block(intro)
                .section(EconomyText.msg(KEY + "contains").itemComponent(viewer), payouts)
                .block(List.of(EconomyText.msg(KEY + "action").itemComponent(viewer)))
                .build();
    }

    private static @NotNull Component payoutRow(@NotNull CouponTemplate.CouponPayout payout,
                                                @NotNull EconomyService economy, @Nullable Player viewer) {
        var currency = economy.findCurrency(payout.currency());
        String label = currency.map(found -> CurrencyFormat.displayName(viewer, found)).orElse(payout.currency());
        String value = currency.map(found -> CurrencyFormat.compact(viewer, found, payout.amount()))
                .orElseGet(() -> CurrencyFormat.amount(payout.amount()));
        return EconomyText.msg(EconomyText.ROOT + "card.row").with("label", label).with("value", value)
                .itemComponent(viewer);
    }

    private static @NotNull Component item(@NotNull Component line) {
        return line.decoration(TextDecoration.ITALIC, false);
    }

    /**
     * Reads the coupon UUID.
     *
     * @param plugin plugin instance for PDC namespacing
     * @param stack  any stack
     * @return the coupon id, {@code null} when the stack is not a coupon
     */
    public static @Nullable String readCouponId(@NotNull Plugin plugin, @NotNull ItemStack stack) {
        return readString(plugin, stack, KEY_COUPON_ID);
    }

    /**
     * Reads the template identifier.
     *
     * @param plugin plugin instance for PDC namespacing
     * @param stack  any stack
     * @return the template id, {@code null} when the stack is not a coupon
     */
    public static @Nullable String readTemplateId(@NotNull Plugin plugin, @NotNull ItemStack stack) {
        return readString(plugin, stack, KEY_TEMPLATE);
    }

    private static @Nullable String readString(@NotNull Plugin plugin, @NotNull ItemStack stack, @NotNull String key) {
        if (!stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer()
                .get(new NamespacedKey(plugin, key), PersistentDataType.STRING);
    }
}
