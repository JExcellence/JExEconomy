package de.jexcellence.economy.command.player.coupon;

import com.raindropcentral.commands.v2.CommandContext;
import com.raindropcentral.commands.v2.CommandHandler;
import de.jexcellence.economy.JExEconomy;
import de.jexcellence.economy.coupon.CouponItemFactory;
import de.jexcellence.economy.coupon.CouponTemplate;
import de.jexcellence.economy.coupon.CouponTemplateRegistry;
import de.jexcellence.economy.database.entity.RedeemedCoupon;
import de.jexcellence.economy.database.repository.RedeemedCouponRepository;
import de.jexcellence.economy.format.CurrencyFormat;
import de.jexcellence.economy.service.EconomyService;
import de.jexcellence.economy.text.EconomyPanel;
import de.jexcellence.economy.text.EconomyText;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JExCommand 2.0 handler collection for {@code /coupon}.
 *
 * <ul>
 *   <li>{@code /coupon} and {@code /coupon list} - every coupon template with its payouts as a panel.</li>
 *   <li>{@code /coupon give <player> <template> [amount]} - mints and gives coupons.</li>
 *   <li>{@code /coupon reload} - re-reads coupons.yml.</li>
 *   <li>{@code /coupon history <player>} - the latest redemptions of a player as a panel.</li>
 * </ul>
 *
 * @author JExcellence
 * @since 3.0.0
 */
public final class CouponHandler {

    private static final String KEY = EconomyText.ROOT + "coupon.";
    private static final String PARAM_PLAYER = "player";
    private static final String PARAM_TEMPLATE = "template";
    private static final String PARAM_AMOUNT = "amount";
    private static final String PARAM_COUNT = "count";
    private static final int HISTORY_LIMIT = 20;

    private final JExEconomy economy;
    private final EconomyService economyService;
    private final CouponTemplateRegistry registry;
    private final RedeemedCouponRepository repo;

    /**
     * Creates the handler.
     *
     * @param economy the plugin core
     */
    public CouponHandler(@NotNull JExEconomy economy) {
        this.economy = economy;
        this.economyService = economy.economyService();
        this.registry = economy.couponRegistry();
        this.repo = economy.couponRepo();
    }

    /**
     * Returns the path to handler map for registration.
     *
     * @return the handlers
     */
    public @NotNull Map<String, CommandHandler> handlerMap() {
        return Map.ofEntries(
                Map.entry("coupon", this::onList),
                Map.entry("coupon.give", this::onGive),
                Map.entry("coupon.list", this::onList),
                Map.entry("coupon.reload", this::onReload),
                Map.entry("coupon.history", this::onHistory)
        );
    }

    private void onGive(@NotNull CommandContext ctx) {
        var sender = ctx.sender();
        var target = ctx.require(PARAM_PLAYER, OfflinePlayer.class);
        var template = ctx.require(PARAM_TEMPLATE, CouponTemplate.class);
        long amount = ctx.get(PARAM_AMOUNT, Long.class).orElse(1L);
        if (!(target instanceof Player onlineTarget) || !onlineTarget.isOnline()) {
            EconomyText.send(sender, EconomyText.msg(KEY + "give.offline")
                    .with(PARAM_PLAYER, EconomyText.nameOf(sender, target)));
            return;
        }
        for (long i = 0; i < amount; i++) {
            var stack = CouponItemFactory.mint(economy.getPlugin(), template, economyService, onlineTarget);
            economy.extensions().current().stampIssuedItem(stack, onlineTarget.getUniqueId());
            onlineTarget.getInventory().addItem(stack).values().forEach(leftover ->
                    onlineTarget.getWorld().dropItemNaturally(onlineTarget.getLocation(), leftover));
        }
        EconomyText.send(sender, EconomyText.msg(KEY + "give.done")
                .with(PARAM_TEMPLATE, template.id())
                .with(PARAM_AMOUNT, amount)
                .with(PARAM_PLAYER, onlineTarget.getName()));
        boolean selfGift = sender instanceof Player senderPlayer
                && senderPlayer.getUniqueId().equals(onlineTarget.getUniqueId());
        if (!selfGift) {
            EconomyText.send(onlineTarget, EconomyText.msg(KEY + "give.received")
                    .with(PARAM_TEMPLATE, template.id())
                    .with(PARAM_AMOUNT, amount));
        }
    }

    private void onList(@NotNull CommandContext ctx) {
        var sender = ctx.sender();
        var templates = registry.all();
        if (templates.isEmpty()) {
            EconomyText.send(sender, KEY + "list.empty");
            return;
        }
        var panel = EconomyPanel.of(sender)
                .header(EconomyText.component(sender, KEY + "list.header"))
                .context(EconomyText.msg(KEY + "list.context").with(PARAM_COUNT, templates.size())
                        .component(EconomyText.viewerOf(sender)))
                .gap();
        for (CouponTemplate template : templates) {
            panel.rowText(template.id(), panel.mini(payouts(sender, template)));
        }
        panel.footer(EconomyText.component(sender, KEY + "list.footer")).send(sender);
    }

    private void onReload(@NotNull CommandContext ctx) {
        var sender = ctx.sender();
        registry.reload(economy.getPlugin());
        EconomyText.send(sender, EconomyText.msg(KEY + "reload.done").with(PARAM_COUNT, registry.all().size()));
    }

    private void onHistory(@NotNull CommandContext ctx) {
        var sender = ctx.sender();
        var target = ctx.require(PARAM_PLAYER, OfflinePlayer.class);
        String targetName = EconomyText.nameOf(sender, target);
        repo.findByRedeemerAsync(target.getUniqueId())
                .thenAccept(rows -> sendHistory(sender, targetName, rows))
                .exceptionally(ex -> {
                    EconomyText.send(sender, KEY + "history.error");
                    return null;
                });
    }

    private static void sendHistory(@NotNull CommandSender sender, @NotNull String targetName,
                                    @NotNull List<RedeemedCoupon> rows) {
        Player viewer = EconomyText.viewerOf(sender);
        if (rows.isEmpty()) {
            EconomyText.send(sender, EconomyText.msg(KEY + "history.empty").with(PARAM_PLAYER, targetName));
            return;
        }
        var dates = DateTimeFormatter.ofPattern(
                EconomyText.msg(EconomyText.ROOT + "log.date-pattern").plain(viewer),
                viewer != null ? viewer.locale() : Locale.ROOT);
        var panel = EconomyPanel.of(sender)
                .header(EconomyText.msg(KEY + "history.header").with(PARAM_PLAYER, targetName).component(viewer))
                .context(EconomyText.msg(KEY + "history.context").with(PARAM_COUNT, rows.size()).component(viewer))
                .gap();
        rows.stream().limit(HISTORY_LIMIT).forEach(row -> panel.rowText(
                row.getRedeemedAt().atZone(RedeemedCoupon.ZONE).format(dates), panel.value(row.getTemplateId())));
        panel.send(sender);
    }

    private @NotNull String payouts(@NotNull CommandSender sender, @NotNull CouponTemplate template) {
        Player viewer = EconomyText.viewerOf(sender);
        String joiner = EconomyText.msg(KEY + "list.joiner").miniMessage(viewer);
        return String.join(joiner, template.payouts().stream()
                .map(payout -> economyService.findCurrency(payout.currency())
                        .map(currency -> CurrencyFormat.compact(viewer, currency, payout.amount()))
                        .orElseGet(() -> CurrencyFormat.amount(payout.amount()) + " " + payout.currency()))
                .toList());
    }
}
