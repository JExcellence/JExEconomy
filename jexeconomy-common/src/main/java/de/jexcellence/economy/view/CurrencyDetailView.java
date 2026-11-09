package de.jexcellence.economy.view;

import de.jexcellence.economy.JExEconomy;
import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.permission.EconomyPermission;
import de.jexcellence.jexplatform.gui.component.CardLore;
import de.jexcellence.jexplatform.view.BaseView;
import me.devnatan.inventoryframework.context.OpenContext;
import me.devnatan.inventoryframework.context.RenderContext;
import me.devnatan.inventoryframework.state.State;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * One currency: its format in the header, then the actions top holders, edit, reset all balances and delete.
 * Actions the viewer lacks the permission for show as locked cards.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class CurrencyDetailView extends BaseView {

    private static final String KEY = EconomyCards.VIEW + "detail.";
    private static final String PLUGIN = "plugin";
    private static final String CURRENCY = "currency";
    private static final String NAME = ".name";

    private final State<JExEconomy> economy = initialState(PLUGIN);
    private final State<Currency> currency = initialState(CURRENCY);

    /**
     * Creates the view; back leads to the currency overview.
     */
    public CurrencyDetailView() {
        super(CurrencyOverviewView.class);
    }

    @Override
    protected String translationKey() {
        return "economy-v2.view.detail";
    }

    @Override
    protected String[] layout() {
        return new String[]{
                "bXXXhXXXX",
                "XXXXXXXXX",
                "XXtXeXrXX",
                "XXXXdXXXX",
                "XXXXXXXXX",
                "cXXXXXXXX"
        };
    }

    @Override
    protected Material fillerMaterial() {
        return Material.BLACK_STAINED_GLASS_PANE;
    }

    @Override
    protected Map<String, Object> titlePlaceholders(@NotNull OpenContext open) {
        return Map.of("currency", currency.get(open).getIdentifier());
    }

    @Override
    protected void onRender(@NotNull RenderContext render, @NotNull Player player) {
        Currency cur = currency.get(render);
        render.layoutSlot('b', EconomyCards.back(player, KEY + "back")).onClick(this::handleBack);
        render.layoutSlot('c', EconomyCards.close(player)).onClick(click -> click.closeForPlayer());
        render.layoutSlot('h', EconomyCards.card(EconomyCards.material(cur.getIcon()),
                EconomyCards.currencyName(player, cur),
                CardLore.create()
                        .block(EconomyCards.paragraph(player, KEY + "header.description"))
                        .section(EconomyCards.section(player, "format"), EconomyCards.formatRows(player, cur))
                        .build()));
        renderTop(render, player);
        renderEdit(render, player, cur);
        renderDanger(render, player, 'r', CurrencyDangerView.MODE_RESET);
        renderDanger(render, player, 'd', CurrencyDangerView.MODE_DELETE);
    }

    private void renderTop(@NotNull RenderContext render, @NotNull Player player) {
        render.layoutSlot('t', action(player, Material.GOLD_INGOT, KEY + "top"))
                .onClick(click -> click.openForPlayer(LeaderboardView.class, Map.of(
                        PLUGIN, economy.get(click),
                        CURRENCY, currency.get(click))));
    }

    private void renderEdit(@NotNull RenderContext render, @NotNull Player player, @NotNull Currency cur) {
        if (!EconomyPermission.CURRENCIES_EDIT.hasOrAdmin(player)) {
            render.layoutSlot('e', EconomyCards.noPermission(player, KEY + "edit" + NAME));
            return;
        }
        render.layoutSlot('e', action(player, Material.ANVIL, KEY + "edit")).onClick(click -> {
            Map<String, Object> data = new HashMap<>();
            data.put(PLUGIN, economy.get(click));
            data.put("draft_currency", new Currency(cur.getIdentifier(), cur.getSymbol(), cur.getPrefix(),
                    cur.getSuffix(), cur.getIcon()));
            data.put("original_identifier", cur.getIdentifier());
            click.openForPlayer(CurrencyEditView.class, data);
        });
    }

    private void renderDanger(@NotNull RenderContext render, @NotNull Player player, char slot,
                              @NotNull String mode) {
        boolean delete = CurrencyDangerView.MODE_DELETE.equals(mode);
        String key = KEY + mode;
        EconomyPermission permission = delete
                ? EconomyPermission.CURRENCIES_DELETE
                : EconomyPermission.CURRENCIES_RESET;
        if (!permission.hasOrAdmin(player)) {
            render.layoutSlot(slot, EconomyCards.noPermission(player, key + NAME));
            return;
        }
        Material icon = delete ? Material.LAVA_BUCKET : Material.TNT;
        render.layoutSlot(slot, EconomyCards.card(icon, EconomyCards.name(player, key + NAME), CardLore.create()
                        .block(EconomyCards.paragraph(player, key + ".description"))
                        .block(EconomyCards.line(player, KEY + "irreversible"))
                        .block(EconomyCards.line(player, key + ".action"))
                        .build()))
                .onClick(click -> click.openForPlayer(CurrencyDangerView.class, Map.of(
                        PLUGIN, economy.get(click),
                        CURRENCY, currency.get(click),
                        CurrencyDangerView.MODE, mode)));
    }

    private static @NotNull ItemStack action(@NotNull Player player, @NotNull Material icon, @NotNull String key) {
        return EconomyCards.card(icon, EconomyCards.name(player, key + NAME), CardLore.create()
                .block(EconomyCards.paragraph(player, key + ".description"))
                .block(EconomyCards.line(player, key + ".action"))
                .build());
    }
}
