package de.jexcellence.economy.view;

import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.format.CurrencyFormat;
import de.jexcellence.economy.text.EconomyText;
import de.jexcellence.jexplatform.gui.component.CardLore;
import de.jexcellence.jexplatform.gui.component.FilterHopperButton;
import de.jexcellence.jexplatform.gui.style.LockedIcon;
import de.jexcellence.jexplatform.utility.item.ItemBuilder;
import de.jexcellence.jextranslate.MessageBuilder;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared building blocks of the second-generation economy views (translation root {@code economy-v2.view.}): the
 * fixed slot layouts, {@code Label | Value} rows, wrapped paragraphs, section titles, navigation, filter and locked
 * cards. Every card hides vanilla tooltip noise.
 *
 * @author JExcellence
 * @since 4.1.0
 */
public final class EconomyCards {

    /** Translation root of every economy view. */
    public static final String VIEW = EconomyText.ROOT + "view.";

    private static final String LIST_ROW = "XOOOOOOOX";

    /** Back 0, header 4, optional filter 8, 28 list slots, close 45, pages 48/50, page info 49, extra action 53. */
    static final String[] LIST_LAYOUT = {"bXXXhXXXf", LIST_ROW, LIST_ROW, LIST_ROW, LIST_ROW, "cXX<p>XXn"};

    /** Example amount for format previews. */
    static final double EXAMPLE_AMOUNT = 1234.5;

    private static final String CARD = EconomyText.ROOT + "card.";
    private static final String COMMON = VIEW + "common.";
    private static final String PARAM_VALUE = "value";
    private static final String PARAM_NAME = "name";
    private static final String NAME = ".name";
    private static final int WRAP_WIDTH = 34;

    private EconomyCards() {
    }

    /**
     * A translated item name, italic removed.
     *
     * @param viewer the viewer
     * @param key    the full key
     * @return the name
     */
    public static @NotNull Component name(@NotNull Player viewer, @NotNull String key) {
        return EconomyText.msg(key).itemComponent(viewer);
    }

    /**
     * A filled translated item name, italic removed.
     *
     * @param builder the filled builder
     * @param viewer  the viewer
     * @return the name
     */
    public static @NotNull Component name(@NotNull MessageBuilder builder, @NotNull Player viewer) {
        return builder.itemComponent(viewer);
    }

    /**
     * {@code Label | value}; the label is the plain text of {@code labelKey}.
     *
     * @param viewer    the viewer
     * @param labelKey  the label key
     * @param valueMini the value as MiniMessage
     * @return the row
     */
    public static @NotNull Component row(@NotNull Player viewer, @NotNull String labelKey, @NotNull String valueMini) {
        return EconomyText.msg(CARD + "row").with("label", EconomyText.msg(labelKey).plain(viewer))
                .with(PARAM_VALUE, valueMini).itemComponent(viewer);
    }

    /**
     * {@code Label | value} with a label that is already translated.
     *
     * @param viewer    the viewer
     * @param label     the label text
     * @param valueMini the value as MiniMessage
     * @return the row
     */
    public static @NotNull Component labelledRow(@NotNull Player viewer, @NotNull String label,
                                                 @NotNull String valueMini) {
        return EconomyText.msg(CARD + "row").with("label", label).with(PARAM_VALUE, valueMini).itemComponent(viewer);
    }

    /**
     * A section title with a text that is already translated.
     *
     * @param viewer the viewer
     * @param title  the title text
     * @return the title
     */
    public static @NotNull Component sectionTitle(@NotNull Player viewer, @NotNull String title) {
        return EconomyText.msg(CARD + "section-title").with(PARAM_NAME, title).itemComponent(viewer);
    }

    /**
     * A white plain value for a row.
     *
     * @param viewer the viewer
     * @param raw    the raw value
     * @return the value as MiniMessage
     */
    public static @NotNull String value(@NotNull Player viewer, @NotNull String raw) {
        return EconomyText.msg(CARD + PARAM_VALUE).with(PARAM_VALUE, raw).miniMessage(viewer);
    }

    /**
     * A value from a key, e.g. a coloured state.
     *
     * @param viewer the viewer
     * @param key    the full key
     * @return the value as MiniMessage
     */
    public static @NotNull String valueKey(@NotNull Player viewer, @NotNull String key) {
        return EconomyText.msg(key).miniMessage(viewer);
    }

    /**
     * The raw value, or the muted "none" marker when it is blank.
     *
     * @param viewer the viewer
     * @param raw    the raw value
     * @return the value as MiniMessage
     */
    public static @NotNull String valueOrNone(@NotNull Player viewer, @Nullable String raw) {
        return raw == null || raw.isBlank() ? valueKey(viewer, CARD + "none") : value(viewer, raw);
    }

    /**
     * A section title from {@code economy-v2.card.section.<id>}.
     *
     * @param viewer the viewer
     * @param id     the section id
     * @return the title
     */
    public static @NotNull Component section(@NotNull Player viewer, @NotNull String id) {
        return name(viewer, CARD + "section." + id);
    }

    /**
     * A paragraph of a key, word wrapped to the card width.
     *
     * @param viewer the viewer
     * @param key    the full key
     * @return the lines
     */
    public static @NotNull List<Component> paragraph(@NotNull Player viewer, @NotNull String key) {
        return paragraph(viewer, EconomyText.msg(key));
    }

    /**
     * A paragraph of a filled builder, word wrapped to the card width.
     *
     * @param viewer  the viewer
     * @param builder the filled builder
     * @return the lines
     */
    public static @NotNull List<Component> paragraph(@NotNull Player viewer, @NotNull MessageBuilder builder) {
        List<Component> lines = new ArrayList<>();
        for (String line : CardLore.wrap(builder.plain(viewer), WRAP_WIDTH)) {
            lines.add(EconomyText.msg(CARD + "line").with("text", line).itemComponent(viewer));
        }
        return lines;
    }

    /**
     * One line block, e.g. an action or a state line.
     *
     * @param viewer the viewer
     * @param key    the full key
     * @return the block
     */
    public static @NotNull List<Component> line(@NotNull Player viewer, @NotNull String key) {
        return List.of(name(viewer, key));
    }

    /**
     * A card with name and lore, vanilla tooltip noise hidden.
     *
     * @param base the base stack
     * @param name the name
     * @param lore the lore
     * @return the card
     */
    public static @NotNull ItemStack card(@NotNull ItemStack base, @NotNull Component name,
                                          @NotNull List<Component> lore) {
        return ItemBuilder.from(base).name(name).lore(lore)
                .flags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS).build();
    }

    /**
     * A card on a plain material.
     *
     * @param icon the material
     * @param name the name
     * @param lore the lore
     * @return the card
     */
    public static @NotNull ItemStack card(@NotNull Material icon, @NotNull Component name,
                                          @NotNull List<Component> lore) {
        return card(new ItemStack(icon), name, lore);
    }

    /**
     * A card on the shared {@link LockedIcon} base.
     *
     * @param viewer the viewer
     * @param name   the name
     * @param lore   the lore
     * @return the locked card
     */
    public static @NotNull ItemStack locked(@NotNull Player viewer, @NotNull Component name,
                                            @NotNull List<Component> lore) {
        return card(LockedIcon.item(viewer), name, lore);
    }

    /**
     * The locked variant of an action the viewer has no permission for: same name, reason as state.
     *
     * @param viewer  the viewer
     * @param nameKey the action's name key
     * @return the locked card
     */
    public static @NotNull ItemStack noPermission(@NotNull Player viewer, @NotNull String nameKey) {
        return locked(viewer, name(viewer, nameKey),
                CardLore.create().block(line(viewer, COMMON + "no-permission")).build());
    }

    /**
     * A navigation card: name {@code <key>.name}, one action line {@code <key>.lore}.
     *
     * @param viewer the viewer
     * @param icon   the material
     * @param key    the key base
     * @return the card
     */
    public static @NotNull ItemStack nav(@NotNull Player viewer, @NotNull Material icon, @NotNull String key) {
        return card(icon, name(viewer, key + NAME), CardLore.create().block(line(viewer, key + ".lore")).build());
    }

    /**
     * The shared close card.
     *
     * @param viewer the viewer
     * @return the card
     */
    public static @NotNull ItemStack close(@NotNull Player viewer) {
        return nav(viewer, Material.BARRIER, COMMON + "close");
    }

    /**
     * A back card with a view-specific destination ({@code <key>.name/.lore}).
     *
     * @param viewer the viewer
     * @param key    the key base
     * @return the card
     */
    public static @NotNull ItemStack back(@NotNull Player viewer, @NotNull String key) {
        return nav(viewer, Material.DARK_OAK_DOOR, key);
    }

    /**
     * The shared filter hopper: {@code ●} for the active option, {@code ○} for the others, and the cycle hint.
     *
     * @param viewer  the viewer
     * @param options the option labels
     * @param active  the active index
     * @return the card
     */
    public static @NotNull ItemStack filter(@NotNull Player viewer, @NotNull List<String> options, int active) {
        List<Component> rows = new ArrayList<>(options.size());
        for (int i = 0; i < options.size(); i++) {
            String key = COMMON + (i == active ? "filter.option-active" : "filter.option");
            rows.add(name(EconomyText.msg(key).with(PARAM_NAME, options.get(i)), viewer));
        }
        List<Component> lore = CardLore.create()
                .section(section(viewer, "show"), rows)
                .block(line(viewer, COMMON + "filter.action"))
                .build();
        return card(new ItemStack(FilterHopperButton.ICON), name(viewer, COMMON + "filter.name"), lore);
    }

    /**
     * The name of a currency card: {@code Name | id}.
     *
     * @param viewer   the viewer
     * @param currency the currency
     * @return the name
     */
    public static @NotNull Component currencyName(@NotNull Player viewer, @NotNull Currency currency) {
        return name(EconomyText.msg(COMMON + "currency-name")
                .with(PARAM_NAME, CurrencyFormat.displayName(viewer, currency))
                .with("id", currency.getIdentifier()), viewer);
    }

    /**
     * The {@code Format} rows of a currency: symbol, prefix, suffix and an example amount.
     *
     * @param viewer   the viewer
     * @param currency the currency
     * @return the rows
     */
    public static @NotNull List<Component> formatRows(@NotNull Player viewer, @NotNull Currency currency) {
        String label = EconomyText.ROOT + "field.";
        return List.of(
                row(viewer, label + "symbol.label", valueOrNone(viewer, currency.getSymbol())),
                row(viewer, label + "prefix.label", valueOrNone(viewer, currency.getPrefix())),
                row(viewer, label + "suffix.label", valueOrNone(viewer, currency.getSuffix())),
                row(viewer, COMMON + "example", CurrencyFormat.compact(viewer, currency, EXAMPLE_AMOUNT)));
    }

    /**
     * An empty draft for the create form: no id, symbol, prefix or suffix, gold ingot icon.
     *
     * @return the draft
     */
    public static @NotNull Currency blankDraft() {
        return new Currency("", "", "", "", Material.GOLD_INGOT.name());
    }

    /**
     * Resolves a material name, gold ingot when blank or unknown.
     *
     * @param name the material name
     * @return the material
     */
    public static @NotNull Material material(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return Material.GOLD_INGOT;
        }
        Material material = Material.matchMaterial(name);
        return material != null && material.isItem() ? material : Material.GOLD_INGOT;
    }
}
