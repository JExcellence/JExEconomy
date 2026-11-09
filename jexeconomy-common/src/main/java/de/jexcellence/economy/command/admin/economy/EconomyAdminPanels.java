package de.jexcellence.economy.command.admin.economy;

import de.jexcellence.economy.permission.EconomyPermission;
import de.jexcellence.economy.text.EconomyPanel;
import de.jexcellence.economy.text.EconomyText;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chat panels of {@code /economy}: the permission-filtered command list and the migration screens.
 *
 * @author JExcellence
 * @since 4.1.0
 */
final class EconomyAdminPanels {

    private static final String HELP = EconomyText.ROOT + "help.economy.";
    private static final String HEADER = "header";
    private static final Map<String, EconomyPermission> COMMANDS = orderedCommands();

    @NotNull EconomyPanel help(@NotNull CommandSender sender, @NotNull String alias) {
        var panel = EconomyPanel.of(sender)
                .header(EconomyText.component(sender, HELP + HEADER))
                .context(EconomyText.component(sender, HELP + "context"))
                .gap();
        COMMANDS.forEach((command, permission) -> {
            if (holds(sender, permission)) {
                panel.command(HELP + command, alias);
            }
        });
        return panel;
    }

    private static boolean holds(@NotNull CommandSender sender, @NotNull EconomyPermission permission) {
        return !(sender instanceof Player player) || player.isOp() || permission.has(player);
    }

    private static @NotNull Map<String, EconomyPermission> orderedCommands() {
        var commands = new LinkedHashMap<String, EconomyPermission>();
        commands.put("give", EconomyPermission.ADMIN_GIVE);
        commands.put("take", EconomyPermission.ADMIN_TAKE);
        commands.put("set", EconomyPermission.ADMIN_SET);
        commands.put("reset", EconomyPermission.ADMIN_RESET_PLAYER);
        commands.put("giveall", EconomyPermission.ADMIN_GIVEALL);
        commands.put("migrate-steps", EconomyPermission.ADMIN_MIGRATE);
        commands.put("reload", EconomyPermission.ADMIN_RELOAD);
        return Collections.unmodifiableMap(commands);
    }
}
