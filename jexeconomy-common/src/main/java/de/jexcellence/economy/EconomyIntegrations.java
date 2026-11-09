package de.jexcellence.economy;

import de.jexcellence.economy.api.EconomyProvider;
import de.jexcellence.economy.coupon.CouponItemListener;
import de.jexcellence.economy.coupon.CouponService;
import de.jexcellence.economy.database.repository.ItemReturnRepository;
import de.jexcellence.economy.extension.ExtensionHost;
import de.jexcellence.economy.item.CurrencyItemListener;
import de.jexcellence.economy.item.ItemReturns;
import de.jexcellence.economy.placeholder.EconomyExpansion;
import de.jexcellence.economy.placeholder.PlaceholderBalanceCache;
import de.jexcellence.economy.service.EconomyService;
import de.jexcellence.economy.vault.ForeignVault;
import de.jexcellence.economy.vault.VaultProvider;
import de.jexcellence.jexplatform.JExPlatform;
import de.jexcellence.jexplatform.logging.JExLogger;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Connects the running economy to the server and other plugins: Bukkit services, listeners, Vault and PlaceholderAPI.
 * All registrations run on the main thread.
 *
 * @author JExcellence
 * @since 3.2.0
 */
final class EconomyIntegrations {

    private final JavaPlugin plugin;
    private final JExPlatform platform;
    private final JExLogger logger;
    private final ExtensionHost extensions;
    private EconomyService registeredService;
    private VaultProvider vaultProvider;
    private Class<?> foreignEconomy;
    private Object foreignProvider;

    EconomyIntegrations(@NotNull JavaPlugin plugin, @NotNull JExPlatform platform, @NotNull JExLogger logger,
                        @NotNull ExtensionHost extensions) {
        this.plugin = plugin;
        this.platform = platform;
        this.logger = logger;
        this.extensions = extensions;
    }

    void followExtensions() {
        plugin.getServer().getPluginManager().registerEvents(extensions, plugin);
        extensions.refresh();
    }

    void registerServices(@NotNull EconomyService economyService) {
        registeredService = economyService;
        Bukkit.getServicesManager().register(EconomyProvider.class, economyService, plugin, ServicePriority.Normal);
        Bukkit.getServicesManager().register(EconomyService.class, economyService, plugin, ServicePriority.Normal);
    }

    void registerListeners(@NotNull EconomyService economyService, @NotNull CouponService couponService,
                           @NotNull ItemReturnRepository itemReturns) {
        var pluginManager = plugin.getServer().getPluginManager();
        var returns = new ItemReturns(itemReturns, platform.scheduler(), logger);
        pluginManager.registerEvents(
                new CurrencyItemListener(plugin, economyService, extensions, platform.scheduler(), returns), plugin);
        pluginManager.registerEvents(
                new CouponItemListener(plugin, couponService, extensions, platform.scheduler()), plugin);
    }

    /**
     * Registers the Vault provider while the server enables its plugins, so a Vault plugin finds it in its own
     * {@code onEnable}. The Vault API is shaded into the plugin jar ({@code provides: [Vault]}); the class-load guard
     * covers a misconfigured shading.
     *
     * @param economyService the economy service, {@code null} until the database is ready
     */
    void registerVault(@NotNull Supplier<@Nullable EconomyService> economyService) {
        try {
            vaultProvider = new VaultProvider(economyService, logger);
            vaultProvider.register(plugin);
            ForeignVault.find(plugin, plugin.getServer().getPluginManager().getPlugins())
                    .ifPresentOrElse(this::registerBesides,
                            () -> logger.info("Registered as Vault economy provider (bundled API)"));
        } catch (NoClassDefFoundError e) {
            logger.warn("Vault API not on classpath - provider registration skipped", e);
        }
    }

    private void registerBesides(@NotNull ForeignVault.Found found) {
        logger.info("{} found next to JExEconomy. Both work together, and {} can be removed unless another plugin "
                + "needs it: JExEconomy includes the Vault API.", found.pluginName(), found.pluginName());
        if (found.separateApi()) {
            registerForeign(found);
        }
    }

    /**
     * Registers under the jar's own {@code Economy} class as well, because a Vault plugin may resolve either copy.
     */
    private void registerForeign(@NotNull ForeignVault.Found found) {
        try {
            Object adapter = ForeignVault.adapt(found.economyClass(), vaultProvider);
            registerHighest(found.economyClass(), adapter);
            foreignEconomy = found.economyClass();
            foreignProvider = adapter;
        } catch (RuntimeException failed) {
            logger.error("Could not register with {}, so some Vault plugins will not see JExEconomy. Remove that "
                    + "jar to fix this", found.pluginName(), failed);
        }
    }

    private <T> void registerHighest(@NotNull Class<T> service, @NotNull Object provider) {
        Bukkit.getServicesManager().register(service, service.cast(provider), plugin, ServicePriority.Highest);
    }

    /**
     * Registers the PlaceholderAPI expansion when PlaceholderAPI is enabled; its balances come from a cache that
     * refreshes asynchronously.
     *
     * @param economyService the economy service
     */
    void registerPlaceholders(@NotNull EconomyService economyService) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return;
        }
        var balances = new PlaceholderBalanceCache(economyService);
        plugin.getServer().getPluginManager().registerEvents(balances, plugin);
        boolean registered = new EconomyExpansion(economyService, balances, pluginVersion()).register();
        logger.info("PlaceholderAPI expansion {}", registered ? "registered" : "could not be registered");
    }

    @SuppressWarnings("deprecation")
    private @NotNull String pluginVersion() {
        return plugin.getDescription().getVersion();
    }

    void unregister() {
        if (foreignProvider != null) {
            Bukkit.getServicesManager().unregister(foreignEconomy, foreignProvider);
            foreignProvider = null;
        }
        if (vaultProvider != null) {
            Bukkit.getServicesManager().unregister(Economy.class, vaultProvider);
            vaultProvider = null;
        }
        if (registeredService != null) {
            Bukkit.getServicesManager().unregister(EconomyProvider.class, registeredService);
            Bukkit.getServicesManager().unregister(EconomyService.class, registeredService);
            registeredService = null;
        }
    }
}
