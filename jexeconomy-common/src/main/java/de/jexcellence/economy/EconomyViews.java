package de.jexcellence.economy;

import de.jexcellence.economy.view.CurrencyCreateView;
import de.jexcellence.economy.view.CurrencyDangerView;
import de.jexcellence.economy.view.CurrencyDetailView;
import de.jexcellence.economy.view.CurrencyEditView;
import de.jexcellence.economy.view.CurrencyFieldInputView;
import de.jexcellence.economy.view.CurrencyOverviewView;
import de.jexcellence.economy.view.LeaderboardView;
import me.devnatan.inventoryframework.AnvilInputFeature;
import me.devnatan.inventoryframework.ViewFrame;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;

/**
 * Registers the InventoryFramework views of JExEconomy. The anvil field input needs InventoryFramework's NMS
 * reflection, which resolves legacy Spigot names that Mojang-mapped Paper lacks; without it the anvil view is
 * skipped and field edits use the chat prompt.
 *
 * @author JExcellence
 * @since 4.1.0
 */
final class EconomyViews {

    private static final String LEGACY_NMS_PLAYER = "net.minecraft.server.level.EntityPlayer";

    private EconomyViews() {
    }

    static boolean anvilSupported() {
        try {
            Class.forName(LEGACY_NMS_PLAYER);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    @SuppressWarnings("UnstableApiUsage")
    static @NotNull ViewFrame register(@NotNull JavaPlugin plugin, boolean anvil) {
        ViewFrame frame = ViewFrame.create(plugin);
        if (anvil) {
            frame.install(AnvilInputFeature.AnvilInput).with(new CurrencyFieldInputView());
        }
        frame.with(
                new CurrencyOverviewView(),
                new CurrencyDetailView(),
                new CurrencyCreateView(),
                new CurrencyEditView(),
                new CurrencyDangerView(),
                new LeaderboardView());
        frame.defaultConfig(config -> {
            config.cancelOnClick();
            config.cancelOnDrag();
            config.cancelOnDrop();
            config.cancelOnPickup();
            config.interactionDelay(Duration.ofMillis(100));
        });
        frame.disableMetrics();
        return frame.register();
    }
}
