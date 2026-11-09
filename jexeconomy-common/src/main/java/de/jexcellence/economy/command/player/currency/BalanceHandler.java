package de.jexcellence.economy.command.player.currency;

import com.raindropcentral.commands.v2.CommandContext;
import com.raindropcentral.commands.v2.CommandHandler;
import de.jexcellence.economy.command.KnownPlayers;
import de.jexcellence.economy.database.entity.Account;
import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.format.CurrencyFormat;
import de.jexcellence.economy.permission.EconomyPermission;
import de.jexcellence.economy.service.EconomyService;
import de.jexcellence.economy.text.EconomyPanel;
import de.jexcellence.economy.text.EconomyText;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * JExCommand 2.0 handler collection for {@code /balance}.
 *
 * <ul>
 *   <li>{@code /balance [currency] [player]} - one currency as a single line, or the treasury panel without one.</li>
 *   <li>{@code /balance all [player]} - the treasury panel for self or a target.</li>
 *   <li>{@code /balance top [currency] [limit]} - the richest players as a panel.</li>
 *   <li>{@code /balance help} - permission-gated command panel.</li>
 * </ul>
 *
 * @author JExcellence
 * @since 3.0.0
 */
public final class BalanceHandler {

    private static final long DEFAULT_TOP_LIMIT = 10L;
    private static final long MAX_TOP_LIMIT = 50L;
    private static final String KEY = EconomyText.ROOT + "balance.";
    private static final String HELP = EconomyText.ROOT + "help.balance.";
    private static final String PARAM_PLAYER = "player";
    private static final String PARAM_CURRENCY = "currency";
    private static final String PARAM_VALUE = "value";

    private final EconomyService economyService;

    /**
     * Creates the handler.
     *
     * @param economyService the economy service
     */
    public BalanceHandler(@NotNull EconomyService economyService) {
        this.economyService = economyService;
    }

    /**
     * Returns the path to handler map for registration.
     *
     * @return the handlers
     */
    public @NotNull Map<String, CommandHandler> handlerMap() {
        return Map.of(
                "balance", this::onRoot,
                "balance.all", this::onAll,
                "balance.top", this::onTop,
                "balance.help", this::onHelp
        );
    }

    private void onRoot(@NotNull CommandContext ctx) {
        var player = ctx.asPlayer().orElseThrow();
        var target = resolveTarget(ctx, player);
        if (target == null) {
            return;
        }
        var currency = ctx.get(PARAM_CURRENCY, Currency.class);
        KnownPlayers.whenKnown(economyService, player, target, () -> {
            if (currency.isPresent()) {
                displaySingle(player, target, currency.get());
            } else {
                displayAll(player, target);
            }
        });
    }

    private void onAll(@NotNull CommandContext ctx) {
        var player = ctx.asPlayer().orElseThrow();
        var target = resolveTarget(ctx, player);
        if (target != null) {
            KnownPlayers.whenKnown(economyService, player, target, () -> displayAll(player, target));
        }
    }

    private @Nullable OfflinePlayer resolveTarget(@NotNull CommandContext ctx, @NotNull Player player) {
        var targetOpt = ctx.get(PARAM_PLAYER, OfflinePlayer.class);
        if (targetOpt.isEmpty()) {
            return player;
        }
        var target = targetOpt.get();
        if (!EconomyPermission.BALANCE_OTHER.has(player)) {
            EconomyText.send(player, EconomyText.msg(EconomyText.ROOT + "error.no-permission"));
            return null;
        }
        return target;
    }

    private void onTop(@NotNull CommandContext ctx) {
        var player = ctx.asPlayer().orElseThrow();
        var currencies = economyService.getAllCurrencies();
        if (currencies.isEmpty()) {
            EconomyText.send(player, EconomyText.ROOT + "error.no-currencies");
            return;
        }
        var currency = ctx.get(PARAM_CURRENCY, Currency.class).orElse(currencies.values().iterator().next());
        int limit = (int) Math.clamp(ctx.get("limit", Long.class).orElse(DEFAULT_TOP_LIMIT), 1L, MAX_TOP_LIMIT);
        economyService.getTopAccounts(currency, limit)
                .thenAccept(accounts -> sendTop(player, currency, limit, accounts));
    }

    private void sendTop(@NotNull Player player, @NotNull Currency currency, int limit,
                         @NotNull List<Account> accounts) {
        String name = CurrencyFormat.displayName(player, currency);
        if (accounts.isEmpty()) {
            EconomyText.send(player, EconomyText.msg(KEY + "top.empty").with(PARAM_CURRENCY, name));
            return;
        }
        var panel = EconomyPanel.of(player)
                .header(EconomyText.msg(KEY + "top.header").with(PARAM_CURRENCY, name).component(player))
                .context(EconomyText.msg(KEY + "top.context").with("limit", limit).component(player))
                .gap();
        int rank = 0;
        for (var account : accounts) {
            rank++;
            String label = EconomyText.msg(KEY + "top.rank")
                    .with("rank", rank)
                    .with(PARAM_PLAYER, ownerName(player, account))
                    .plain(player);
            panel.rowText(label, panel.mini(CurrencyFormat.compact(player, currency, account.getBalance().doubleValue())));
        }
        panel.send(player);
    }

    private static @NotNull String ownerName(@NotNull Player viewer, @NotNull Account account) {
        var owner = account.getPlayer();
        if (owner != null && owner.getPlayerName() != null) {
            return owner.getPlayerName();
        }
        return EconomyText.msg(EconomyText.ROOT + "common.unknown-player").plain(viewer);
    }

    private void onHelp(@NotNull CommandContext ctx) {
        var player = ctx.asPlayer().orElseThrow();
        var alias = ctx.alias();
        var panel = EconomyPanel.of(player)
                .header(EconomyText.component(player, HELP + "header"))
                .context(EconomyText.component(player, HELP + "context"))
                .gap()
                .command(HELP + "self", alias)
                .command(HELP + "single", alias)
                .command(HELP + "all", alias);
        if (EconomyPermission.BALANCE_OTHER.has(player)) {
            panel.command(HELP + "other", alias);
        }
        if (EconomyPermission.BALANCE_TOP.has(player)) {
            panel.command(HELP + "top", alias);
        }
        panel.send(player);
    }

    private void displaySingle(@NotNull Player viewer, @NotNull OfflinePlayer target, @NotNull Currency currency) {
        economyService.getDisplayBalance(target, currency).thenAccept(balance -> {
            boolean self = viewer.getUniqueId().equals(target.getUniqueId());
            boolean bank = economyService.isExternalBalance(target.getUniqueId(), currency);
            String key = KEY + (self ? "single-self" : "single-other") + (bank ? "-bank" : "");
            EconomyText.send(viewer, EconomyText.msg(key)
                    .with(PARAM_CURRENCY, CurrencyFormat.displayName(viewer, currency))
                    .with(PARAM_VALUE, CurrencyFormat.compact(viewer, currency, balance))
                    .with(PARAM_PLAYER, EconomyText.nameOf(viewer, target)));
        });
    }

    private void displayAll(@NotNull Player viewer, @NotNull OfflinePlayer target) {
        if (economyService.getAllCurrencies().isEmpty()) {
            EconomyText.send(viewer, EconomyText.ROOT + "error.no-currencies");
            return;
        }
        economyService.getAccounts(target).thenAccept(accounts -> {
            boolean self = viewer.getUniqueId().equals(target.getUniqueId());
            String targetName = EconomyText.nameOf(viewer, target);
            if (accounts.isEmpty()) {
                EconomyText.send(viewer, EconomyText.msg(KEY + (self ? "empty-self" : "empty-other"))
                        .with(PARAM_PLAYER, targetName));
                return;
            }
            var panel = EconomyPanel.of(viewer)
                    .header(EconomyText.msg(KEY + (self ? "header-self" : "header-other"))
                            .with(PARAM_PLAYER, targetName).component(viewer))
                    .gap();
            for (var account : accounts) {
                addAccountRow(panel, viewer, target, account);
            }
            panel.footer(EconomyText.component(viewer, KEY + "footer"));
            panel.send(viewer);
        });
    }

    private void addAccountRow(@NotNull EconomyPanel panel, @NotNull Player viewer, @NotNull OfflinePlayer target,
                               @NotNull Account account) {
        var currency = account.getCurrency();
        double shown = economyService.displayBalance(target.getUniqueId(), currency, account.getBalance().doubleValue());
        String name = CurrencyFormat.displayName(viewer, currency);
        String label = economyService.isExternalBalance(target.getUniqueId(), currency)
                ? EconomyText.msg(KEY + "bank-label").with(PARAM_CURRENCY, name).plain(viewer)
                : name;
        panel.rowText(label, panel.mini(CurrencyFormat.compact(viewer, currency, shown)));
    }
}
