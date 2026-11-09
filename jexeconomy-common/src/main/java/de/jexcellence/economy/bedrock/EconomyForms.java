package de.jexcellence.economy.bedrock;

import de.jexcellence.economy.JExEconomy;
import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.text.EconomyText;
import de.jexcellence.jexplatform.gui.style.BedrockViewers;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.floodgate.api.FloodgateApi;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bedrock (Floodgate) mirror of the currency GUIs: overview, detail, leaderboard, create/edit and the destructive
 * confirmations as Cumulus forms. Callers check {@link #isBedrock(Player)} first; Floodgate classes are touched only
 * for Bedrock players, so servers without Floodgate never load them.
 *
 * @author JExcellence
 * @since 4.1.0
 */
public final class EconomyForms {

    private static final Logger LOGGER = Logger.getLogger(EconomyForms.class.getName());

    private final JExEconomy economy;
    private final CurrencyBrowseForms browse;
    private final CurrencyEditForms edit;

    /**
     * Creates the forms.
     *
     * @param economy the plugin core
     */
    public EconomyForms(@NotNull JExEconomy economy) {
        this.economy = economy;
        this.browse = new CurrencyBrowseForms(this);
        this.edit = new CurrencyEditForms(this);
    }

    /**
     * Whether a player joined through Geyser/Floodgate.
     *
     * @param player the player
     * @return {@code true} for Bedrock players
     */
    public boolean isBedrock(@NotNull Player player) {
        return BedrockViewers.isBedrock(player);
    }

    /**
     * Opens the currency overview.
     *
     * @param player the Bedrock player
     */
    public void openOverview(@NotNull Player player) {
        browse.overview(player);
    }

    /**
     * Opens the create form with a draft.
     *
     * @param player the Bedrock player
     * @param draft  the draft currency
     */
    public void openCreate(@NotNull Player player, @NotNull Currency draft) {
        edit.create(player, draft);
    }

    /**
     * A {@code /pay} that waits for its confirmation.
     *
     * @param value     the plain amount with the currency name
     * @param target    the recipient's name
     * @param onConfirm runs when the player confirms
     * @param onCancel  runs when the player cancels or closes the form
     */
    public record PaymentPrompt(@NotNull String value, @NotNull String target, @NotNull Runnable onConfirm,
                                @NotNull Runnable onCancel) {
    }

    /**
     * Asks a Bedrock player to confirm a payment: "You pay X to Y" with Confirm and Cancel.
     *
     * @param player the Bedrock player
     * @param prompt the payment and its actions
     */
    public void confirmPayment(@NotNull Player player, @NotNull PaymentPrompt prompt) {
        send(player, ModalForm.builder()
                .title(text(player, "pay.title"))
                .content(EconomyText.msg(EconomyText.ROOT + "bedrock.pay.content")
                        .with("value", prompt.value())
                        .with("target", prompt.target())
                        .plain(player))
                .button1(text(player, "pay.confirm"))
                .button2(text(player, "pay.cancel"))
                .validResultHandler(response -> onMain(player,
                        response.clickedButtonId() == 0 ? prompt.onConfirm() : prompt.onCancel()))
                .closedOrInvalidResultHandler(() -> onMain(player, prompt.onCancel()))
                .build());
    }

    @NotNull JExEconomy economy() {
        return economy;
    }

    @NotNull CurrencyBrowseForms browse() {
        return browse;
    }

    @NotNull CurrencyEditForms edit() {
        return edit;
    }

    @NotNull String text(@NotNull Player player, @NotNull String key) {
        return EconomyText.msg(EconomyText.ROOT + "bedrock." + key).plain(player);
    }

    void onMain(@NotNull Player player, @NotNull Runnable task) {
        economy.platform().scheduler().runAtEntity(player, task);
    }

    void send(@NotNull Player player, @NotNull Form form) {
        try {
            FloodgateApi.getInstance().sendForm(player.getUniqueId(), form);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, () -> "Bedrock form could not be sent to " + player.getName() + ": "
                    + e.getMessage());
        }
    }
}
