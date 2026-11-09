package de.jexcellence.economy.view;

import de.jexcellence.economy.JExEconomy;
import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.format.CurrencyFormat;
import de.jexcellence.economy.text.EconomyText;
import de.jexcellence.jexplatform.gui.component.CardLore;
import de.jexcellence.jexplatform.view.BaseView;
import me.devnatan.inventoryframework.View;
import me.devnatan.inventoryframework.context.RenderContext;
import me.devnatan.inventoryframework.context.SlotClickContext;
import me.devnatan.inventoryframework.state.State;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared layout of the create and edit forms: back 0, header 4 with a live format preview, the five field cards
 * in the middle row, the save action below and close 45. A field click opens the anvil input when the server
 * supports it, otherwise the chat prompt. The save card is locked until identifier and symbol are set.
 *
 * @author JExcellence
 * @since 4.1.0
 */
abstract class CurrencyFormView extends BaseView {

    static final String PLUGIN = "plugin";
    static final String DRAFT = "draft_currency";
    static final String ORIGINAL = "original_identifier";

    private static final String KEY = EconomyCards.VIEW + "form.";
    private static final String FIELD = EconomyText.ROOT + "field.";
    private static final String NAME = ".name";

    private record FieldSlot(char slot, @NotNull String field, @NotNull Material icon) {
    }

    private static final List<FieldSlot> FIELDS = List.of(
            new FieldSlot('n', "identifier", Material.NAME_TAG),
            new FieldSlot('s', "symbol", Material.GOLD_NUGGET),
            new FieldSlot('i', "icon", Material.ITEM_FRAME),
            new FieldSlot('l', "prefix", Material.WRITABLE_BOOK),
            new FieldSlot('t', "suffix", Material.PAPER));

    protected final State<JExEconomy> economy = initialState(PLUGIN);
    protected final State<Currency> draft = initialState(DRAFT);

    protected CurrencyFormView(@NotNull Class<? extends View> parent) {
        super(parent);
    }

    /** The form mode, {@link CurrencyFieldInputView#MODE_CREATE} or {@link CurrencyFieldInputView#MODE_EDIT}. */
    protected abstract @NotNull String mode();

    /** The identifier the save looks the currency up by, {@code null} when creating. */
    protected abstract @Nullable String originalIdentifier(@NotNull RenderContext render);

    /** Persists the draft and reports the result in chat. */
    protected abstract void save(@NotNull SlotClickContext click, @NotNull Currency currency);

    @Override
    protected String[] layout() {
        return new String[]{
                "bXXXhXXXX",
                "XXXXXXXXX",
                "XXnsiltXX",
                "XXXXXXXXX",
                "XXXXwXXXX",
                "cXXXXXXXX"
        };
    }

    @Override
    protected Material fillerMaterial() {
        return Material.BLACK_STAINED_GLASS_PANE;
    }

    @Override
    protected void onRender(@NotNull RenderContext render, @NotNull Player player) {
        Currency cur = draft.get(render);
        String modeKey = KEY + mode() + ".";
        render.layoutSlot('b', EconomyCards.back(player, modeKey + "back")).onClick(this::handleBack);
        render.layoutSlot('c', EconomyCards.close(player)).onClick(click -> click.closeForPlayer());
        render.layoutSlot('h', header(player, cur, modeKey));
        String original = originalIdentifier(render);
        for (FieldSlot field : FIELDS) {
            render.layoutSlot(field.slot(), fieldCard(player, cur, field))
                    .onClick(click -> openField(click, cur, field.field(), original));
        }
        renderSave(render, player, cur, modeKey);
    }

    private static @NotNull ItemStack header(@NotNull Player player, @NotNull Currency cur, @NotNull String modeKey) {
        String preview = cur.getIdentifier().isBlank() && cur.getSymbol().isBlank()
                ? EconomyCards.valueOrNone(player, null)
                : CurrencyFormat.compact(player, cur, EconomyCards.EXAMPLE_AMOUNT);
        return EconomyCards.card(Material.NETHER_STAR,
                EconomyCards.name(EconomyText.msg(modeKey + "header.name")
                        .with("currency", cur.getIdentifier()), player),
                CardLore.create()
                        .block(EconomyCards.paragraph(player, modeKey + "header.description"))
                        .section(EconomyCards.section(player, "preview"),
                                List.of(EconomyCards.row(player, EconomyCards.VIEW + "common.example", preview)))
                        .build());
    }

    private static @NotNull ItemStack fieldCard(@NotNull Player player, @NotNull Currency cur,
                                                @NotNull FieldSlot field) {
        String value = CurrencyFieldInputView.fieldValue(cur, field.field());
        Material icon = "icon".equals(field.field()) ? EconomyCards.material(value) : field.icon();
        return EconomyCards.card(icon, EconomyCards.name(player, FIELD + field.field() + NAME),
                CardLore.create()
                        .block(EconomyCards.paragraph(player, FIELD + field.field() + ".description"))
                        .section(EconomyCards.section(player, "now"), List.of(EconomyCards.row(player,
                                KEY + "label.value", EconomyCards.valueOrNone(player, value))))
                        .block(EconomyCards.line(player, KEY + "field-action"))
                        .build());
    }

    private void renderSave(@NotNull RenderContext render, @NotNull Player player, @NotNull Currency cur,
                            @NotNull String modeKey) {
        String nameKey = modeKey + "save.name";
        if (cur.getIdentifier().isBlank() || cur.getSymbol().isBlank()) {
            render.layoutSlot('w', EconomyCards.locked(player, EconomyCards.name(player, nameKey),
                    CardLore.create()
                            .block(EconomyCards.paragraph(player, modeKey + "save.description"))
                            .block(EconomyCards.line(player, KEY + "save-missing"))
                            .build()));
            return;
        }
        render.layoutSlot('w', EconomyCards.card(Material.EMERALD, EconomyCards.name(player, nameKey),
                        CardLore.create()
                                .block(EconomyCards.paragraph(player, modeKey + "save.description"))
                                .block(EconomyCards.line(player, modeKey + "save.action"))
                                .build()))
                .onClick(click -> {
                    click.closeForPlayer();
                    save(click, cur);
                });
    }

    private void openField(@NotNull SlotClickContext click, @NotNull Currency cur, @NotNull String field,
                           @Nullable String original) {
        JExEconomy eco = economy.get(click);
        if (!eco.anvilSupported()) {
            eco.chatInput().prompt(click.getPlayer(), field, cur, mode(), original);
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put(PLUGIN, eco);
        data.put("field", field);
        data.put(DRAFT, cur);
        data.put("mode", mode());
        if (original != null) {
            data.put(ORIGINAL, original);
        }
        click.openForPlayer(CurrencyFieldInputView.class, data);
    }
}
