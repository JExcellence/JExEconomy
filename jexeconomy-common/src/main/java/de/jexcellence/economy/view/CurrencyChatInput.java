package de.jexcellence.economy.view;

import de.jexcellence.economy.JExEconomy;
import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.text.EconomyText;
import de.jexcellence.jexplatform.server.ServerType;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chat input for currency fields, the fallback for servers where the anvil input cannot open (InventoryFramework's
 * anvil reflection resolves legacy Spigot NMS names that Mojang-mapped Paper does not have).
 *
 * <p>Clicking a field closes the form and arms a prompt; the player's next chat line is consumed (never
 * broadcast), applied to the draft, and the form reopens with it. {@code cancel} keeps the old value.
 *
 * <p>Paper and Folia deliver the line through {@link AsyncChatEvent}; Spigot has no such event, so
 * {@link AsyncPlayerChatEvent} is used there. Each listener is its own class so a server never loads the event
 * type it does not have.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public final class CurrencyChatInput {

    private static final String KEY = EconomyText.ROOT + "view.input.";
    private static final String CANCEL_WORD = "cancel";
    private static final String CANCEL_SHORT = "c";

    private final JExEconomy plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    private record Pending(@NotNull String field, @NotNull Currency draft, @NotNull String mode,
                           @Nullable String originalIdentifier) {
    }

    /**
     * Creates the input.
     *
     * @param plugin the plugin core
     */
    public CurrencyChatInput(@NotNull JExEconomy plugin) {
        this.plugin = plugin;
    }

    /**
     * Registers the chat listener that matches the running server.
     */
    public void register() {
        var owner = plugin.getPlugin();
        if (plugin.platform().serverType() instanceof ServerType.Spigot) {
            owner.getServer().getPluginManager().registerEvents(new SpigotChatListener(this), owner);
        } else {
            owner.getServer().getPluginManager().registerEvents(new PaperChatListener(this), owner);
        }
    }

    /**
     * Arms a chat prompt for one field of the draft and closes the GUI.
     *
     * @param player             the editing player
     * @param field              the field id
     * @param draft              the draft carrying the current edits
     * @param mode               {@link CurrencyFieldInputView#MODE_CREATE} or {@link CurrencyFieldInputView#MODE_EDIT}
     * @param originalIdentifier the identifier the save looks up (edit only), or {@code null}
     */
    public void prompt(@NotNull Player player, @NotNull String field, @NotNull Currency draft,
                       @NotNull String mode, @Nullable String originalIdentifier) {
        pending.put(player.getUniqueId(), new Pending(field, draft, mode, originalIdentifier));
        player.closeInventory();
        EconomyText.send(player, EconomyText.msg(KEY + "prompt")
                .with("field", CurrencyFieldInputView.fieldLabel(player, field)));
    }

    private boolean consume(@NotNull Player player, @NotNull String line) {
        Pending armed = pending.remove(player.getUniqueId());
        if (armed == null) {
            return false;
        }
        String input = line.trim();
        plugin.platform().scheduler().runAtEntity(player, () -> apply(player, armed, input));
        return true;
    }

    private void apply(@NotNull Player player, @NotNull Pending armed, @NotNull String input) {
        if (input.equalsIgnoreCase(CANCEL_WORD) || input.equalsIgnoreCase(CANCEL_SHORT)) {
            EconomyText.send(player, KEY + "cancelled");
            reopen(player, armed.draft(), armed);
            return;
        }
        if ("icon".equals(armed.field()) && !CurrencyFieldInputView.isValidMaterial(input)) {
            EconomyText.send(player, EconomyText.msg(KEY + "invalid-material").with("value", input));
            reopen(player, armed.draft(), armed);
            return;
        }
        if (CurrencyFieldInputView.rejectReservedIdentifier(player, armed.field(), input)) {
            reopen(player, armed.draft(), armed);
            return;
        }
        reopen(player, CurrencyFieldInputView.applyField(armed.draft(), armed.field(), input), armed);
    }

    private void reopen(@NotNull Player player, @NotNull Currency draft, @NotNull Pending armed) {
        Map<String, Object> data = new HashMap<>();
        data.put(CurrencyFormView.PLUGIN, plugin);
        data.put(CurrencyFormView.DRAFT, draft);
        if (CurrencyFieldInputView.MODE_EDIT.equals(armed.mode())) {
            data.put(CurrencyFormView.ORIGINAL,
                    armed.originalIdentifier() != null ? armed.originalIdentifier() : draft.getIdentifier());
            plugin.viewFrame().open(CurrencyEditView.class, player, data);
        } else {
            plugin.viewFrame().open(CurrencyCreateView.class, player, data);
        }
    }

    private static final class PaperChatListener implements Listener {

        private final CurrencyChatInput input;

        private PaperChatListener(@NotNull CurrencyChatInput input) {
            this.input = input;
        }

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onChat(@NotNull AsyncChatEvent event) {
            String line = PlainTextComponentSerializer.plainText().serialize(event.message());
            if (input.consume(event.getPlayer(), line)) {
                event.setCancelled(true);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static final class SpigotChatListener implements Listener {

        private final CurrencyChatInput input;

        private SpigotChatListener(@NotNull CurrencyChatInput input) {
            this.input = input;
        }

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onChat(@NotNull AsyncPlayerChatEvent event) {
            if (input.consume(event.getPlayer(), event.getMessage())) {
                event.setCancelled(true);
            }
        }
    }
}
