package de.jexcellence.economy.view;

import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.text.EconomyText;
import me.devnatan.inventoryframework.context.RenderContext;
import me.devnatan.inventoryframework.context.SlotClickContext;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The create form for a new currency. Required initial data: {@code plugin} and {@code draft_currency} (may be
 * blank on the first open). Saving creates the currency through the economy service.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class CurrencyCreateView extends CurrencyFormView {

    private static final String KEY = EconomyText.ROOT + "currencies.create.";

    /**
     * Creates the view; back leads to the currency overview.
     */
    public CurrencyCreateView() {
        super(CurrencyOverviewView.class);
    }

    @Override
    protected String translationKey() {
        return "economy-v2.view.form.create";
    }

    @Override
    protected @NotNull String mode() {
        return CurrencyFieldInputView.MODE_CREATE;
    }

    @Override
    protected @Nullable String originalIdentifier(@NotNull RenderContext render) {
        return null;
    }

    @Override
    protected void save(@NotNull SlotClickContext click, @NotNull Currency currency) {
        Player player = click.getPlayer();
        economy.get(click).economyService().createCurrency(currency, player).thenAccept(success ->
                EconomyText.send(player, EconomyText.msg(KEY + (Boolean.TRUE.equals(success) ? "done" : "failed"))
                        .with("currency", currency.getIdentifier())));
    }
}
