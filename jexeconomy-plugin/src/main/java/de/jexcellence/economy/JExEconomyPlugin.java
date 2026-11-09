package de.jexcellence.economy;

import de.jexcellence.dependency.JEDependency;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * Bukkit entry point. Provisions the runtime libraries first, then hands the lifecycle to {@link JExEconomy}.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public final class JExEconomyPlugin extends JavaPlugin {

    private JExEconomy economy;

    @Override
    public void onLoad() {
        try {
            JEDependency.initializeWithRemapping(this, JExEconomyPlugin.class);
            this.economy = new JExEconomy(this);
            this.economy.onLoad();
        } catch (Exception exception) {
            this.getLogger().log(Level.SEVERE, "[JExEconomy] Failed to load", exception);
            this.economy = null;
        }
    }

    @Override
    public void onEnable() {
        if (this.economy == null) {
            this.getLogger().severe("Cannot enable - JExEconomy failed during onLoad.");
            this.getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.economy.onEnable();
    }

    @Override
    public void onDisable() {
        if (this.economy == null) {
            return;
        }
        try {
            this.economy.onDisable();
        } catch (Exception exception) {
            this.getLogger().log(Level.SEVERE, "Error during JExEconomy shutdown", exception);
        }
    }
}
