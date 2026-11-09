package de.jexcellence.economy.extension;

import de.jexcellence.economy.api.extension.DepositRedirect;
import de.jexcellence.economy.api.extension.EconomyExtension;
import de.jexcellence.economy.api.extension.TransferRoute;
import de.jexcellence.jexplatform.logging.JExLogger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Follows the {@link EconomyExtension} in the services manager, so the economy always talks to the current one.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public final class ExtensionHost implements Listener {

    private final ServicesManager services;
    private final JExLogger logger;
    private volatile EconomyExtension active = EconomyExtension.NONE;

    /**
     * Creates the host.
     *
     * @param services the services manager
     * @param logger   the plugin logger
     */
    public ExtensionHost(@NotNull ServicesManager services, @NotNull JExLogger logger) {
        this.services = services;
        this.logger = logger;
    }

    /**
     * The extension in use.
     *
     * @return the extension, {@link EconomyExtension#NONE} when there is none
     */
    public @NotNull EconomyExtension current() {
        return active;
    }

    /** Looks up the extension to use. */
    public void refresh() {
        RegisteredServiceProvider<EconomyExtension> registration = services.getRegistration(EconomyExtension.class);
        EconomyExtension found = registration != null ? registration.getProvider() : EconomyExtension.NONE;
        if (found != active) {
            active = found;
            logger.info("Economy extension: {}", registration != null ? registration.getPlugin().getName() : "none");
        }
    }

    /**
     * Follows a new registration.
     *
     * @param event the event
     */
    @EventHandler
    public void onServiceRegister(@NotNull ServiceRegisterEvent event) {
        if (event.getProvider().getService() == EconomyExtension.class) {
            refresh();
        }
    }

    /**
     * Follows a removed registration.
     *
     * @param event the event
     */
    @EventHandler
    public void onServiceUnregister(@NotNull ServiceUnregisterEvent event) {
        if (event.getProvider().getService() == EconomyExtension.class) {
            refresh();
        }
    }

    /**
     * A redirect that always forwards to the current extension.
     *
     * @return the redirect
     */
    public @NotNull DepositRedirect depositRedirect() {
        return new DepositRedirect() {
            @Override
            public @NotNull Redirected redirect(@NotNull UUID player, @NotNull String currencyId, double amount,
                                                @NotNull String reason) {
                return active.depositRedirect().redirect(player, currencyId, amount, reason);
            }

            @Override
            public boolean undo(@NotNull UUID player, @NotNull String currencyId, @NotNull Redirected redirected) {
                return active.depositRedirect().undo(player, currencyId, redirected);
            }
        };
    }

    /**
     * A route that always forwards to the current extension.
     *
     * @return the route
     */
    public @NotNull TransferRoute transferRoute() {
        return new TransferRoute() {
            @Override
            public boolean walletOnly(@NotNull String currencyId) {
                return active.transferRoute().walletOnly(currencyId);
            }

            @Override
            public @NotNull String holderName() {
                return active.transferRoute().holderName();
            }

            @Override
            public @NotNull Leg withdraw(@NotNull UUID sender, @NotNull UUID recipient, @NotNull String currencyId,
                                         double amount) {
                return active.transferRoute().withdraw(sender, recipient, currencyId, amount);
            }

            @Override
            public @NotNull Leg deposit(@NotNull UUID recipient, @NotNull UUID sender, @NotNull String currencyId,
                                        double amount) {
                return active.transferRoute().deposit(recipient, sender, currencyId, amount);
            }

            @Override
            public boolean refund(@NotNull Leg withdrawn, @NotNull UUID recipient, double amount) {
                return active.transferRoute().refund(withdrawn, recipient, amount);
            }
        };
    }
}
