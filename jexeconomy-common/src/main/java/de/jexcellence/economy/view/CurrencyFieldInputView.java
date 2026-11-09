package de.jexcellence.economy.view;

import de.jexcellence.economy.JExEconomy;
import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.service.currency.ReservedCurrencyNames;
import de.jexcellence.economy.text.EconomyText;
import de.jexcellence.jexplatform.utility.item.ItemMetaText;
import me.devnatan.inventoryframework.AnvilInput;
import me.devnatan.inventoryframework.View;
import me.devnatan.inventoryframework.ViewConfigBuilder;
import me.devnatan.inventoryframework.ViewType;
import me.devnatan.inventoryframework.context.OpenContext;
import me.devnatan.inventoryframework.context.RenderContext;
import me.devnatan.inventoryframework.context.SlotClickContext;
import me.devnatan.inventoryframework.state.State;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Anvil text input for one currency field (identifier, symbol, prefix, suffix, icon). Opened by the create and
 * edit forms; on confirm the value is applied to a fresh copy of the draft and the form reopens with it.
 *
 * <p>Required initial data: {@code plugin}, {@code field}, {@code draft_currency}, {@code mode} and, in edit mode,
 * {@code original_identifier}.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class CurrencyFieldInputView extends View {

    /** Create mode: the input returns to {@link CurrencyCreateView}. */
    public static final String MODE_CREATE = "create";
    /** Edit mode: the input returns to {@link CurrencyEditView}. */
    public static final String MODE_EDIT = "edit";

    private static final String FIELD_IDENTIFIER = "identifier";
    private static final String FIELD_SYMBOL = "symbol";
    private static final String FIELD_PREFIX = "prefix";
    private static final String FIELD_SUFFIX = "suffix";
    private static final String FIELD_ICON = "icon";
    private static final String KEY = EconomyText.ROOT + "view.input.";

    private final AnvilInput anvilInput = AnvilInput.createAnvilInput();
    private final State<JExEconomy> economy = initialState(CurrencyFormView.PLUGIN);
    private final State<String> field = initialState("field");
    private final State<Currency> draft = initialState(CurrencyFormView.DRAFT);
    private final State<String> mode = initialState("mode");

    @Override
    public void onInit(@NotNull ViewConfigBuilder config) {
        config.type(ViewType.ANVIL).cancelOnClick().use(anvilInput).title("");
    }

    @Override
    public void onOpen(@NotNull OpenContext open) {
        Component title = EconomyText.msg(KEY + "title")
                .with("field", fieldLabel(open.getPlayer(), field.get(open)))
                .component(open.getPlayer());
        open.modifyConfig().title(LegacyComponentSerializer.legacySection().serialize(title));
    }

    @Override
    public void onFirstRender(@NotNull RenderContext render) {
        String current = fieldValue(draft.get(render), field.get(render));
        ItemStack paper = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = paper.getItemMeta();
        if (meta != null) {
            ItemMetaText.setName(meta, Component.text(current.isEmpty() ? " " : current));
            paper.setItemMeta(meta);
        }
        render.firstSlot(paper);
        render.resultSlot().onClick(this::confirm);
        if (!current.isEmpty()) {
            anvilInput.set(current, render);
        }
    }

    private void confirm(@NotNull SlotClickContext ctx) {
        String input = anvilInput.get(ctx);
        if (input == null || input.isBlank()) {
            EconomyText.send(ctx.getPlayer(), KEY + "empty");
            return;
        }
        String fieldName = field.get(ctx);
        if (FIELD_ICON.equals(fieldName) && !isValidMaterial(input)) {
            EconomyText.send(ctx.getPlayer(), EconomyText.msg(KEY + "invalid-material").with("value", input));
            return;
        }
        if (rejectReservedIdentifier(ctx.getPlayer(), fieldName, input)) {
            return;
        }
        JExEconomy eco = economy.get(ctx);
        Currency updated = applyField(draft.get(ctx), fieldName, input.trim());
        if (MODE_CREATE.equals(mode.get(ctx))) {
            ctx.openForPlayer(CurrencyCreateView.class,
                    Map.of(CurrencyFormView.PLUGIN, eco, CurrencyFormView.DRAFT, updated));
            return;
        }
        ctx.openForPlayer(CurrencyEditView.class, Map.of(CurrencyFormView.PLUGIN, eco,
                CurrencyFormView.DRAFT, updated, CurrencyFormView.ORIGINAL, originalIdentifier(ctx)));
    }

    private @NotNull String originalIdentifier(@NotNull SlotClickContext ctx) {
        if (ctx.getInitialData() instanceof Map<?, ?> data && data.get(CurrencyFormView.ORIGINAL) != null) {
            return data.get(CurrencyFormView.ORIGINAL).toString();
        }
        return draft.get(ctx).getIdentifier();
    }

    /**
     * Refuses an identifier that is reserved for a {@code /balance} subcommand and tells the sender why.
     *
     * @param sender the player or console that typed the value
     * @param field  the field id
     * @param value  the typed value
     * @return {@code true} when the value was refused
     */
    public static boolean rejectReservedIdentifier(@NotNull CommandSender sender, @NotNull String field,
                                                   @NotNull String value) {
        if (!FIELD_IDENTIFIER.equals(field) || !ReservedCurrencyNames.isReserved(value)) {
            return false;
        }
        EconomyText.send(sender, EconomyText.msg(EconomyText.ROOT + "error.reserved-currency-name")
                .with("currency", value.trim())
                .with("names", String.join(", ", ReservedCurrencyNames.names())));
        return true;
    }

    /**
     * The current value of a field, empty for an unknown field.
     *
     * @param cur   the currency
     * @param field the field id
     * @return the value
     */
    public static @NotNull String fieldValue(@NotNull Currency cur, @NotNull String field) {
        return switch (field) {
            case FIELD_IDENTIFIER -> cur.getIdentifier();
            case FIELD_SYMBOL -> cur.getSymbol();
            case FIELD_PREFIX -> cur.getPrefix();
            case FIELD_SUFFIX -> cur.getSuffix();
            case FIELD_ICON -> cur.getIcon();
            default -> "";
        };
    }

    /**
     * A fresh copy of {@code cur} with {@code field} set to {@code value}; the draft is never mutated in place.
     *
     * @param cur   the currency
     * @param field the field id
     * @param value the new value
     * @return the copy
     */
    public static @NotNull Currency applyField(@NotNull Currency cur, @NotNull String field, @NotNull String value) {
        String id = cur.getIdentifier();
        String symbol = cur.getSymbol();
        String prefix = cur.getPrefix();
        String suffix = cur.getSuffix();
        String icon = cur.getIcon();
        return switch (field) {
            case FIELD_IDENTIFIER -> new Currency(value, symbol, prefix, suffix, icon);
            case FIELD_SYMBOL -> new Currency(id, value, prefix, suffix, icon);
            case FIELD_PREFIX -> new Currency(id, symbol, value, suffix, icon);
            case FIELD_SUFFIX -> new Currency(id, symbol, prefix, value, icon);
            case FIELD_ICON -> new Currency(id, symbol, prefix, suffix, value);
            default -> new Currency(id, symbol, prefix, suffix, icon);
        };
    }

    /**
     * The translated label of a field.
     *
     * @param viewer the viewer, {@code null} for the default locale
     * @param field  the field id
     * @return the label
     */
    public static @NotNull String fieldLabel(@Nullable Player viewer, @NotNull String field) {
        return EconomyText.msg(EconomyText.ROOT + "field." + field + ".label").plain(viewer);
    }

    /**
     * Whether a text names an item material.
     *
     * @param name the material name
     * @return {@code true} for a valid item material
     */
    public static boolean isValidMaterial(@NotNull String name) {
        Material material = Material.matchMaterial(name.trim());
        return material != null && material.isItem();
    }
}
