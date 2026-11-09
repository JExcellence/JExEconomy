package de.jexcellence.economy.text;

import de.jexcellence.jextranslate.MessageBuilder;
import de.jexcellence.jextranslate.R18nManager;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Single-line chat feedback of JExEconomy: the versioned {@code economy-v2.prefix} followed by one translated
 * line. Multi-line output goes through {@link EconomyPanel} instead.
 *
 * @author JExcellence
 * @since 4.1.0
 */
public final class EconomyText {

    /** Translation root of every second-generation economy text. */
    public static final String ROOT = "economy-v2.";

    private static final String PREFIX = ROOT + "prefix";
    private static final String UNKNOWN_NAME = ROOT + "common.unknown-player";

    private EconomyText() {
    }

    /**
     * Starts a translated message.
     *
     * @param key the full translation key
     * @return the builder
     */
    public static @NotNull MessageBuilder msg(@NotNull String key) {
        return R18nManager.getInstance().msg(key);
    }

    /**
     * Sends {@code prefix + message} as one line.
     *
     * @param sender  the receiver, player or console
     * @param message the filled message builder
     */
    public static void send(@NotNull CommandSender sender, @NotNull MessageBuilder message) {
        Player viewer = viewerOf(sender);
        deliver(sender, msg(PREFIX).component(viewer).append(message.component(viewer)));
    }

    /**
     * Sends {@code prefix + line} for a line that is already a component, e.g. one with buttons.
     *
     * @param sender the receiver, player or console
     * @param line   the line after the prefix
     */
    public static void sendLine(@NotNull CommandSender sender, @NotNull Component line) {
        deliver(sender, msg(PREFIX).component(viewerOf(sender)).append(line));
    }

    /**
     * Sends a component through the Adventure path of the running server: native on Paper and Folia, bridged
     * through adventure-platform-bukkit on Spigot.
     *
     * @param sender    the receiver
     * @param component the component to send
     */
    public static void deliver(@NotNull CommandSender sender, @NotNull Component component) {
        R18nManager.getInstance().getMessageSender().sendMessage(sender, component);
    }

    /**
     * The Adventure audience of a player. A Bukkit player is an audience natively on Paper and Folia only; on
     * Spigot the adventure-platform-bukkit bridge supplies it.
     *
     * @param player the player
     * @return the audience that delivers components to the player
     */
    public static @NotNull Audience audience(@NotNull Player player) {
        var sender = R18nManager.getInstance().getMessageSender();
        BukkitAudiences bridge = sender.getAudiences();
        if (sender.getVersionDetector().hasNativeAdventure() || bridge == null) {
            return player;
        }
        return bridge.player(player);
    }

    /**
     * Sends {@code prefix + message} for a key without placeholders.
     *
     * @param sender the receiver
     * @param key    the full translation key
     */
    public static void send(@NotNull CommandSender sender, @NotNull String key) {
        send(sender, msg(key));
    }

    /**
     * Renders a key for a receiver.
     *
     * @param sender the receiver, its locale is used when it is a player
     * @param key    the full translation key
     * @return the component
     */
    public static @NotNull Component component(@NotNull CommandSender sender, @NotNull String key) {
        return msg(key).component(viewerOf(sender));
    }

    /**
     * Parses a MiniMessage value, e.g. a branded currency amount, as a component.
     *
     * @param viewer    the viewer, {@code null} for the default locale
     * @param valueMini the MiniMessage value
     * @return the parsed component
     */
    public static @NotNull Component mini(@Nullable Player viewer, @NotNull String valueMini) {
        return msg(ROOT + "panel.raw").with("value", valueMini).component(viewer);
    }

    /**
     * The player behind a sender, {@code null} for the console.
     *
     * @param sender the sender
     * @return the player or {@code null}
     */
    public static @Nullable Player viewerOf(@NotNull CommandSender sender) {
        return sender instanceof Player player ? player : null;
    }

    /**
     * The display name of a player, a translated placeholder when the server never saw the name.
     *
     * @param sender the receiver, for the locale
     * @param player the player
     * @return the name
     */
    public static @NotNull String nameOf(@NotNull CommandSender sender, @NotNull OfflinePlayer player) {
        String name = player.getName();
        return name != null ? name : msg(UNKNOWN_NAME).plain(viewerOf(sender));
    }
}
