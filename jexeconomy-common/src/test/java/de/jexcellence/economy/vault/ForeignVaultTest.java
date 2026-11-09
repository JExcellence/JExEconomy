package de.jexcellence.economy.vault;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A second copy of the Vault API, loaded the way a separate Vault jar is, reaches the provider through the adapter.
 */
class ForeignVaultTest {

    private static final double AMOUNT = 12.5;

    /** Hides the bundled Vault API, so the child loader defines its own copy. */
    private static final class WithoutVault extends ClassLoader {
        WithoutVault(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("net.milkbowl.")) {
                throw new ClassNotFoundException(name);
            }
            return super.loadClass(name, resolve);
        }
    }

    private final Economy provider = mock(Economy.class);
    private final OfflinePlayer player = mock(OfflinePlayer.class);
    private URLClassLoader foreignLoader;
    private Class<?> foreignEconomy;

    @BeforeEach
    void loadSecondCopy() throws Exception {
        URL vaultJar = Economy.class.getProtectionDomain().getCodeSource().getLocation();
        foreignLoader = new URLClassLoader(new URL[]{vaultJar}, new WithoutVault(getClass().getClassLoader()));
        foreignEconomy = Class.forName(Economy.class.getName(), false, foreignLoader);
    }

    @AfterEach
    void close() throws Exception {
        foreignLoader.close();
    }

    @Test
    void theSecondCopyIsADifferentClass() {
        assertNotSame(Economy.class, foreignEconomy);
    }

    @Test
    void aPlainCallReachesTheProvider() throws Exception {
        when(provider.getBalance(player)).thenReturn(AMOUNT);
        Object adapter = ForeignVault.adapt(foreignEconomy, provider);

        Method getBalance = foreignEconomy.getMethod("getBalance", OfflinePlayer.class);

        assertEquals(AMOUNT, getBalance.invoke(adapter, player));
    }

    @Test
    void aResponseComesBackInTheJarsOwnClass() throws Exception {
        when(provider.depositPlayer(player, AMOUNT))
                .thenReturn(new EconomyResponse(AMOUNT, 40.0, EconomyResponse.ResponseType.SUCCESS, null));
        Object adapter = ForeignVault.adapt(foreignEconomy, provider);

        Object response = foreignEconomy.getMethod("depositPlayer", OfflinePlayer.class, double.class)
                .invoke(adapter, player, AMOUNT);

        assertEquals(foreignLoader, response.getClass().getClassLoader());
        assertEquals(40.0, response.getClass().getField("balance").get(response));
        assertEquals("SUCCESS", String.valueOf(response.getClass().getField("type").get(response)));
    }

    @Test
    void theAdapterEqualsOnlyItself() {
        Object adapter = ForeignVault.adapt(foreignEconomy, provider);

        assertEquals(adapter, adapter);
        assertFalse(adapter.equals(ForeignVault.adapt(foreignEconomy, provider)));
    }

    @Test
    void noOtherVaultPluginMeansNothingIsFound() {
        Plugin self = namedPlugin("JExEconomy");

        assertEquals(Optional.empty(), ForeignVault.find(self, new Plugin[]{self, namedPlugin("LuckPerms")}));
    }

    @Test
    void aVaultJarIsFoundByName() {
        Plugin self = namedPlugin("JExEconomy");
        Plugin vault = namedPlugin("VaultUnlocked");

        var found = ForeignVault.find(self, new Plugin[]{self, vault}).orElseThrow();

        assertEquals("VaultUnlocked", found.pluginName());
        assertTrue(found.economyClass().getName().endsWith("Economy"));
    }

    private static Plugin namedPlugin(String name) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn(name);
        return plugin;
    }
}
