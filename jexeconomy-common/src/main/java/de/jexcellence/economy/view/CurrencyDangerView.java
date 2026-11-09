package de.jexcellence.economy.view;

import de.jexcellence.economy.JExEconomy;
import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.format.CurrencyFormat;
import de.jexcellence.economy.text.EconomyText;
import de.jexcellence.jexplatform.gui.component.CardLore;
import de.jexcellence.jexplatform.view.BaseView;
import me.devnatan.inventoryframework.context.OpenContext;
import me.devnatan.inventoryframework.context.RenderContext;
import me.devnatan.inventoryframework.context.SlotClickContext;
import me.devnatan.inventoryframework.state.State;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Confirmation for the two destructive currency actions: delete the currency, or reset every balance of it to
 * zero. The header names the consequence; confirm runs the action, keep returns to the currency.
 *
 * @author JExcellence
 * @since 4.1.0
 */
public class CurrencyDangerView extends BaseView {

    /** Initial-data key of the mode. */
    public static final String MODE = "mode";
    /** Deletes the currency and every account of it. */
    public static final String MODE_DELETE = "delete";
    /** Sets every balance of the currency to zero. */
    public static final String MODE_RESET = "reset";

    private static final String KEY = EconomyCards.VIEW + "danger.";
    private static final String FILLER_ROW = "XXXXXXXXX";
    private static final String MSG = EconomyText.ROOT + "currencies.";
    private static final String PARAM_CURRENCY = "currency";

    private final State<JExEconomy> economy = initialState("plugin");
    private final State<Currency> currency = initialState(PARAM_CURRENCY);
    private final State<String> mode = initialState(MODE);

    /**
     * Creates the view; back leads to the currency detail.
     */
    public CurrencyDangerView() {
        super(CurrencyDetailView.class);
    }

    @Override
    protected String translationKey() {
        return "economy-v2.view.danger";
    }

    @Override
    protected String[] layout() {
        return new String[]{
                "bXXXhXXXX",
                FILLER_ROW,
                "XXXyXzXXX",
                FILLER_ROW,
                FILLER_ROW,
                "cXXXXXXXX"
        };
    }

    @Override
    protected Material fillerMaterial() {
        return Material.BLACK_STAINED_GLASS_PANE;
    }

    @Override
    protected Map<String, Object> titlePlaceholders(@NotNull OpenContext open) {
        return Map.of(PARAM_CURRENCY, CurrencyFormat.displayName(open.getPlayer(), currency.get(open)));
    }

    @Override
    protected void onRender(@NotNull RenderContext render, @NotNull Player player) {
        Currency cur = currency.get(render);
        String modeKey = KEY + mode.get(render) + ".";
        String name = CurrencyFormat.displayName(player, cur);
        render.layoutSlot('b', EconomyCards.back(player, KEY + "back")).onClick(this::handleBack);
        render.layoutSlot('c', EconomyCards.close(player)).onClick(click -> click.closeForPlayer());
        render.layoutSlot('h', EconomyCards.card(Material.TNT,
                EconomyCards.name(EconomyText.msg(modeKey + "header.name").with(PARAM_CURRENCY, name), player),
                CardLore.create()
                        .block(EconomyCards.paragraph(player,
                                EconomyText.msg(modeKey + "header.description").with(PARAM_CURRENCY, name)))
                        .block(EconomyCards.line(player, EconomyCards.VIEW + "detail.irreversible"))
                        .build()));
        render.layoutSlot('y', EconomyCards.card(Material.RED_CONCRETE,
                        EconomyCards.name(player, modeKey + "confirm.name"),
                        CardLore.create()
                                .block(EconomyCards.paragraph(player, modeKey + "confirm.description"))
                                .block(EconomyCards.line(player, KEY + "confirm-action"))
                                .build()))
                .onClick(this::confirm);
        render.layoutSlot('z', EconomyCards.card(Material.LIME_CONCRETE,
                        EconomyCards.name(player, KEY + "keep.name"),
                        CardLore.create().block(EconomyCards.line(player, KEY + "keep.lore")).build()))
                .onClick(this::handleBack);
    }

    private void confirm(@NotNull SlotClickContext click) {
        Player player = click.getPlayer();
        Currency cur = currency.get(click);
        var service = economy.get(click).economyService();
        String id = cur.getIdentifier();
        click.closeForPlayer();
        if (MODE_DELETE.equals(mode.get(click))) {
            service.deleteCurrency(id, player).thenAccept(success -> EconomyText.send(player,
                    EconomyText.msg(MSG + (Boolean.TRUE.equals(success) ? "delete.done" : "delete.failed"))
                            .with(PARAM_CURRENCY, id)));
            return;
        }
        service.resetCurrency(id, "Reset by " + player.getName()).thenAccept(count -> {
            boolean refused = count == null || count < 0;
            EconomyText.send(player, EconomyText.msg(MSG + (refused ? "reset.refused" : "reset.done"))
                    .with(PARAM_CURRENCY, CurrencyFormat.displayName(player, cur))
                    .with("count", refused ? 0 : count));
        });
    }
}
