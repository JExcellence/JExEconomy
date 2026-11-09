package de.jexcellence.economy.bedrock;

import de.jexcellence.economy.command.player.currencies.CurrencyField;
import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.format.CurrencyFormat;
import de.jexcellence.economy.text.EconomyText;
import de.jexcellence.economy.view.CurrencyFieldInputView;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.response.CustomFormResponse;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Bedrock forms of the write side: one custom form with the five currency fields for create and edit, and the
 * modal confirmation for deleting a currency or resetting all of its balances.
 *
 * @author JExcellence
 * @since 4.1.0
 */
final class CurrencyEditForms {

    private static final String MSG = EconomyText.ROOT + "currencies.";
    private static final List<String> FIELDS = List.of("identifier", "symbol", "icon", "prefix", "suffix");
    private static final String PARAM_CURRENCY = "currency";

    private final EconomyForms forms;

    CurrencyEditForms(@NotNull EconomyForms forms) {
        this.forms = forms;
    }

    void create(@NotNull Player player, @NotNull Currency draft) {
        open(player, draft, null);
    }

    void edit(@NotNull Player player, @NotNull Currency currency) {
        open(player, currency, currency.getIdentifier());
    }

    private void open(@NotNull Player player, @NotNull Currency draft, @Nullable String original) {
        CustomForm.Builder form = CustomForm.builder()
                .title(forms.text(player, original == null ? "form.create-title" : "form.edit-title"));
        for (String field : FIELDS) {
            form.input(CurrencyFieldInputView.fieldLabel(player, field),
                    EconomyText.msg(EconomyText.ROOT + "field." + field + ".description").plain(player),
                    CurrencyFieldInputView.fieldValue(draft, field));
        }
        form.validResultHandler(response -> forms.onMain(player, () -> submit(player, response, draft, original)));
        forms.send(player, form.build());
    }

    private void submit(@NotNull Player player, @NotNull CustomFormResponse response, @NotNull Currency draft,
                        @Nullable String original) {
        Currency updated = draft;
        for (int i = 0; i < FIELDS.size(); i++) {
            String value = response.asInput(i);
            updated = CurrencyFieldInputView.applyField(updated, FIELDS.get(i), value == null ? "" : value.trim());
        }
        boolean renamed = !updated.getIdentifier().equalsIgnoreCase(original == null ? "" : original);
        if (renamed && CurrencyFieldInputView.rejectReservedIdentifier(player, CurrencyField.IDENTIFIER.id(),
                updated.getIdentifier())) {
            open(player, updated, original);
            return;
        }
        String problem = problemOf(updated);
        if (problem != null) {
            EconomyText.send(player, EconomyText.msg(EconomyText.ROOT + problem).with("value", updated.getIcon()));
            open(player, updated, original);
            return;
        }
        Currency result = updated;
        var service = forms.economy().economyService();
        if (original == null) {
            service.createCurrency(result, player).thenAccept(success -> EconomyText.send(player,
                    EconomyText.msg(MSG + (Boolean.TRUE.equals(success) ? "create.done" : "create.failed"))
                            .with(PARAM_CURRENCY, result.getIdentifier())));
            return;
        }
        service.updateCurrency(original, result, player).thenAccept(success -> EconomyText.send(player,
                EconomyText.msg(MSG + (Boolean.TRUE.equals(success) ? "edit.saved" : "edit.failed"))
                        .with(PARAM_CURRENCY, Boolean.TRUE.equals(success) ? result.getIdentifier() : original)));
    }

    private static @Nullable String problemOf(@NotNull Currency currency) {
        if (currency.getIdentifier().isBlank() || currency.getSymbol().isBlank()) {
            return "view.form.save-missing-chat";
        }
        if (!currency.getIcon().isBlank() && !CurrencyFieldInputView.isValidMaterial(currency.getIcon())) {
            return "view.input.invalid-material";
        }
        return null;
    }

    void danger(@NotNull Player player, @NotNull Currency currency, boolean delete) {
        String mode = delete ? "delete" : "reset";
        String name = CurrencyFormat.displayName(player, currency);
        String content = EconomyText.msg(EconomyText.ROOT + "view.danger." + mode + ".header.description")
                .with(PARAM_CURRENCY, name).plain(player);
        forms.send(player, ModalForm.builder()
                .title(EconomyText.msg(EconomyText.ROOT + "view.danger." + mode + ".header.name")
                        .with(PARAM_CURRENCY, name).plain(player))
                .content(content)
                .button1(forms.text(player, "danger.confirm"))
                .button2(forms.text(player, "danger.keep"))
                .validResultHandler(response -> forms.onMain(player, () -> {
                    if (response.clickedButtonId() == 0) {
                        runDanger(player, currency, delete);
                    } else {
                        forms.browse().detail(player, currency);
                    }
                }))
                .build());
    }

    private void runDanger(@NotNull Player player, @NotNull Currency currency, boolean delete) {
        var service = forms.economy().economyService();
        String id = currency.getIdentifier();
        if (delete) {
            service.deleteCurrency(id, player).thenAccept(success -> EconomyText.send(player,
                    EconomyText.msg(MSG + (Boolean.TRUE.equals(success) ? "delete.done" : "delete.failed"))
                            .with(PARAM_CURRENCY, id)));
            return;
        }
        service.resetCurrency(id, "Reset by " + player.getName()).thenAccept(count -> {
            boolean refused = count == null || count < 0;
            EconomyText.send(player, EconomyText.msg(MSG + (refused ? "reset.refused" : "reset.done"))
                    .with(PARAM_CURRENCY, CurrencyFormat.displayName(player, currency))
                    .with("count", refused ? 0 : count));
        });
    }
}
