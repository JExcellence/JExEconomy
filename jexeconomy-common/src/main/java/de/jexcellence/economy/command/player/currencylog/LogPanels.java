package de.jexcellence.economy.command.player.currencylog;

import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.database.entity.TransactionLog;
import de.jexcellence.economy.format.CurrencyFormat;
import de.jexcellence.economy.text.EconomyPanel;
import de.jexcellence.economy.text.EconomyText;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Renders the transaction log as chat panels: one page of entries with clickable navigation, and the full detail
 * panel of a single entry. Dates follow the locale pattern {@code economy-v2.log.date-pattern}.
 *
 * @author JExcellence
 * @since 4.1.0
 */
final class LogPanels {

    private static final String KEY = EconomyText.ROOT + "log.";
    private static final String LABEL = KEY + "label.";
    private static final String PARAM_VALUE = "value";
    private static final String PARAM_PAGE = "page";
    private static final String PARAM_ID = "id";

    /**
     * One page of a transaction log.
     *
     * @param logs       the entries of this page
     * @param page       the one-based page number
     * @param totalPages the page count
     * @param total      the entry count
     * @param owner      the owner's name when it is not the viewer, else {@code null}
     */
    record Page(@NotNull List<TransactionLog> logs, int page, int totalPages, long total, @Nullable String owner) {
    }

    @NotNull EconomyPanel page(@NotNull Player viewer, @NotNull Page page) {
        String headerKey = page.owner() == null ? "header" : "header-other";
        var panel = EconomyPanel.of(viewer)
                .header(EconomyText.msg(KEY + headerKey).with("player", String.valueOf(page.owner()))
                        .component(viewer))
                .context(EconomyText.msg(KEY + "context")
                        .with(PARAM_PAGE, page.page()).with("pages", page.totalPages())
                        .with("total", page.total()).component(viewer))
                .gap();
        DateTimeFormatter dates = dateFormat(viewer);
        for (TransactionLog log : page.logs()) {
            panel.row(entryLabel(viewer, log, dates), changeValue(viewer, log));
        }
        return panel.footer(navigation(viewer, page.page(), page.totalPages()));
    }

    @NotNull EconomyPanel details(@NotNull Player viewer, @NotNull TransactionLog log) {
        var panel = EconomyPanel.of(viewer)
                .header(EconomyText.msg(KEY + "details.header").with(PARAM_ID, log.getId()).component(viewer))
                .context(EconomyText.component(viewer, KEY + (log.isSuccess() ? "state.ok" : "state.failed")))
                .gap()
                .row(LABEL + "time", log.getTimestamp().atZone(ZoneId.systemDefault()).format(dateFormat(viewer)))
                .row(LABEL + "type", typeName(viewer, log))
                .row(LABEL + "currency", currencyName(viewer, log.getCurrency()))
                .row(LABEL + "amount", EconomyText.mini(viewer, amount(viewer, log.getCurrency(), log.getAmount().doubleValue())))
                .row(LABEL + "balance", EconomyText.mini(viewer, EconomyText.msg(KEY + "balance-change")
                        .with("old", amount(viewer, log.getCurrency(), log.getOldBalance().doubleValue()))
                        .with("new", amount(viewer, log.getCurrency(), log.getNewBalance().doubleValue()))
                        .miniMessage(viewer)))
                .row(LABEL + "change", changeValue(viewer, log));
        addPeople(panel, log);
        addNotes(panel, log);
        return panel.footer(EconomyText.component(viewer, KEY + "details.back"));
    }

    private static void addPeople(@NotNull EconomyPanel panel, @NotNull TransactionLog log) {
        if (log.getPlayerUuid() != null) {
            panel.row(LABEL + "player", playerName(log.getPlayerUuid()));
        }
        if (log.getInitiatorUuid() != null && !log.getInitiatorUuid().equals(log.getPlayerUuid())) {
            panel.row(LABEL + "initiator", playerName(log.getInitiatorUuid()));
        }
    }

    private static void addNotes(@NotNull EconomyPanel panel, @NotNull TransactionLog log) {
        if (log.getReason() != null && !log.getReason().isBlank()) {
            panel.row(LABEL + "reason", log.getReason());
        }
        if (!log.isSuccess() && log.getErrorMessage() != null) {
            panel.row(LABEL + "error", log.getErrorMessage());
        }
    }

    private static @NotNull Component entryLabel(@NotNull Player viewer, @NotNull TransactionLog log,
                                                 @NotNull DateTimeFormatter dates) {
        return EconomyText.msg(KEY + (log.isSuccess() ? "entry" : "entry-failed"))
                .with(PARAM_ID, log.getId())
                .with("time", log.getTimestamp().atZone(ZoneId.systemDefault()).format(dates))
                .with("type", typeName(viewer, log))
                .component(viewer);
    }

    private static @NotNull Component changeValue(@NotNull Player viewer, @NotNull TransactionLog log) {
        double change = log.getChangeAmount().doubleValue();
        String key = change >= 0 ? "change-plus" : "change-minus";
        return EconomyText.msg(KEY + key)
                .with(PARAM_VALUE, amount(viewer, log.getCurrency(), Math.abs(change)))
                .component(viewer);
    }

    private static @NotNull Component navigation(@NotNull Player viewer, int page, int totalPages) {
        Component previous = page > 1
                ? EconomyText.msg(KEY + "nav.previous").with(PARAM_PAGE, page - 1).component(viewer)
                : EconomyText.component(viewer, KEY + "nav.previous-off");
        Component next = page < totalPages
                ? EconomyText.msg(KEY + "nav.next").with(PARAM_PAGE, page + 1).component(viewer)
                : EconomyText.component(viewer, KEY + "nav.next-off");
        Component separator = EconomyText.component(viewer, KEY + "nav.separator");
        Component info = EconomyText.msg(KEY + "nav.info").with(PARAM_PAGE, page).with("pages", totalPages)
                .component(viewer);
        return previous.append(separator).append(info).append(separator).append(next);
    }

    private static @NotNull String typeName(@NotNull Player viewer, @NotNull TransactionLog log) {
        String id = log.getChangeType().name().toLowerCase(Locale.ROOT).replace('_', '-');
        return EconomyText.msg(KEY + "type." + id).plain(viewer);
    }

    private static @NotNull String currencyName(@NotNull Player viewer, @Nullable Currency currency) {
        return currency == null
                ? EconomyText.msg(EconomyText.ROOT + "common.none").plain(viewer)
                : CurrencyFormat.displayName(viewer, currency);
    }

    private static @NotNull String amount(@NotNull Player viewer, @Nullable Currency currency, double value) {
        return currency == null ? CurrencyFormat.amount(value) : CurrencyFormat.compact(viewer, currency, value);
    }

    private static @NotNull DateTimeFormatter dateFormat(@NotNull Player viewer) {
        return DateTimeFormatter.ofPattern(EconomyText.msg(KEY + "date-pattern").plain(viewer), viewer.locale());
    }

    private static @NotNull String playerName(@NotNull UUID uuid) {
        var player = Bukkit.getOfflinePlayer(uuid);
        return player.getName() != null ? player.getName() : uuid.toString();
    }
}
