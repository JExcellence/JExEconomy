package de.jexcellence.economy.command.player.currencies;

import com.raindropcentral.commands.v2.CommandContext;
import com.raindropcentral.commands.v2.CommandHandler;
import de.jexcellence.economy.JExEconomy;
import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.format.CurrencyFormat;
import de.jexcellence.economy.permission.EconomyPermission;
import de.jexcellence.economy.service.EconomyService;
import de.jexcellence.economy.text.EconomyPanel;
import de.jexcellence.economy.text.EconomyText;
import de.jexcellence.economy.view.CurrencyCreateView;
import de.jexcellence.economy.view.CurrencyFieldInputView;
import de.jexcellence.economy.view.CurrencyOverviewView;
import de.jexcellence.economy.view.EconomyCards;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * JExCommand 2.0 handler collection for {@code /currencies}.
 *
 * <ul>
 *   <li>{@code /currencies} and {@code /currencies overview} - the management GUI (Bedrock: the form mirror).</li>
 *   <li>{@code /currencies list} and {@code /currencies info <currency>} - chat panels.</li>
 *   <li>{@code /currencies create [id symbol prefix suffix]} - inline create, or the create GUI without arguments.</li>
 *   <li>{@code /currencies edit <currency> <field> <value>} and {@code /currencies delete <currency>}.</li>
 *   <li>{@code /currencies help} - permission-gated command panel.</li>
 * </ul>
 *
 * @author JExcellence
 * @since 3.0.0
 */
public final class CurrenciesHandler {

    private static final String FIELD_SYMBOL = CurrencyField.SYMBOL.id();
    private static final String FIELD_PREFIX = CurrencyField.PREFIX.id();
    private static final String FIELD_SUFFIX = CurrencyField.SUFFIX.id();
    private static final Map<String, EconomyPermission> HELP_COMMANDS = Map.of(
            "create", EconomyPermission.CURRENCIES_CREATE,
            "update", EconomyPermission.CURRENCIES_EDIT,
            "delete", EconomyPermission.CURRENCIES_DELETE);
    private static final String KEY = EconomyText.ROOT + "currencies.";
    private static final String HELP = EconomyText.ROOT + "help.currencies.";
    private static final String LABEL = KEY + "label.";
    private static final String PARAM_CURRENCY = "currency";
    private static final String PLUGIN = "plugin";
    private static final double EXAMPLE_AMOUNT = 1234.5;

    private final JExEconomy economy;
    private final EconomyService economyService;

    /**
     * Creates the handler.
     *
     * @param economy the plugin core
     */
    public CurrenciesHandler(@NotNull JExEconomy economy) {
        this.economy = economy;
        this.economyService = economy.economyService();
    }

    /**
     * Returns the path to handler map for registration.
     *
     * @return the handlers
     */
    public @NotNull Map<String, CommandHandler> handlerMap() {
        return Map.ofEntries(
                Map.entry("currencies", this::onRoot),
                Map.entry("currencies.overview", this::onOverview),
                Map.entry("currencies.list", this::onList),
                Map.entry("currencies.info", this::onInfo),
                Map.entry("currencies.create", this::onCreate),
                Map.entry("currencies.edit", this::onEdit),
                Map.entry("currencies.delete", this::onDelete),
                Map.entry("currencies.help", this::onHelp)
        );
    }

    private void onRoot(@NotNull CommandContext ctx) {
        var player = ctx.asPlayer().orElseThrow();
        if (!EconomyPermission.CURRENCIES_OVERVIEW.has(player)) {
            EconomyText.send(player, EconomyText.ROOT + "error.no-permission");
            return;
        }
        openOverview(player);
    }

    private void onOverview(@NotNull CommandContext ctx) {
        openOverview(ctx.asPlayer().orElseThrow());
    }

    private void onList(@NotNull CommandContext ctx) {
        var player = ctx.asPlayer().orElseThrow();
        var all = economyService.getAllCurrencies();
        if (all.isEmpty()) {
            EconomyText.send(player, EconomyText.ROOT + "error.no-currencies");
            return;
        }
        var panel = EconomyPanel.of(player)
                .header(EconomyText.component(player, KEY + "list.header"))
                .context(EconomyText.msg(KEY + "list.context").with("count", all.size()).component(player))
                .gap();
        for (var currency : all.values()) {
            panel.row(EconomyText.msg(KEY + "list.entry")
                            .with("name", CurrencyFormat.displayName(player, currency))
                            .with("id", currency.getIdentifier())
                            .component(player),
                    panel.mini(CurrencyFormat.compact(player, currency, EXAMPLE_AMOUNT)));
        }
        panel.footer(EconomyText.component(player, KEY + "list.footer")).send(player);
    }

    private void onInfo(@NotNull CommandContext ctx) {
        var player = ctx.asPlayer().orElseThrow();
        var currency = ctx.require(PARAM_CURRENCY, Currency.class);
        var panel = EconomyPanel.of(player);
        panel.header(EconomyText.msg(KEY + "info.header")
                        .with("name", CurrencyFormat.displayName(player, currency)).component(player))
                .context(EconomyText.msg(KEY + "info.context").with("id", currency.getIdentifier())
                        .component(player))
                .gap()
                .row(LABEL + FIELD_SYMBOL, orNone(player, currency.getSymbol()))
                .row(LABEL + FIELD_PREFIX, orNone(player, currency.getPrefix()))
                .row(LABEL + FIELD_SUFFIX, orNone(player, currency.getSuffix()))
                .row(LABEL + "icon", orNone(player, currency.getIcon()))
                .row(LABEL + "example", panel.mini(CurrencyFormat.compact(player, currency, EXAMPLE_AMOUNT)))
                .send(player);
    }

    private void onCreate(@NotNull CommandContext ctx) {
        var player = ctx.asPlayer().orElseThrow();
        var id = ctx.get("identifier", String.class).orElse(null);
        if (id == null || id.isBlank()) {
            openCreate(player);
            return;
        }
        var symbol = ctx.get(FIELD_SYMBOL, String.class).orElse(null);
        if (symbol == null || symbol.isBlank()) {
            EconomyText.send(player, KEY + "create.usage");
            return;
        }
        if (CurrencyFieldInputView.rejectReservedIdentifier(player, CurrencyField.IDENTIFIER.id(), id)) {
            return;
        }
        if (economyService.findCurrency(id).isPresent()) {
            EconomyText.send(player, EconomyText.msg(KEY + "create.exists").with(PARAM_CURRENCY, id));
            return;
        }
        var currency = new Currency(id, symbol, ctx.get(FIELD_PREFIX, String.class).orElse(""),
                ctx.get(FIELD_SUFFIX, String.class).orElse(""), null);
        economyService.createCurrency(currency, player).thenAccept(success -> EconomyText.send(player,
                EconomyText.msg(KEY + (Boolean.TRUE.equals(success) ? "create.done" : "create.failed"))
                        .with(PARAM_CURRENCY, id)));
    }

    private void onEdit(@NotNull CommandContext ctx) {
        var player = ctx.asPlayer().orElseThrow();
        var current = ctx.require(PARAM_CURRENCY, Currency.class);
        var field = ctx.require("field", CurrencyField.class).id();
        var value = ctx.require("value", String.class);
        boolean renamed = !value.trim().equalsIgnoreCase(current.getIdentifier());
        if (renamed && CurrencyFieldInputView.rejectReservedIdentifier(player, field, value)) {
            return;
        }
        var updated = CurrencyFieldInputView.applyField(current, field, value);
        economyService.updateCurrency(current.getIdentifier(), updated, player).thenAccept(success -> {
            boolean saved = Boolean.TRUE.equals(success);
            EconomyText.send(player, EconomyText.msg(KEY + (saved ? "edit.done" : "edit.failed"))
                    .with(PARAM_CURRENCY, saved ? updated.getIdentifier() : current.getIdentifier())
                    .with("field", EconomyText.msg(EconomyText.ROOT + "field." + field + ".label").plain(player))
                    .with("value", value));
        });
    }

    private void onDelete(@NotNull CommandContext ctx) {
        var player = ctx.asPlayer().orElseThrow();
        var id = ctx.require(PARAM_CURRENCY, Currency.class).getIdentifier();
        economyService.deleteCurrency(id, player).thenAccept(success -> EconomyText.send(player,
                EconomyText.msg(KEY + (Boolean.TRUE.equals(success) ? "delete.done" : "delete.failed"))
                        .with(PARAM_CURRENCY, id)));
    }

    private void onHelp(@NotNull CommandContext ctx) {
        var player = ctx.asPlayer().orElseThrow();
        var alias = ctx.alias();
        var panel = EconomyPanel.of(player)
                .header(EconomyText.component(player, HELP + "header"))
                .context(EconomyText.component(player, HELP + "context"))
                .gap();
        if (EconomyPermission.CURRENCIES_OVERVIEW.has(player)) {
            panel.command(HELP + "overview", alias);
        }
        panel.command(HELP + "list", alias).command(HELP + "info", alias);
        for (String command : List.of("create", "update", "delete")) {
            if (HELP_COMMANDS.get(command).has(player)) {
                panel.command(HELP + command, alias);
            }
        }
        panel.send(player);
    }

    private void openOverview(@NotNull Player player) {
        if (economy.bedrockForms().isBedrock(player)) {
            economy.bedrockForms().openOverview(player);
            return;
        }
        economy.viewFrame().open(CurrencyOverviewView.class, player, Map.of(PLUGIN, economy));
    }

    private void openCreate(@NotNull Player player) {
        var draft = EconomyCards.blankDraft();
        if (economy.bedrockForms().isBedrock(player)) {
            economy.bedrockForms().openCreate(player, draft);
            return;
        }
        economy.viewFrame().open(CurrencyCreateView.class, player, Map.of(PLUGIN, economy, "draft_currency", draft));
    }

    private static @NotNull String orNone(@NotNull Player viewer, @NotNull String value) {
        return value.isBlank() ? EconomyText.msg(EconomyText.ROOT + "common.none").plain(viewer) : value;
    }
}
