package de.jexcellence.economy.command.player.currencylog;

import com.raindropcentral.commands.v2.CommandContext;
import com.raindropcentral.commands.v2.CommandHandler;
import de.jexcellence.economy.JExEconomy;
import de.jexcellence.economy.database.entity.TransactionLog;
import de.jexcellence.economy.database.repository.TransactionLogRepository;
import de.jexcellence.economy.service.EconomyService;
import de.jexcellence.economy.permission.EconomyPermission;
import de.jexcellence.economy.text.EconomyPanel;
import de.jexcellence.economy.text.EconomyText;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JExCommand 2.0 handler collection for {@code /currencylog}.
 *
 * <ul>
 *   <li>{@code /currencylog} and {@code /currencylog view [page]} - the invoker's transaction log as a panel.</li>
 *   <li>{@code /currencylog details <id>} - one entry in full.</li>
 *   <li>{@code /currencylog player <name> [page]} - another player's log ({@code jexeconomy.currencylog.player}).</li>
 *   <li>{@code /currencylog clear [days]} - prune entries older than N days
 *       ({@code jexeconomy.currencylog.clear}).</li>
 *   <li>{@code /currencylog help} - permission-gated command panel.</li>
 * </ul>
 *
 * @author JExcellence
 * @since 3.0.0
 */
public final class CurrencyLogHandler {

    private static final int LOGS_PER_PAGE = 10;
    private static final int MAX_LOGS = 200;
    private static final int MIN_CLEAR_DAYS = 7;
    private static final long DEFAULT_CLEAR_DAYS = 90L;
    private static final String KEY = EconomyText.ROOT + "log.";
    private static final String HELP = EconomyText.ROOT + "help.log.";
    private static final String KEY_ERROR = KEY + "error";
    private static final String PARAM_PAGE = "page";
    private static final String PARAM_PLAYER = "player";
    private static final String PARAM_DAYS = "days";

    private final TransactionLogRepository txLogRepo;
    private final EconomyService economyService;
    private final LogPanels panels = new LogPanels();

    /**
     * Creates the handler.
     *
     * @param economy the plugin core
     */
    public CurrencyLogHandler(@NotNull JExEconomy economy) {
        this.txLogRepo = economy.transactionLogRepo();
        this.economyService = economy.economyService();
    }

    /**
     * Returns the path to handler map for registration.
     *
     * @return the handlers
     */
    public @NotNull Map<String, CommandHandler> handlerMap() {
        return Map.of(
                "currencylog", this::onRoot,
                "currencylog.view", this::onView,
                "currencylog.details", this::onDetails,
                "currencylog.player", this::onPlayerLookup,
                "currencylog.clear", this::onClear,
                "currencylog.help", this::onHelp
        );
    }

    private void onRoot(@NotNull CommandContext ctx) {
        var player = ctx.asPlayer().orElseThrow();
        displayLogs(player, player.getUniqueId(), player.getName(), 1);
    }

    private void onView(@NotNull CommandContext ctx) {
        var player = ctx.asPlayer().orElseThrow();
        displayLogs(player, player.getUniqueId(), player.getName(), requestedPage(ctx));
    }

    private void onDetails(@NotNull CommandContext ctx) {
        var player = ctx.asPlayer().orElseThrow();
        long logId = ctx.require("id", Long.class);
        txLogRepo.findByIdWithCurrencyAsync(logId).thenApply(found -> found.orElse(null))
                .thenAccept(log -> {
                    if (log == null) {
                        EconomyText.send(player, EconomyText.msg(KEY + "not-found").with("id", logId));
                    } else {
                        panels.details(player, log).send(player);
                    }
                })
                .exceptionally(ex -> sendError(player));
    }

    private void onPlayerLookup(@NotNull CommandContext ctx) {
        var viewer = ctx.asPlayer().orElseThrow();
        var target = ctx.require(PARAM_PLAYER, OfflinePlayer.class);
        int page = requestedPage(ctx);
        String targetName = EconomyText.nameOf(viewer, target);
        economyService.isKnownPlayer(target).thenAccept(known -> {
            if (Boolean.TRUE.equals(known)) {
                displayLogs(viewer, target.getUniqueId(), targetName, page);
                return;
            }
            txLogRepo.findByPlayerNameAsync(targetName, MAX_LOGS)
                    .thenAccept(logs -> displayCachedName(viewer, targetName, logs, page))
                    .exceptionally(ex -> sendError(viewer));
        }).exceptionally(ex -> sendError(viewer));
    }

    private void displayCachedName(@NotNull Player viewer, @NotNull String targetName,
                                   @NotNull List<TransactionLog> logs, int page) {
        if (logs.isEmpty()) {
            EconomyText.send(viewer, EconomyText.msg(KEY + "empty-other").with(PARAM_PLAYER, targetName));
            return;
        }
        int totalPages = pageCount(logs.size());
        if (page > totalPages) {
            sendPageNotFound(viewer, page, totalPages);
            return;
        }
        int start = (page - 1) * LOGS_PER_PAGE;
        int end = Math.min(start + LOGS_PER_PAGE, logs.size());
        panels.page(viewer, new LogPanels.Page(logs.subList(start, end), page, totalPages, logs.size(), targetName))
                .send(viewer);
    }

    private void onClear(@NotNull CommandContext ctx) {
        var viewer = ctx.asPlayer().orElseThrow();
        int days = ctx.get(PARAM_DAYS, Long.class).orElse(DEFAULT_CLEAR_DAYS).intValue();
        if (days < MIN_CLEAR_DAYS) {
            EconomyText.send(viewer, EconomyText.msg(KEY + "clear-too-small")
                    .with("min", MIN_CLEAR_DAYS).with("given", days));
            return;
        }
        EconomyText.send(viewer, EconomyText.msg(KEY + "clear-start").with(PARAM_DAYS, days));
        txLogRepo.deleteOlderThanAsync(Instant.now().minus(days, ChronoUnit.DAYS))
                .thenAccept(count -> EconomyText.send(viewer, EconomyText.msg(KEY + "clear-done")
                        .with("count", count).with(PARAM_DAYS, days)))
                .exceptionally(ex -> {
                    EconomyText.send(viewer, KEY + "clear-failed");
                    return null;
                });
    }

    private void onHelp(@NotNull CommandContext ctx) {
        var player = ctx.asPlayer().orElseThrow();
        var alias = ctx.alias();
        var panel = EconomyPanel.of(player)
                .header(EconomyText.component(player, HELP + "header"))
                .context(EconomyText.component(player, HELP + "context"))
                .gap()
                .command(HELP + "view", alias)
                .command(HELP + "details", alias);
        if (EconomyPermission.CURRENCYLOG_PLAYER.has(player)) {
            panel.command(HELP + PARAM_PLAYER, alias);
        }
        if (EconomyPermission.CURRENCYLOG_CLEAR.has(player)) {
            panel.command(HELP + "clear", alias);
        }
        panel.send(player);
    }

    private void displayLogs(@NotNull Player viewer, @NotNull UUID targetUuid, @NotNull String targetName, int page) {
        txLogRepo.countByPlayerUuidAsync(targetUuid)
                .thenAccept(totalCount -> displayCountedLogs(viewer, targetUuid, targetName, page,
                        totalCount == null ? 0L : totalCount))
                .exceptionally(ex -> sendError(viewer));
    }

    private void displayCountedLogs(@NotNull Player viewer, @NotNull UUID targetUuid, @NotNull String targetName,
                                    int page, long total) {
        boolean self = viewer.getUniqueId().equals(targetUuid);
        if (total == 0L) {
            EconomyText.send(viewer, EconomyText.msg(KEY + (self ? "empty" : "empty-other"))
                    .with(PARAM_PLAYER, targetName));
            return;
        }
        int totalPages = pageCount(total);
        if (page > totalPages) {
            sendPageNotFound(viewer, page, totalPages);
            return;
        }
        String owner = self ? null : targetName;
        txLogRepo.findByPlayerUuidPageAsync(targetUuid, (page - 1) * LOGS_PER_PAGE, LOGS_PER_PAGE)
                .thenAccept(logs -> panels.page(viewer,
                        new LogPanels.Page(logs, page, totalPages, total, owner)).send(viewer))
                .exceptionally(ex -> sendError(viewer));
    }

    private static int requestedPage(@NotNull CommandContext ctx) {
        return Math.max(1, ctx.get(PARAM_PAGE, Long.class).orElse(1L).intValue());
    }

    private static int pageCount(long total) {
        return (int) ((total + LOGS_PER_PAGE - 1) / LOGS_PER_PAGE);
    }

    private static void sendPageNotFound(@NotNull Player viewer, int page, int totalPages) {
        EconomyText.send(viewer, EconomyText.msg(KEY + "page-not-found")
                .with(PARAM_PAGE, page).with("total", totalPages));
    }

    private static @Nullable Void sendError(@NotNull Player viewer) {
        EconomyText.send(viewer, KEY_ERROR);
        return null;
    }
}
