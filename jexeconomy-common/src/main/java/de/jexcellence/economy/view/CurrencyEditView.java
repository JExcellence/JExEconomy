package de.jexcellence.economy.view;

import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.text.EconomyText;
import me.devnatan.inventoryframework.context.OpenContext;
import me.devnatan.inventoryframework.context.RenderContext;
import me.devnatan.inventoryframework.context.SlotClickContext;
import me.devnatan.inventoryframework.state.State;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * The edit form for an existing currency. Required initial data: {@code plugin}, {@code draft_currency} (carries
 * the current edits) and {@code original_identifier} (finds the cache entry even when the identifier changed).
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class CurrencyEditView extends CurrencyFormView {

    private static final String KEY = EconomyText.ROOT + "currencies.edit.";
    private static final String PARAM_CURRENCY = "currency";

    private final State<String> originalIdentifier = initialState(ORIGINAL);

    /**
     * Creates the view; back leads to the currency detail.
     */
    public CurrencyEditView() {
        super(CurrencyDetailView.class);
    }

    @Override
    protected String translationKey() {
        return "economy-v2.view.form.edit";
    }

    @Override
    protected Map<String, Object> titlePlaceholders(@NotNull OpenContext open) {
        return Map.of(PARAM_CURRENCY, originalIdentifier.get(open));
    }

    @Override
    protected @NotNull String mode() {
        return CurrencyFieldInputView.MODE_EDIT;
    }

    @Override
    protected @Nullable String originalIdentifier(@NotNull RenderContext render) {
        return originalIdentifier.get(render);
    }

    @Override
    protected void handleBack(@NotNull SlotClickContext ctx) {
        var eco = economy.get(ctx);
        var current = eco.economyService().findCurrency(originalIdentifier.get(ctx));
        if (current.isPresent()) {
            ctx.openForPlayer(CurrencyDetailView.class, Map.of(PLUGIN, eco, PARAM_CURRENCY, current.get()));
        } else {
            ctx.openForPlayer(CurrencyOverviewView.class, Map.of(PLUGIN, eco));
        }
    }

    @Override
    protected void save(@NotNull SlotClickContext click, @NotNull Currency currency) {
        Player player = click.getPlayer();
        String original = originalIdentifier.get(click);
        economy.get(click).economyService().updateCurrency(original, currency, player).thenAccept(success -> {
            boolean saved = Boolean.TRUE.equals(success);
            EconomyText.send(player, EconomyText.msg(KEY + (saved ? "saved" : "failed"))
                    .with(PARAM_CURRENCY, saved ? currency.getIdentifier() : original));
        });
    }
}
