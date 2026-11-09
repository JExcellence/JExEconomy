package de.jexcellence.economy.listener;

import de.jexcellence.economy.JExEconomy;
import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.service.EconomyService;
import de.jexcellence.jexplatform.logging.JExLogger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Creates the {@link de.jexcellence.economy.database.entity.EconomyPlayer} row and one account per currency before
 * a player joins, and stores a changed player name. Leaderboards, {@code /balance <player>} and offline payments
 * read these rows, so a player shows up with a zero balance before the first transaction.
 *
 * <p>Registered by the JExCommand listener scan ({@code listener} package, constructor taking {@link JExEconomy}).
 * The accounts are created one after another on the economy executor, so the player row is inserted once; the
 * pre-login thread waits at most 5 seconds and never blocks the login on a slow
 * database. Logins another plugin refused create nothing. The Vault balance of a player is kept in memory from the
 * join to the quit.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public final class PlayerJoinListener implements Listener {

    private static final long ACCOUNT_WAIT_SECONDS = 5L;

    private final EconomyService economyService;
    private final JExLogger logger;

    /**
     * Creates the listener.
     *
     * @param economy the JExEconomy core
     */
    public PlayerJoinListener(@NotNull JExEconomy economy) {
        this.economyService = economy.economyService();
        this.logger = economy.logger();
    }

    /**
     * Ensures the player row and an account for every currency exist.
     *
     * @param event the async pre-login event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAsyncPreLogin(@NotNull AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        Collection<Currency> currencies = economyService.getAllCurrencies().values();
        if (currencies.isEmpty()) {
            return;
        }
        UUID uuid = event.getUniqueId();
        String name = event.getName();
        try {
            ensureAccounts(uuid, name, currencies).get(ACCOUNT_WAIT_SECONDS, TimeUnit.SECONDS);
            logger.debug("Ensured accounts for {} across {} currencies", name, currencies.size());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            logger.warn("Account check for {} interrupted", name);
        } catch (ExecutionException | TimeoutException failure) {
            logger.warn("Accounts for {} could not be checked before the join, they are created on first use: {}",
                    name, String.valueOf(failure.getMessage()));
        }
    }

    /**
     * Starts caching the player's Vault balance.
     *
     * @param event the join event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        economyService.trackOnlineBalance(event.getPlayer());
    }

    /**
     * Stops caching the player's Vault balance.
     *
     * @param event the quit event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        economyService.forgetOnlineBalance(event.getPlayer().getUniqueId());
    }

    private @NotNull CompletableFuture<?> ensureAccounts(@NotNull UUID uuid, @NotNull String name,
                                                        @NotNull Collection<Currency> currencies) {
        CompletableFuture<?> chain = CompletableFuture.completedFuture(null);
        for (Currency currency : currencies) {
            chain = chain.thenCompose(previous -> economyService.getOrCreateAccount(uuid, name, currency));
        }
        return chain;
    }
}
