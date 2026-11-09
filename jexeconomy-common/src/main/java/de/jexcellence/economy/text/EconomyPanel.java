package de.jexcellence.economy.text;

import de.jexcellence.jexplatform.gui.chat.ChatPanel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The JExEconomy chat panel: header, optional context, {@code Label | value} rows, free lines and a footer.
 * Players get the suite {@link ChatPanel} (centred header, label column padded by pixel width); the console and
 * Bedrock forms get the same lines without padding.
 *
 * @author JExcellence
 * @since 4.1.0
 */
public final class EconomyPanel {

    private static final String KEY = EconomyText.ROOT + "panel.";
    private static final String PARAM_VALUE = "value";
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private enum Kind { CENTERED, GAP, ROW, LINE, FOOTER }

    private record Entry(@NotNull Kind kind, @NotNull Component first, @NotNull Component second) {
    }

    private final @Nullable Player viewer;
    private final List<Entry> entries = new ArrayList<>();

    private EconomyPanel(@Nullable Player viewer) {
        this.viewer = viewer;
    }

    /**
     * Starts a panel for a receiver.
     *
     * @param sender the receiver; a player gets the centred panel, anything else plain lines
     * @return the panel
     */
    public static @NotNull EconomyPanel of(@NotNull CommandSender sender) {
        return new EconomyPanel(EconomyText.viewerOf(sender));
    }

    /**
     * The viewer whose locale the panel uses.
     *
     * @return the viewer, {@code null} for the console
     */
    public @Nullable Player viewer() {
        return viewer;
    }

    /**
     * Adds the centred header: what this panel shows.
     *
     * @param header the translated header
     * @return this panel
     */
    public @NotNull EconomyPanel header(@NotNull Component header) {
        return add(Kind.CENTERED, header, Component.empty());
    }

    /**
     * Adds the centred scope line below the header.
     *
     * @param context the translated context
     * @return this panel
     */
    public @NotNull EconomyPanel context(@NotNull Component context) {
        return add(Kind.CENTERED, context, Component.empty());
    }

    /**
     * Adds one blank line between groups; leading, trailing and repeated gaps collapse.
     *
     * @return this panel
     */
    public @NotNull EconomyPanel gap() {
        return add(Kind.GAP, Component.empty(), Component.empty());
    }

    /**
     * Starts a titled group: one blank line and the muted section title.
     *
     * @param titleKey translation key of the section name
     * @return this panel
     */
    public @NotNull EconomyPanel section(@NotNull String titleKey) {
        gap();
        return line(EconomyText.msg(KEY + "section")
                .with("name", EconomyText.msg(titleKey).plain(viewer)).component(viewer));
    }

    /**
     * Adds a {@code Label | value} row with a translated label.
     *
     * @param labelKey translation key of the label
     * @param value    the rendered value
     * @return this panel
     */
    public @NotNull EconomyPanel row(@NotNull String labelKey, @NotNull Component value) {
        return rowText(EconomyText.msg(labelKey).plain(viewer), value);
    }

    /**
     * Adds a {@code Label | value} row with a plain neutral value.
     *
     * @param labelKey translation key of the label
     * @param value    the value as plain text
     * @return this panel
     */
    public @NotNull EconomyPanel row(@NotNull String labelKey, @NotNull String value) {
        return row(labelKey, value(value));
    }

    /**
     * Adds a {@code Label | value} row whose label is already a plain text, such as a rank and a name.
     *
     * @param label the label text
     * @param value the rendered value
     * @return this panel
     */
    public @NotNull EconomyPanel rowText(@NotNull String label, @NotNull Component value) {
        Component styled = EconomyText.msg(KEY + "label").with("label", label).component(viewer);
        return add(Kind.ROW, styled, value);
    }

    /**
     * Adds a {@code Label | value} row with a ready label component, such as a clickable command.
     *
     * @param label the rendered label
     * @param value the rendered value
     * @return this panel
     */
    public @NotNull EconomyPanel row(@NotNull Component label, @NotNull Component value) {
        return add(Kind.ROW, label, value);
    }

    /**
     * Adds a help row: the clickable command {@code <keyBase>.command} and its description {@code <keyBase>.text}.
     *
     * @param keyBase translation key without the {@code .command} / {@code .text} suffix
     * @param alias   the command label the player typed
     * @return this panel
     */
    public @NotNull EconomyPanel command(@NotNull String keyBase, @NotNull String alias) {
        Component command = EconomyText.msg(keyBase + ".command").with("alias", alias).component(viewer);
        Component text = EconomyText.msg(keyBase + ".text").component(viewer);
        return add(Kind.ROW, command, text);
    }

    /**
     * Adds a free left-aligned body line.
     *
     * @param line the line
     * @return this panel
     */
    public @NotNull EconomyPanel line(@NotNull Component line) {
        return add(Kind.LINE, line, Component.empty());
    }

    /**
     * Adds the action line, separated from the body by one blank line.
     *
     * @param footer the translated footer
     * @return this panel
     */
    public @NotNull EconomyPanel footer(@NotNull Component footer) {
        return add(Kind.FOOTER, footer, Component.empty());
    }

    /**
     * A neutral highlighted value.
     *
     * @param value the plain value
     * @return the styled value
     */
    public @NotNull Component value(@NotNull String value) {
        return EconomyText.msg(KEY + PARAM_VALUE).with(PARAM_VALUE, value).component(viewer);
    }

    /**
     * A value given as MiniMessage, e.g. a branded currency amount.
     *
     * @param valueMini the MiniMessage value
     * @return the parsed value
     */
    public @NotNull Component mini(@NotNull String valueMini) {
        return EconomyText.mini(viewer, valueMini);
    }

    /**
     * Sends the panel: centred for a player, plain lines for the console.
     *
     * @param sender the receiver
     */
    public void send(@NotNull CommandSender sender) {
        if (sender instanceof Player player) {
            chatPanel().send(EconomyText.audience(player));
        } else {
            plainLines().forEach(sender::sendMessage);
        }
    }

    /**
     * Renders the panel without padding, one empty line per gap. Used for the console and Bedrock forms.
     *
     * @return the lines
     */
    public @NotNull List<Component> plainLines() {
        Component separator = EconomyText.msg(KEY + "separator").component(viewer);
        List<Component> lines = new ArrayList<>();
        for (Entry entry : trimmed()) {
            lines.add(switch (entry.kind()) {
                case ROW -> entry.first().append(separator).append(entry.second());
                case GAP -> Component.empty();
                default -> entry.first();
            });
        }
        return lines;
    }

    /**
     * The panel as plain text with line breaks, for Bedrock form content.
     *
     * @return the text
     */
    public @NotNull String plainText() {
        StringBuilder text = new StringBuilder();
        for (Component line : plainLines()) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(PLAIN.serialize(line));
        }
        return text.toString();
    }

    private @NotNull ChatPanel chatPanel() {
        ChatPanel panel = ChatPanel.create();
        for (Entry entry : entries) {
            switch (entry.kind()) {
                case CENTERED -> panel.context(entry.first());
                case GAP -> panel.gap();
                case ROW -> panel.row(entry.first(), entry.second());
                case LINE -> panel.line(entry.first());
                case FOOTER -> panel.footer(entry.first());
                default -> throw new IllegalStateException("Unexpected panel entry: " + entry.kind());
            }
        }
        return panel;
    }

    private @NotNull List<Entry> trimmed() {
        List<Entry> out = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.kind() == Kind.GAP || entry.kind() == Kind.FOOTER) {
                appendGap(out);
            }
            if (entry.kind() != Kind.GAP) {
                out.add(entry);
            }
        }
        while (!out.isEmpty() && out.getLast().kind() == Kind.GAP) {
            out.removeLast();
        }
        return out;
    }

    private static void appendGap(@NotNull List<Entry> out) {
        if (!out.isEmpty() && out.getLast().kind() != Kind.GAP) {
            out.add(new Entry(Kind.GAP, Component.empty(), Component.empty()));
        }
    }

    private @NotNull EconomyPanel add(@NotNull Kind kind, @NotNull Component first, @NotNull Component second) {
        entries.add(new Entry(kind, first, second));
        return this;
    }
}
