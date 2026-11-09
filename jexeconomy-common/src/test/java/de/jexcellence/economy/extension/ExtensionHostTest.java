package de.jexcellence.economy.extension;

import de.jexcellence.economy.api.extension.DepositRedirect;
import de.jexcellence.economy.api.extension.EconomyExtension;
import de.jexcellence.economy.api.extension.TransferRoute;
import de.jexcellence.jexplatform.logging.JExLogger;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Without an extension JExEconomy behaves plainly, and it follows extensions as they come and go.
 */
class ExtensionHostTest {

    private static final UUID PLAYER = new UUID(1L, 1L);
    private static final String COINS = "coins";

    private final ServicesManager services = mock(ServicesManager.class);
    private final Plugin owner = mock(Plugin.class);
    private final ExtensionHost host = new ExtensionHost(services, JExLogger.of("ExtensionHostTest"));

    private final EconomyExtension extension = new EconomyExtension() {
        @Override
        public boolean isTradeBlocked(@NotNull UUID first, @NotNull UUID second) {
            return true;
        }

        @Override
        public @NotNull DepositRedirect depositRedirect() {
            return (player, currencyId, amount, reason) -> new DepositRedirect.Redirected(7L, 1L, true);
        }

        @Override
        public @NotNull TransferRoute transferRoute() {
            return new RouteNamed("vault room");
        }
    };

    private record RouteNamed(String name) implements TransferRoute {
        @Override
        public @NotNull String holderName() {
            return name;
        }

        @Override
        public @NotNull Leg withdraw(@NotNull UUID sender, @NotNull UUID recipient, @NotNull String currencyId,
                                     double amount) {
            return Leg.wallet();
        }

        @Override
        public @NotNull Leg deposit(@NotNull UUID recipient, @NotNull UUID sender, @NotNull String currencyId,
                                    double amount) {
            return Leg.wallet();
        }

        @Override
        public boolean refund(@NotNull Leg withdrawn, @NotNull UUID recipient, double amount) {
            return false;
        }
    }

    private RegisteredServiceProvider<EconomyExtension> registration() {
        return new RegisteredServiceProvider<>(EconomyExtension.class, extension, ServicePriority.Normal, owner);
    }

    private static ServiceRegisterEvent registerEvent(RegisteredServiceProvider<?> provider) {
        ServiceRegisterEvent event = mock(ServiceRegisterEvent.class);
        doReturn(provider).when(event).getProvider();
        return event;
    }

    private void registered() {
        when(services.getRegistration(EconomyExtension.class)).thenReturn(registration());
        host.onServiceRegister(registerEvent(registration()));
    }

    @Test
    void withoutAnExtensionEverythingStaysInTheWallets() {
        host.refresh();

        assertSame(EconomyExtension.NONE, host.current());
        assertFalse(host.current().isTradeBlocked(PLAYER, PLAYER));
        assertTrue(host.transferRoute().walletOnly(COINS));
        assertFalse(host.depositRedirect().redirect(PLAYER, COINS, 5.0, "test").taken());
    }

    @Test
    void aRegisteredExtensionAppliesAtOnce() {
        registered();

        assertSame(extension, host.current());
        assertEquals(7L, host.depositRedirect().redirect(PLAYER, COINS, 5.0, "test").amount());
        assertEquals("vault room", host.transferRoute().holderName());
    }

    @Test
    void aRemovedExtensionEnds() {
        registered();
        when(services.getRegistration(EconomyExtension.class)).thenReturn(null);

        ServiceUnregisterEvent event = mock(ServiceUnregisterEvent.class);
        doReturn(registration()).when(event).getProvider();
        host.onServiceUnregister(event);

        assertSame(EconomyExtension.NONE, host.current());
        assertTrue(host.transferRoute().walletOnly(COINS));
    }

    @Test
    void otherServicesAreIgnored() {
        when(services.getRegistration(EconomyExtension.class)).thenReturn(registration());
        var other = new RegisteredServiceProvider<>(String.class, "other", ServicePriority.Normal, owner);

        host.onServiceRegister(registerEvent(other));

        assertSame(EconomyExtension.NONE, host.current());
    }
}
