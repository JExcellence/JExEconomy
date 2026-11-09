package de.jexcellence.economy.view;

import de.jexcellence.economy.JExEconomy;
import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.permission.EconomyPermission;
import de.jexcellence.jexplatform.gui.component.CardLore;
import de.jexcellence.jexplatform.view.PaginatedView;
import me.devnatan.inventoryframework.component.BukkitItemComponentBuilder;
import me.devnatan.inventoryframework.context.Context;
import me.devnatan.inventoryframework.context.RenderContext;
import me.devnatan.inventoryframework.state.State;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The currency manager: every currency as a card with its format, 28 per page, plus the create action.
 * Bedrock players get {@code CurrencyBrowseForms#overview} instead.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class CurrencyOverviewView extends PaginatedView<Currency> {

    private static final String KEY = EconomyCards.VIEW + "overview.";
    private static final String PLUGIN = "plugin";

    private final State<JExEconomy> economy = initialState(PLUGIN);

    @Override
    protected String translationKey() {
        return "economy-v2.view.overview";
    }

    @Override
    protected String[] layout() {
        return EconomyCards.LIST_LAYOUT.clone();
    }

    @Override
    protected Material fillerMaterial() {
        return Material.BLACK_STAINED_GLASS_PANE;
    }

    @Override
    protected @NotNull CompletableFuture<List<Currency>> loadData(@NotNull Context ctx) {
        List<Currency> currencies = new ArrayList<>(economy.get(ctx).economyService().getAllCurrencies().values());
        currencies.sort(Comparator.comparing(Currency::getIdentifier));
        return CompletableFuture.completedFuture(currencies);
    }

    @Override
    protected void onPaginatedRender(@NotNull RenderContext render, @NotNull Player player) {
        int count = economy.get(render).economyService().getAllCurrencies().size();
        render.layoutSlot('b', createItem(fillerMaterial(), Component.empty()));
        render.layoutSlot('f', createItem(fillerMaterial(), Component.empty()));
        render.layoutSlot('c', EconomyCards.close(player)).onClick(click -> click.closeForPlayer());
        render.layoutSlot('h', EconomyCards.card(Material.SUNFLOWER, EconomyCards.name(player, KEY + "header.name"),
                CardLore.create()
                        .block(EconomyCards.paragraph(player, KEY + "header.description"))
                        .section(EconomyCards.section(player, "now"), List.of(EconomyCards.row(player,
                                KEY + "label.count", EconomyCards.value(player, String.valueOf(count)))))
                        .build()));
        renderCreate(render, player);
    }

    private void renderCreate(@NotNull RenderContext render, @NotNull Player player) {
        if (!EconomyPermission.CURRENCIES_CREATE.has(player)) {
            render.layoutSlot('n', EconomyCards.noPermission(player, KEY + "create.name"));
            return;
        }
        render.layoutSlot('n', EconomyCards.card(Material.NETHER_STAR, EconomyCards.name(player, KEY + "create.name"),
                CardLore.create()
                        .block(EconomyCards.paragraph(player, KEY + "create.description"))
                        .block(EconomyCards.line(player, KEY + "create.action"))
                        .build()))
                .onClick(click -> click.openForPlayer(CurrencyCreateView.class, Map.of(
                        PLUGIN, economy.get(click),
                        "draft_currency", EconomyCards.blankDraft())));
    }

    @Override
    protected void renderItem(@NotNull Context ctx, @NotNull BukkitItemComponentBuilder builder, int index,
                              @NotNull Currency entry) {
        Player player = ctx.getPlayer();
        builder.withItem(EconomyCards.card(EconomyCards.material(entry.getIcon()),
                        EconomyCards.currencyName(player, entry),
                        CardLore.create()
                                .section(EconomyCards.section(player, "format"), EconomyCards.formatRows(player, entry))
                                .block(EconomyCards.line(player, KEY + "entry.action"))
                                .build()))
                .onClick(click -> click.openForPlayer(CurrencyDetailView.class, Map.of(
                        PLUGIN, economy.get(click),
                        "currency", entry)));
    }
}
