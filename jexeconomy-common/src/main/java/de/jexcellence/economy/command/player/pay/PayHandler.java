package de.jexcellence.economy.command.player.pay;

import com.raindropcentral.commands.v2.CommandContext;
import com.raindropcentral.commands.v2.CommandHandler;
import de.jexcellence.economy.api.TransactionResult;
import de.jexcellence.economy.bedrock.EconomyForms;
import de.jexcellence.economy.command.KnownPlayers;
import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.format.CurrencyFormat;
import de.jexcellence.economy.service.EconomyService;
import de.jexcellence.economy.service.FailureReason;
import de.jexcellence.economy.text.EconomyText;
import de.jexcellence.economy.text.FailureText;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * JExCommand 2.0 handler for {@code /pay}.
 *
 * <p>Transfers {@code amount} of the given (or default) currency from the invoking player to the target. An
 * extension may book a leg outside the wallets, also while the recipient is offline. Payments from the configured threshold on
 * ({@code pay-confirm-threshold} in {@code currencies.yml}) wait for a confirmation: a chat line with Confirm and
 * Cancel buttons, a form for Bedrock players, or the same command again, within 30 seconds. No fee is taken.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public final class PayHandler {

    private static final String KEY = EconomyText.ROOT + "pay.";
    private static final String ERROR = EconomyText.ROOT + "error.";
    private static final String PARAM_VALUE = "value";
    private static final String PARAM_TARGET = "target";
    private static final String PARAM_CURRENCY = "currency";
    private static final String KEY_EXPIRED = KEY + "confirm-expired";
    private static final Map<FailureReason, String> ERROR_KEYS = Map.of(
            FailureReason.TRADE_BLOCKED, KEY + "isolated-blocked",
            FailureReason.TRANSFER_IN_FLIGHT, KEY + "in-progress",
            FailureReason.BANK_DENIED, KEY + "bank-denied",
            FailureReason.RECIPIENT_BANK_FULL, KEY + "recipient-bank-full",
            FailureReason.WHOLE_COINS, KEY + "whole-coins");

    private final EconomyService economyService;
    private final Supplier<EconomyForms> forms;
    private final PlatformScheduler scheduler;
    private final PayConfirmations confirmations = new PayConfirmations(System::currentTimeMillis);

    private record PayRequest(@NotNull Player sender, @NotNull OfflinePlayer target, @NotNull Currency currency,
                              double amount) {
    }

    /**
     * Creates the handler.
     *
     * @param economyService the economy service
     * @param forms          the Bedrock forms, read when a Bedrock player confirms a payment
     * @param scheduler      returns to the player's thread after the off-thread player lookup
     */
    public PayHandler(@NotNull EconomyService economyService, @NotNull Supplier<EconomyForms> forms,
                      @NotNull PlatformScheduler scheduler) {
        this.economyService = economyService;
        this.forms = forms;
        this.scheduler = scheduler;
    }

    /**
     * Returns the path to handler map for registration.
     *
     * @return the handlers
     */
    public @NotNull Map<String, CommandHandler> handlerMap() {
        return Map.of("pay", this::onPay);
    }

    private void onPay(@NotNull CommandContext ctx) {
        var player = ctx.asPlayer().orElseThrow();
        var target = ctx.require(PARAM_TARGET, OfflinePlayer.class);
        var amount = ctx.require("amount", Double.class);
        var currency = ctx.get(PARAM_CURRENCY, Currency.class)
                .orElseGet(() -> economyService.defaultPayCurrency().orElse(null));
        if (currency == null) {
            EconomyText.send(player, KEY + "no-default-currency");
            return;
        }
        var request = new PayRequest(player, target, currency, amount);
        if (!isValid(request)) {
            return;
        }
        KnownPlayers.whenKnown(economyService, player, target,
                () -> scheduler.runAtEntity(player, () -> confirmOrExecute(request)));
    }

    private void confirmOrExecute(@NotNull PayRequest request) {
        Player player = request.sender();
        OfflinePlayer target = request.target();
        Currency currency = request.currency();
        double amount = request.amount();
        if (PayConfirmations.needsConfirmation(amount, economyService.payConfirmThreshold())
                && !confirmations.confirmRepeat(player.getUniqueId(), target.getUniqueId(),
                currency.getIdentifier(), amount)) {
            askConfirmation(request);
            return;
        }
        execute(request);
    }

    private boolean isValid(@NotNull PayRequest request) {
        Player player = request.sender();
        String blockedKey = null;
        if (!economyService.isPayable(request.currency().getIdentifier())) {
            blockedKey = KEY + "currency-not-allowed";
        } else if (request.amount() <= 0) {
            blockedKey = ERROR + "invalid-amount";
        } else if (request.target().getUniqueId().equals(player.getUniqueId())) {
            blockedKey = ERROR + "self-payment";
        }
        if (blockedKey == null) {
            return true;
        }
        EconomyText.send(player, EconomyText.msg(blockedKey)
                .with(PARAM_CURRENCY, CurrencyFormat.displayName(player, request.currency()))
                .with("player", EconomyText.nameOf(player, request.target())));
        return false;
    }

    private void askConfirmation(@NotNull PayRequest request) {
        Player player = request.sender();
        PayConfirmations.Pending pending = confirmations.arm(player.getUniqueId(), request.target().getUniqueId(),
                request.currency().getIdentifier(), request.amount());
        Runnable confirm = () -> onConfirm(request, pending);
        Runnable cancel = () -> onCancel(request, pending);
        EconomyForms bedrock = forms.get();
        if (bedrock != null && bedrock.isBedrock(player)) {
            String value = CurrencyFormat.amount(request.amount()) + " "
                    + CurrencyFormat.displayName(player, request.currency());
            bedrock.confirmPayment(player, new EconomyForms.PaymentPrompt(value,
                    EconomyText.nameOf(player, request.target()), confirm, cancel));
            return;
        }
        Component prompt = EconomyText.msg(KEY + "confirm-panel")
                .with(PARAM_VALUE, CurrencyFormat.compact(player, request.currency(), request.amount()))
                .with(PARAM_TARGET, EconomyText.nameOf(player, request.target()))
                .component(player);
        EconomyText.sendLine(player, prompt
                .append(Component.space())
                .append(button(player, KEY + "confirm-button", confirm))
                .append(Component.space())
                .append(button(player, KEY + "cancel-button", cancel)));
    }

    private static @NotNull Component button(@NotNull Player player, @NotNull String key, @NotNull Runnable action) {
        return EconomyText.component(player, key).clickEvent(ClickEvent.callback(audience -> action.run(),
                ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMillis(PayConfirmations.WINDOW_MS))
                        .build()));
    }

    private void onConfirm(@NotNull PayRequest request, @NotNull PayConfirmations.Pending pending) {
        Player player = request.sender();
        if (!player.isOnline() || !confirmations.confirm(player.getUniqueId(), pending)) {
            EconomyText.send(player, KEY_EXPIRED);
            return;
        }
        execute(request);
    }

    private void onCancel(@NotNull PayRequest request, @NotNull PayConfirmations.Pending pending) {
        Player player = request.sender();
        boolean dropped = confirmations.cancel(player.getUniqueId(), pending);
        EconomyText.send(player, dropped ? KEY + "cancelled" : KEY_EXPIRED);
    }

    private void execute(@NotNull PayRequest request) {
        economyService.transfer(request.sender(), request.target(), request.currency(), request.amount())
                .thenAccept(result -> reportResult(request, result));
    }

    private static void reportResult(@NotNull PayRequest request, @NotNull TransactionResult result) {
        Player player = request.sender();
        Currency currency = request.currency();
        FailureReason reason = FailureReason.of(result.error()).orElse(null);
        String errorKey = reason == null ? null : ERROR_KEYS.get(reason);
        if (result.isSuccess()) {
            reportSuccess(request, result);
        } else if (errorKey != null) {
            EconomyText.send(player, EconomyText.msg(errorKey)
                    .with(PARAM_TARGET, EconomyText.nameOf(player, request.target())));
        } else if (reason == FailureReason.INSUFFICIENT_FUNDS) {
            EconomyText.send(player, EconomyText.msg(ERROR + "insufficient-funds")
                    .with(PARAM_VALUE, CurrencyFormat.compact(player, currency, request.amount()))
                    .with("balance", CurrencyFormat.compact(player, currency, result.balance())));
        } else {
            EconomyText.send(player, EconomyText.msg(KEY + "failed")
                    .with("error", FailureText.of(player, result.error())));
        }
    }

    private static void reportSuccess(@NotNull PayRequest request, @NotNull TransactionResult result) {
        Player player = request.sender();
        Currency currency = request.currency();
        EconomyText.send(player, EconomyText.msg(KEY + "sent")
                .with(PARAM_VALUE, CurrencyFormat.compact(player, currency, request.amount()))
                .with(PARAM_TARGET, EconomyText.nameOf(player, request.target()))
                .with("balance", CurrencyFormat.compact(player, currency, result.balance())));
        Player onlineTarget = Bukkit.getPlayer(request.target().getUniqueId());
        if (onlineTarget != null) {
            EconomyText.send(onlineTarget, EconomyText.msg(KEY + "received")
                    .with(PARAM_VALUE, CurrencyFormat.compact(onlineTarget, currency, request.amount()))
                    .with("sender", player.getName()));
        }
    }

    /**
     * Exposes the root handler.
     *
     * @param instance the handler instance
     * @return the root command handler
     */
    public static @NotNull CommandHandler asHandler(@NotNull PayHandler instance) {
        return instance::onPay;
    }
}
