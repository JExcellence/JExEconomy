package de.jexcellence.economy.bedrock;

import de.jexcellence.economy.database.entity.Account;
import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.format.CurrencyFormat;
import de.jexcellence.economy.permission.EconomyPermission;
import de.jexcellence.economy.text.EconomyPanel;
import de.jexcellence.economy.text.EconomyText;
import de.jexcellence.economy.view.EconomyCards;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.SimpleForm;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Bedrock forms of the read side: the currency overview, one currency with its actions, and the leaderboard.
 * Each button list is paired with an action list, so the clicked index runs exactly the shown action.
 *
 * @author JExcellence
 * @since 4.1.0
 */
final class CurrencyBrowseForms {

    private static final String LABEL = EconomyText.ROOT + "field.";
    private static final int TOP_SIZE = 28;
    private static final double EXAMPLE_AMOUNT = 1234.5;

    private final EconomyForms forms;

    CurrencyBrowseForms(@NotNull EconomyForms forms) {
        this.forms = forms;
    }

    void overview(@NotNull Player player) {
        List<Currency> currencies = sorted();
        List<String> buttons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (Currency currency : currencies) {
            buttons.add(EconomyText.msg(EconomyText.ROOT + "bedrock.overview.entry")
                    .with("name", CurrencyFormat.displayName(player, currency))
                    .with("id", currency.getIdentifier()).plain(player));
            actions.add(() -> detail(player, currency));
        }
        if (EconomyPermission.CURRENCIES_CREATE.has(player)) {
            buttons.add(forms.text(player, "overview.create"));
            actions.add(() -> forms.edit().create(player, EconomyCards.blankDraft()));
        }
        String content = EconomyText.msg(EconomyText.ROOT + "bedrock.overview.content")
                .with("count", currencies.size()).plain(player);
        send(player, forms.text(player, "overview.title"), content, buttons, actions);
    }

    void detail(@NotNull Player player, @NotNull Currency currency) {
        String content = EconomyPanel.of(player)
                .row(LABEL + "symbol.label", orNone(player, currency.getSymbol()))
                .row(LABEL + "prefix.label", orNone(player, currency.getPrefix()))
                .row(LABEL + "suffix.label", orNone(player, currency.getSuffix()))
                .row(LABEL + "icon.label", orNone(player, currency.getIcon()))
                .row(EconomyText.ROOT + "view.common.example",
                        EconomyText.mini(player, CurrencyFormat.compact(player, currency, EXAMPLE_AMOUNT)))
                .plainText();
        List<String> buttons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        buttons.add(forms.text(player, "detail.top"));
        actions.add(() -> leaderboard(player, currency));
        if (EconomyPermission.CURRENCIES_EDIT.hasOrAdmin(player)) {
            buttons.add(forms.text(player, "detail.edit"));
            actions.add(() -> forms.edit().edit(player, currency));
        }
        if (EconomyPermission.CURRENCIES_RESET.hasOrAdmin(player)) {
            buttons.add(forms.text(player, "detail.reset"));
            actions.add(() -> forms.edit().danger(player, currency, false));
        }
        if (EconomyPermission.CURRENCIES_DELETE.hasOrAdmin(player)) {
            buttons.add(forms.text(player, "detail.delete"));
            actions.add(() -> forms.edit().danger(player, currency, true));
        }
        buttons.add(forms.text(player, "back"));
        actions.add(() -> overview(player));
        send(player, CurrencyFormat.displayName(player, currency), content, buttons, actions);
    }

    void leaderboard(@NotNull Player player, @NotNull Currency currency) {
        forms.economy().economyService().getTopAccounts(currency, TOP_SIZE)
                .thenAccept(accounts -> forms.onMain(player, () -> showLeaderboard(player, currency, accounts)));
    }

    private void showLeaderboard(@NotNull Player player, @NotNull Currency currency, @NotNull List<Account> accounts) {
        EconomyPanel panel = EconomyPanel.of(player);
        int rank = 0;
        for (Account account : accounts) {
            rank++;
            panel.rowText(EconomyText.msg(EconomyText.ROOT + "balance.top.rank").with("rank", rank)
                            .with("player", account.getPlayer().getPlayerName()).plain(player),
                    EconomyText.mini(player, CurrencyFormat.compact(player, currency, account.getBalance().doubleValue())));
        }
        String content = accounts.isEmpty() ? forms.text(player, "leaderboard.empty") : panel.plainText();
        List<String> buttons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        List<Currency> currencies = sorted();
        if (currencies.size() > 1) {
            Currency next = currencies.get((indexOf(currencies, currency) + 1) % currencies.size());
            buttons.add(EconomyText.msg(EconomyText.ROOT + "bedrock.leaderboard.next")
                    .with("currency", CurrencyFormat.displayName(player, next)).plain(player));
            actions.add(() -> leaderboard(player, next));
        }
        buttons.add(forms.text(player, "back"));
        actions.add(() -> detail(player, currency));
        String title = EconomyText.msg(EconomyText.ROOT + "bedrock.leaderboard.title")
                .with("currency", CurrencyFormat.displayName(player, currency)).plain(player);
        send(player, title, content, buttons, actions);
    }

    private void send(@NotNull Player player, @NotNull String title, @NotNull String content,
                      @NotNull List<String> buttons, @NotNull List<Runnable> actions) {
        SimpleForm.Builder form = SimpleForm.builder().title(title).content(content);
        buttons.forEach(form::button);
        form.validResultHandler(response -> forms.onMain(player, () -> {
            int clicked = response.clickedButtonId();
            if (clicked >= 0 && clicked < actions.size()) {
                actions.get(clicked).run();
            }
        }));
        forms.send(player, form.build());
    }

    private @NotNull List<Currency> sorted() {
        List<Currency> currencies = new ArrayList<>(forms.economy().economyService().getAllCurrencies().values());
        currencies.sort(Comparator.comparing(Currency::getIdentifier));
        return currencies;
    }

    private static int indexOf(@NotNull List<Currency> currencies, @NotNull Currency active) {
        for (int i = 0; i < currencies.size(); i++) {
            if (currencies.get(i).getIdentifier().equalsIgnoreCase(active.getIdentifier())) {
                return i;
            }
        }
        return 0;
    }

    private static @NotNull String orNone(@NotNull Player player, @NotNull String value) {
        return value.isBlank() ? EconomyText.msg(EconomyText.ROOT + "common.none").plain(player) : value;
    }
}
