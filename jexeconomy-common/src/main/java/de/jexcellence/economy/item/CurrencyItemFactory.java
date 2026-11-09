package de.jexcellence.economy.item;

import de.jexcellence.economy.format.CurrencyFormat;
import de.jexcellence.economy.text.EconomyText;
import de.jexcellence.jexplatform.gui.component.CardLore;
import de.jexcellence.jexplatform.utility.item.ItemMetaText;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
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

import java.util.List;

/**
 * Builds physical {@link ItemStack}s that act as a deposit slip for a named currency. The PDC carries the currency
 * identifier and the amount to grant; {@link CurrencyItemListener} reads those keys on right-click to redeem the
 * item into the player's balance.
 *
 * <p>Name and lore come from {@code economy-v2.item.<currency_id>.{name,description}} so operators can re-skin
 * currency items without touching code; unknown currencies use {@code economy-v2.item.generic}.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public final class CurrencyItemFactory {

    /** Namespace for all JExEconomy PDC keys. */
    public static final String NAMESPACE = "jexeconomy";

    /** Currency identifier, e.g. {@code "coins"}. */
    public static final String KEY_CURRENCY = "currency";

    /** Amount of the currency to grant on redeem. Stored as a Long. */
    public static final String KEY_AMOUNT = "currency_amount";

    private static final String KEY = EconomyText.ROOT + "item.";
    private static final String PARAM_AMOUNT = "amount";

    private CurrencyItemFactory() {
    }

    /**
     * Builds a physical currency item with the given icon.
     *
     * @param plugin       the JExEconomy plugin instance (PDC namespace)
     * @param player       player viewing the item (locale)
     * @param currency     currency identifier, e.g. {@code "coins"}
     * @param amount       amount granted on redeem
     * @param iconMaterial the item icon, {@code null} for the built-in fallback
     * @return a new stack carrying the redeem PDC tags
     */
    public static @NotNull ItemStack build(@NotNull Plugin plugin, @NotNull Player player, @NotNull String currency,
                                           long amount, @Nullable Material iconMaterial) {
        ItemStack stack = new ItemStack(iconMaterial != null ? iconMaterial : Material.GOLD_NUGGET);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        String family = familyKey(player, currency);
        String amountText = CurrencyFormat.amount(amount);
        ItemMetaText.setName(meta,
                EconomyText.msg(family + ".name").with(PARAM_AMOUNT, amountText).itemComponent(player));
        ItemMetaText.setLore(meta, lore(player, family, amountText));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(new NamespacedKey(plugin, KEY_CURRENCY), PersistentDataType.STRING, currency);
        pdc.set(new NamespacedKey(plugin, KEY_AMOUNT), PersistentDataType.LONG, amount);
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * Builds a currency item with the built-in icon for the identifier.
     *
     * @param plugin   the JExEconomy plugin instance
     * @param player   player viewing the item
     * @param currency currency identifier
     * @param amount   amount granted on redeem
     * @return a new stack carrying the redeem PDC tags
     */
    public static @NotNull ItemStack build(@NotNull Plugin plugin, @NotNull Player player, @NotNull String currency,
                                           long amount) {
        return build(plugin, player, currency, amount, iconFor(currency));
    }

    /**
     * Reads the currency identifier from a stack.
     *
     * @param plugin the plugin, for the PDC namespace
     * @param stack  any stack
     * @return the identifier, {@code null} when the stack is not a currency item
     */
    public static @Nullable String readCurrency(@NotNull Plugin plugin, @NotNull ItemStack stack) {
        if (!stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer()
                .get(new NamespacedKey(plugin, KEY_CURRENCY), PersistentDataType.STRING);
    }

    /**
     * Reads the redeem amount from a stack.
     *
     * @param plugin the plugin, for the PDC namespace
     * @param stack  any stack
     * @return the amount, {@code 0} when the stack is not a currency item
     */
    public static long readAmount(@NotNull Plugin plugin, @NotNull ItemStack stack) {
        if (!stack.hasItemMeta()) {
            return 0;
        }
        Long amount = stack.getItemMeta().getPersistentDataContainer()
                .get(new NamespacedKey(plugin, KEY_AMOUNT), PersistentDataType.LONG);
        return amount != null ? amount : 0;
    }

    /**
     * Resolves a material from a currency's icon field.
     *
     * @param iconName the material name
     * @return the material, {@code null} when blank or unknown
     */
    public static @Nullable Material resolveIcon(@Nullable String iconName) {
        if (iconName == null || iconName.isBlank()) {
            return null;
        }
        return Material.matchMaterial(iconName);
    }

    private static @NotNull List<Component> lore(@NotNull Player player, @NotNull String family,
                                                 @NotNull String amountText) {
        Component worth = EconomyText.msg(EconomyText.ROOT + "card.row")
                .with("label", EconomyText.msg(KEY + "worth").plain(player))
                .with("value", EconomyText.msg(family + ".value").with(PARAM_AMOUNT, amountText).miniMessage(player))
                .itemComponent(player);
        return CardLore.create()
                .block(List.of(EconomyText.msg(family + ".description").itemComponent(player)))
                .block(List.of(worth))
                .block(List.of(EconomyText.msg(KEY + "action").itemComponent(player)))
                .build();
    }

    private static @NotNull String familyKey(@NotNull Player player, @NotNull String currency) {
        String specific = KEY + currency;
        return EconomyText.msg(specific + ".name").exists(player) ? specific : KEY + "generic";
    }

    private static @NotNull Material iconFor(@NotNull String currency) {
        return switch (currency) {
            case "coins" -> Material.GOLD_NUGGET;
            case "crystals" -> Material.AMETHYST_SHARD;
            default -> Material.PAPER;
        };
    }
}
