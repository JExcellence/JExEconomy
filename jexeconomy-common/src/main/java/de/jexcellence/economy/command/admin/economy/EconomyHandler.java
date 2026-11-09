package de.jexcellence.economy.command.admin.economy;

import com.raindropcentral.commands.v2.CommandContext;
import com.raindropcentral.commands.v2.CommandHandler;
import de.jexcellence.economy.api.TransactionResult;
import de.jexcellence.economy.command.KnownPlayers;
import de.jexcellence.economy.migration.MigrationService;
import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.format.CurrencyFormat;
import de.jexcellence.economy.service.EconomyService;
import de.jexcellence.economy.service.FailureReason;
import de.jexcellence.economy.text.EconomyText;
import de.jexcellence.economy.text.FailureText;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import de.jexcellence.jextranslate.R18nManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * JExCommand 2.0 handler collection for {@code /economy} (console and player).
 *
 * <ul>
 *   <li>{@code give|take|set <target> <amount> [currency]} - change one balance.</li>
 *   <li>{@code reset <target> [currency]} - reset one balance to zero.</li>
 *   <li>{@code giveall <amount> [currency]} and {@code giveall confirm ...} - gift every online player.</li>
 *   <li>{@code migrate <action>}, {@code reload}, {@code help}.</li>
 * </ul>
 *
 * <p>Players are gated at tree-load time by the YAML {@code permission:} fields.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public final class EconomyHandler {

    private static final String KEY = EconomyText.ROOT + "admin.";
    private static final String PARAM_PLAYER = "player";
    private static final String PARAM_VALUE = "value";
    private static final String PARAM_CURRENCY = "currency";
    private static final String PARAM_COUNT = "count";
    private static final String PARAM_ERROR = "error";
    private static final String PARAM_AMOUNT = "amount";

    private final EconomyService economyService;
    private final PlatformScheduler scheduler;
    private final EconomyAdminPanels panels = new EconomyAdminPanels();
    private final MigrationCommands migrationCommands;

    private record Mutation(@NotNull CommandSender sender, @NotNull String targetName, @NotNull Currency currency,
                            double amount, @NotNull Action action) {
    }

    /**
     * Creates the handler.
     *
     * @param economyService the economy service
     * @param scheduler      the platform scheduler; online players are read and broadcasts sent on the main thread
     * @param migration      the balance import
     */
    public EconomyHandler(@NotNull EconomyService economyService, @NotNull PlatformScheduler scheduler,
                          @NotNull MigrationService migration) {
        this.economyService = economyService;
        this.scheduler = scheduler;
        this.migrationCommands = new MigrationCommands(economyService, migration);
    }

    /**
     * Returns the path to handler map for registration.
     *
     * @return the handlers
     */
    public @NotNull Map<String, CommandHandler> handlerMap() {
        return Map.of(
                "economy", this::onHelp,
                "economy.give", ctx -> mutate(ctx, Action.GIVE),
                "economy.take", ctx -> mutate(ctx, Action.TAKE),
                "economy.set", ctx -> mutate(ctx, Action.SET),
                "economy.reset", ctx -> mutate(ctx, Action.RESET),
                "economy.giveall", this::onGiveAllPreview,
                "economy.giveall.confirm", this::onGiveAllConfirm,
                "economy.migrate", ctx -> migrationCommands.handle(ctx),
                "economy.reload", this::onReload,
                "economy.help", this::onHelp
        );
    }

    private void mutate(@NotNull CommandContext ctx, @NotNull Action action) {
        var sender = ctx.sender();
        var target = ctx.require("target", OfflinePlayer.class);
        double amount = action == Action.RESET ? 0.0 : ctx.require(PARAM_AMOUNT, Double.class);
        if ((action == Action.GIVE || action == Action.TAKE) && amount <= 0) {
            EconomyText.send(sender, EconomyText.ROOT + "error.invalid-amount");
            return;
        }
        KnownPlayers.whenKnown(economyService, sender, target, () -> applyMutation(ctx, target, action, amount));
    }

    private void applyMutation(@NotNull CommandContext ctx, @NotNull OfflinePlayer target, @NotNull Action action,
                               double amount) {
        var sender = ctx.sender();
        var currency = resolveCurrency(ctx);
        if (currency.isEmpty()) {
            return;
        }
        var mutation = new Mutation(sender, EconomyText.nameOf(sender, target), currency.get(), amount, action);
        run(target, mutation)
                .thenAccept(result -> report(mutation, result))
                .exceptionally(ex -> {
                    reportFailure(mutation, FailureReason.STORAGE_FAILED.text());
                    return null;
                });
    }

    private @NotNull CompletableFuture<TransactionResult> run(@NotNull OfflinePlayer target,
                                                              @NotNull Mutation mutation) {
        Player initiator = EconomyText.viewerOf(mutation.sender());
        Currency currency = mutation.currency();
        String reason = mutation.action().id + " by " + mutation.sender().getName();
        return switch (mutation.action()) {
            case GIVE -> economyService.deposit(target, currency, mutation.amount(), initiator, reason);
            case TAKE -> economyService.withdraw(target, currency, mutation.amount(), initiator, reason);
            case SET -> economyService.setBalance(target, currency, mutation.amount(), initiator, reason);
            case RESET -> economyService.resetBalance(target, currency, initiator);
            default -> throw new IllegalStateException("Unexpected action: " + mutation.action());
        };
    }

    private static void report(@NotNull Mutation mutation, @NotNull TransactionResult result) {
        if (!result.isSuccess()) {
            reportFailure(mutation, result.error());
            return;
        }
        Player viewer = EconomyText.viewerOf(mutation.sender());
        Currency currency = mutation.currency();
        EconomyText.send(mutation.sender(), EconomyText.msg(KEY + mutation.action().id + "-done")
                .with(PARAM_PLAYER, mutation.targetName())
                .with(PARAM_CURRENCY, CurrencyFormat.displayName(viewer, currency))
                .with(PARAM_VALUE, CurrencyFormat.compact(viewer, currency, mutation.amount()))
                .with("balance", CurrencyFormat.compact(viewer, currency, result.balance())));
    }

    private static void reportFailure(@NotNull Mutation mutation, @Nullable String error) {
        Player viewer = EconomyText.viewerOf(mutation.sender());
        EconomyText.send(mutation.sender(), EconomyText.msg(KEY + mutation.action().id + "-failed")
                .with(PARAM_PLAYER, mutation.targetName())
                .with(PARAM_CURRENCY, CurrencyFormat.displayName(viewer, mutation.currency()))
                .with(PARAM_ERROR, errorText(viewer, error)));
    }

    private void onGiveAllPreview(@NotNull CommandContext ctx) {
        var sender = ctx.sender();
        double amount = ctx.require(PARAM_AMOUNT, Double.class);
        var currency = resolveCurrency(ctx);
        if (currency.isEmpty()) {
            return;
        }
        int count = Bukkit.getOnlinePlayers().size();
        if (count == 0) {
            EconomyText.send(sender, KEY + "giveall-none");
            return;
        }
        EconomyText.send(sender, EconomyText.msg(KEY + "giveall-confirm")
                .with(PARAM_VALUE, CurrencyFormat.compact(EconomyText.viewerOf(sender), currency.get(), amount))
                .with(PARAM_COUNT, count)
                .with(PARAM_AMOUNT, commandAmount(amount))
                .with(PARAM_CURRENCY, currency.get().getIdentifier()));
    }

    private void onGiveAllConfirm(@NotNull CommandContext ctx) {
        var sender = ctx.sender();
        double amount = ctx.require(PARAM_AMOUNT, Double.class);
        var resolved = resolveCurrency(ctx);
        if (resolved.isEmpty()) {
            return;
        }
        var grant = new Grant(sender, resolved.get(), amount);
        scheduler.runSync(() -> grantToOnline(grant, List.copyOf(Bukkit.getOnlinePlayers())));
    }

    private record Grant(@NotNull CommandSender sender, @NotNull Currency currency, double amount) {
    }

    private void grantToOnline(@NotNull Grant grant, @NotNull List<Player> recipients) {
        CommandSender sender = grant.sender();
        if (recipients.isEmpty()) {
            EconomyText.send(sender, KEY + "giveall-none");
            return;
        }
        Player initiator = EconomyText.viewerOf(sender);
        economyService.depositAll(recipients, grant.currency().getIdentifier(), grant.amount(), initiator,
                        "giveall by " + sender.getName())
                .thenAccept(results -> scheduler.runSync(() -> reportGrant(grant, recipients, results)))
                .exceptionally(ex -> {
                    EconomyText.send(sender, EconomyText.msg(KEY + "giveall-failed")
                            .with(PARAM_ERROR, errorText(initiator, ex.getMessage())));
                    return null;
                });
    }

    private static void reportGrant(@NotNull Grant grant, @NotNull List<Player> recipients,
                                    @NotNull List<TransactionResult> results) {
        List<Player> credited = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (int index = 0; index < recipients.size(); index++) {
            Player recipient = recipients.get(index);
            if (index < results.size() && results.get(index).isSuccess()) {
                credited.add(recipient);
            } else {
                failed.add(recipient.getName());
            }
        }
        for (Player viewer : credited) {
            if (viewer.isOnline()) {
                EconomyText.send(viewer, EconomyText.msg(KEY + "giveall-broadcast")
                        .with(PARAM_VALUE, CurrencyFormat.compact(viewer, grant.currency(), grant.amount())));
            }
        }
        Player initiator = EconomyText.viewerOf(grant.sender());
        var message = EconomyText.msg(KEY + (failed.isEmpty() ? "giveall-done" : "giveall-partial"))
                .with(PARAM_VALUE, CurrencyFormat.compact(initiator, grant.currency(), grant.amount()))
                .with(PARAM_COUNT, credited.size())
                .with("failed", failed.size())
                .with("players", String.join(", ", failed));
        EconomyText.send(grant.sender(), message);
    }

    private static @NotNull String commandAmount(double amount) {
        return amount % 1.0 == 0.0 ? String.valueOf((long) amount) : String.valueOf(amount);
    }

    private void onReload(@NotNull CommandContext ctx) {
        var sender = ctx.sender();
        EconomyText.send(sender, KEY + "reload-start");
        economyService.reload()
                .thenCompose(v -> R18nManager.getInstance().reload())
                .thenAccept(v -> EconomyText.send(sender, EconomyText.msg(KEY + "reload-done")
                        .with(PARAM_COUNT, economyService.getAllCurrencies().size())))
                .exceptionally(ex -> {
                    EconomyText.send(sender, EconomyText.msg(KEY + "reload-failed")
                            .with(PARAM_ERROR, errorText(EconomyText.viewerOf(sender), ex.getMessage())));
                    return null;
                });
    }

    private void onHelp(@NotNull CommandContext ctx) {
        panels.help(ctx.sender(), ctx.alias()).send(ctx.sender());
    }

    private @NotNull Optional<Currency> resolveCurrency(@NotNull CommandContext ctx) {
        var explicit = ctx.get(PARAM_CURRENCY, Currency.class);
        if (explicit.isPresent()) {
            return explicit;
        }
        var currencies = economyService.getAllCurrencies();
        if (currencies.size() == 1) {
            return Optional.of(currencies.values().iterator().next());
        }
        var sender = ctx.sender();
        if (currencies.isEmpty()) {
            EconomyText.send(sender, KEY + "no-currency");
        } else {
            EconomyText.send(sender, EconomyText.msg(KEY + "currency-required")
                    .with("available", String.join(", ", currencies.keySet())));
        }
        return Optional.empty();
    }

    private static @NotNull String errorText(@Nullable Player viewer, @Nullable String error) {
        return FailureText.of(viewer, error);
    }

    private enum Action {
        GIVE("give"),
        TAKE("take"),
        SET("set"),
        RESET("reset");

        private final String id;

        Action(@NotNull String id) {
            this.id = id;
        }
    }
}
