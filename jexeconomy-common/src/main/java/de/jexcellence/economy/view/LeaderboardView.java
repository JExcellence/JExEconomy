package de.jexcellence.economy.view;

import de.jexcellence.economy.JExEconomy;
import de.jexcellence.economy.database.entity.Account;
import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.format.CurrencyFormat;
import de.jexcellence.jexplatform.view.PaginatedView;
import me.devnatan.inventoryframework.component.BukkitItemComponentBuilder;
import me.devnatan.inventoryframework.context.Context;
import me.devnatan.inventoryframework.context.OpenContext;
import me.devnatan.inventoryframework.context.RenderContext;
import me.devnatan.inventoryframework.context.SlotClickContext;
import me.devnatan.inventoryframework.state.State;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The richest players of one currency with their real heads, 28 per page. The shared filter hopper switches the
 * currency (left-click next, right-click back).
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class LeaderboardView extends PaginatedView<Account> {

    /** Number of ranked players loaded per currency. */
    public static final int TOP_SIZE = 28;

    private static final String KEY = EconomyCards.VIEW + "leaderboard.";
    private static final String PLUGIN = "plugin";
    private static final String CURRENCY = "currency";

    private final State<JExEconomy> economy = initialState(PLUGIN);
    private final State<Currency> currency = initialState(CURRENCY);

    /**
     * Creates the view; back leads to the currency detail.
     */
    public LeaderboardView() {
        super(CurrencyDetailView.class);
    }

    @Override
    protected String translationKey() {
        return "economy-v2.view.leaderboard";
    }

    @Override
    protected Map<String, Object> titlePlaceholders(@NotNull OpenContext open) {
        return Map.of(CURRENCY, CurrencyFormat.displayName(open.getPlayer(), currency.get(open)));
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
    protected @NotNull CompletableFuture<List<Account>> loadData(@NotNull Context ctx) {
        return economy.get(ctx).economyService().getTopAccounts(currency.get(ctx), TOP_SIZE);
    }

    @Override
    protected void onPaginatedRender(@NotNull RenderContext render, @NotNull Player player) {
        Currency cur = currency.get(render);
        List<Currency> currencies = LeaderboardCards.sorted(
                economy.get(render).economyService().getAllCurrencies().values());
        render.layoutSlot('b', EconomyCards.back(player, KEY + "back")).onClick(this::handleBack);
        render.layoutSlot('c', EconomyCards.close(player)).onClick(click -> click.closeForPlayer());
        render.layoutSlot('n', createItem(fillerMaterial(), Component.empty()));
        render.layoutSlot('h', LeaderboardCards.header(player, cur, TOP_SIZE));
        List<String> options = currencies.stream().map(entry -> CurrencyFormat.displayName(player, entry)).toList();
        render.layoutSlot('f', EconomyCards.filter(player, options, LeaderboardCards.indexOf(currencies, cur)))
                .onClick(click -> switchCurrency(click, currencies));
    }

    private void switchCurrency(@NotNull SlotClickContext click, @NotNull List<Currency> currencies) {
        if (currencies.size() < 2) {
            return;
        }
        int current = LeaderboardCards.indexOf(currencies, currency.get(click));
        int step = click.isRightClick() ? -1 : 1;
        Currency next = currencies.get(Math.floorMod(current + step, currencies.size()));
        click.openForPlayer(LeaderboardView.class, Map.of(PLUGIN, economy.get(click), CURRENCY, next));
    }

    @Override
    protected void renderItem(@NotNull Context ctx, @NotNull BukkitItemComponentBuilder builder, int index,
                              @NotNull Account entry) {
        builder.withItem(LeaderboardCards.entry(ctx.getPlayer(), currency.get(ctx), entry, index + 1,
                economy.get(ctx).extensions().current()));
    }
}
