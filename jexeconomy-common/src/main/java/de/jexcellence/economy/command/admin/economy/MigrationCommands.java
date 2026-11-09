package de.jexcellence.economy.command.admin.economy;

import com.raindropcentral.commands.v2.CommandContext;
import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.migration.MigrationMode;
import de.jexcellence.economy.migration.MigrationRefused;
import de.jexcellence.economy.migration.MigrationReport;
import de.jexcellence.economy.migration.MigrationService;
import de.jexcellence.economy.migration.MigrationSource;
import de.jexcellence.economy.service.EconomyService;
import de.jexcellence.economy.text.EconomyPanel;
import de.jexcellence.economy.text.EconomyText;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;

/**
 * {@code /economy migrate}: imports balances from another economy. Starting, resuming and rolling back change every
 * balance at once, so only the console may do them.
 *
 * @author JExcellence
 * @since 3.2.0
 */
final class MigrationCommands {

    private static final String KEY = EconomyText.ROOT + "migration.";
    private static final String HEADER = "header";
    private static final String PARAM_ID = "id";
    private static final String PARAM_TARGET = "target";

    private final EconomyService economy;
    private final MigrationService migration;

    MigrationCommands(@NotNull EconomyService economy, @NotNull MigrationService migration) {
        this.economy = economy;
        this.migration = migration;
    }

    void handle(@NotNull CommandContext ctx) {
        var sender = ctx.sender();
        var action = ctx.get("action", MigrateAction.class);
        if (action.isEmpty()) {
            sendUsage(sender);
            return;
        }
        switch (action.get()) {
            case SOURCES -> sendSources(sender);
            case PLAN -> plan(ctx);
            case START -> start(ctx);
            case PAUSE -> pause(sender);
            case RESUME -> resume(sender);
            case STATUS -> sendStatus(sender);
            case ROLLBACK -> rollback(ctx);
            default -> sendUsage(sender);
        }
    }

    private void sendUsage(@NotNull CommandSender sender) {
        var panel = EconomyPanel.of(sender)
                .header(EconomyText.component(sender, KEY + "usage." + HEADER))
                .gap();
        for (MigrateAction action : MigrateAction.values()) {
            panel.command(KEY + "usage." + action.token(), "economy");
        }
        panel.send(sender);
    }

    private void sendSources(@NotNull CommandSender sender) {
        migration.sources().thenAccept(sources -> showSources(sender, sources))
                .exceptionally(failure -> report(sender, failure));
    }

    private void showSources(@NotNull CommandSender sender, @NotNull List<MigrationSource> sources) {
        if (sources.isEmpty()) {
            EconomyText.send(sender, KEY + "sources.none");
            return;
        }
        var panel = EconomyPanel.of(sender)
                .header(EconomyText.component(sender, KEY + "sources." + HEADER))
                .gap();
        for (MigrationSource source : sources) {
            panel.line(EconomyText.msg(KEY + "sources.entry").with(PARAM_ID, source.id())
                    .component(EconomyText.viewerOf(sender)));
        }
        panel.send(sender);
    }

    private void plan(@NotNull CommandContext ctx) {
        var sender = ctx.sender();
        Optional<String> source = ctx.get(PARAM_TARGET, String.class);
        if (source.isEmpty()) {
            EconomyText.send(sender, KEY + "error.source-missing");
            return;
        }
        Optional<Currency> currency = ctx.get("currency", Currency.class).or(economy::vaultCurrency);
        if (currency.isEmpty()) {
            EconomyText.send(sender, KEY + "error.currency-unknown");
            return;
        }
        MigrationMode mode = ctx.get("mode", MigrationMode.class).orElse(MigrationMode.SKIP_EXISTING);
        EconomyText.send(sender, KEY + "plan.running");
        migration.plan(source.get(), currency.get().getIdentifier(), mode)
                .thenAccept(result -> showPlan(sender, result))
                .exceptionally(failure -> report(sender, failure));
    }

    private void showPlan(@NotNull CommandSender sender, @NotNull MigrationService.PlanResult result) {
        MigrationReport report = result.report();
        MigrationReport.LeftOut leftOut = report.counts();
        EconomyPanel.of(sender)
                .header(EconomyText.component(sender, KEY + "plan." + HEADER))
                .gap()
                .row(KEY + "plan.players", String.valueOf(report.players()))
                .row(KEY + "plan.source-total", report.sourceTotal().toPlainString())
                .row(KEY + "plan.import-total", report.importTotal().toPlainString())
                .row(KEY + "plan.rounding", report.roundingLoss().toPlainString())
                .row(KEY + "plan.invalid", String.valueOf(leftOut.invalid()))
                .row(KEY + "plan.unreadable", String.valueOf(leftOut.unreadable()))
                .row(KEY + "plan.empty", String.valueOf(leftOut.empty()))
                .row(KEY + "plan.existing", String.valueOf(leftOut.existing()))
                .row(KEY + "plan.already", String.valueOf(leftOut.alreadyImported()))
                .footer(EconomyText.msg(KEY + "plan.next").with(PARAM_ID, result.planId())
                        .component(EconomyText.viewerOf(sender)))
                .send(sender);
    }

    private void start(@NotNull CommandContext ctx) {
        var sender = ctx.sender();
        Optional<Long> id = consoleId(ctx);
        id.ifPresent(planId -> migration.start(planId)
                .thenRun(() -> EconomyText.send(sender, EconomyText.msg(KEY + "start.done").with(PARAM_ID, planId)))
                .exceptionally(failure -> report(sender, failure)));
    }

    private void pause(@NotNull CommandSender sender) {
        EconomyText.send(sender, KEY + (migration.pause() ? "pause.done" : "pause.idle"));
    }

    private void resume(@NotNull CommandSender sender) {
        if (refusedForPlayer(sender)) {
            return;
        }
        migration.resume()
                .thenRun(() -> EconomyText.send(sender, KEY + "resume.done"))
                .exceptionally(failure -> report(sender, failure));
    }

    private void sendStatus(@NotNull CommandSender sender) {
        migration.status().thenAccept(found -> {
            if (found.isEmpty()) {
                EconomyText.send(sender, KEY + "status.none");
                return;
            }
            var run = found.get();
            EconomyText.send(sender, EconomyText.msg(KEY + "status.line")
                    .with(PARAM_ID, run.getId())
                    .with("state", run.getState().name())
                    .with("imported", run.getImported())
                    .with("failed", run.getFailed())
                    .with("players", run.getPlayers()));
        }).exceptionally(failure -> report(sender, failure));
    }

    private void rollback(@NotNull CommandContext ctx) {
        var sender = ctx.sender();
        consoleId(ctx).ifPresent(runId -> {
            EconomyText.send(sender, KEY + "rollback.running");
            migration.rollback(runId)
                    .thenAccept(result -> EconomyText.send(sender, EconomyText.msg(KEY + "rollback.done")
                            .with("restored", result.restored())
                            .with("changed", result.changed())))
                    .exceptionally(failure -> report(sender, failure));
        });
    }

    private @NotNull Optional<Long> consoleId(@NotNull CommandContext ctx) {
        var sender = ctx.sender();
        if (refusedForPlayer(sender)) {
            return Optional.empty();
        }
        try {
            return ctx.get(PARAM_TARGET, String.class).map(Long::parseLong)
                    .or(() -> {
                        EconomyText.send(sender, KEY + "error.id-invalid");
                        return Optional.empty();
                    });
        } catch (NumberFormatException notANumber) {
            EconomyText.send(sender, KEY + "error.id-invalid");
            return Optional.empty();
        }
    }

    private static boolean refusedForPlayer(@NotNull CommandSender sender) {
        if (sender instanceof Player) {
            EconomyText.send(sender, KEY + "error.console-only");
            return true;
        }
        return false;
    }

    private static Void report(@NotNull CommandSender sender, @NotNull Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        if (cause instanceof MigrationRefused refused) {
            EconomyText.send(sender, KEY + "error." + refused.reason());
        } else {
            EconomyText.send(sender, EconomyText.msg(KEY + "error.failed")
                    .with("error", String.valueOf(cause.getMessage())));
        }
        return null;
    }
}
