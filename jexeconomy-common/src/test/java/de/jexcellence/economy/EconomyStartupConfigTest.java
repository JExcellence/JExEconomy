package de.jexcellence.economy;

import de.jexcellence.economy.service.EconomyEvents;
import de.jexcellence.economy.service.currency.CurrencyConfig;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Defaults a server gets without touching its configuration, and events during the shutdown.
 */
class EconomyStartupConfigTest {

    @Test
    void theShippedH2PathFollowsTheDataFolder() {
        String url = EconomyDatabase.withDataFolder(
                "jdbc:h2:file:./plugins/JExEconomy/database/jexeconomy;MODE=MySQL", Path.of("custom", "JExEconomy"));

        assertFalse(url.contains("./plugins"), url);
        assertTrue(url.endsWith("custom/JExEconomy/database/jexeconomy;MODE=MySQL"), url);
    }

    @Test
    void anotherDatabaseUrlStaysAsItIs() {
        String url = "jdbc:mysql://localhost:3306/economy";

        assertEquals(url, EconomyDatabase.withDataFolder(url, Path.of("anywhere")));
    }

    @Test
    void aLiveFileWithoutVaultKeyKeepsCoins() {
        var config = CurrencyConfig.read(new YamlConfiguration(), warning -> { });

        assertEquals("coins", config.vaultCurrency());
    }

    @Test
    void theVaultCurrencyIsConfigurable() throws InvalidConfigurationException {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("vault:\n  currency: gold\n");

        assertEquals("gold", CurrencyConfig.read(yaml, warning -> { }).vaultCurrency());
    }

    @Test
    void postEventsAreDroppedOnceThePluginStops() {
        var events = new EconomyEvents(null);
        events.stopMainThreadDispatch();

        assertDoesNotThrow(() -> events.fire(new Event() {
            @Override
            public @NotNull HandlerList getHandlers() {
                return new HandlerList();
            }
        }));
    }
}
