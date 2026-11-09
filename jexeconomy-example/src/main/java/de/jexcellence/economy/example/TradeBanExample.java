package de.jexcellence.economy.example;

import de.jexcellence.economy.api.extension.EconomyExtension;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A complete JExEconomy extension: the players in {@code trade-banned} cannot send or receive payments, and the
 * leaderboard shows their state. Copy it as the start of your own.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public final class TradeBanExample extends JavaPlugin {

    private String text(String key) {
        return getConfig().getString("card." + key, key);
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Set<UUID> banned = getConfig().getStringList("trade-banned").stream()
                .map(UUID::fromString)
                .collect(Collectors.toUnmodifiableSet());
        var texts = new TradeBanExtension.Texts(text("title"), text("label"), text("allowed"), text("denied"));
        getServer().getServicesManager().register(EconomyExtension.class, new TradeBanExtension(banned, texts), this,
                ServicePriority.Normal);
    }
}
