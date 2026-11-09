package de.jexcellence.economy.migration;

import de.jexcellence.economy.vault.ForeignVault;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;

/**
 * Finds what balances can be imported from: other plugins' Vault economies, CSV files in
 * {@code plugins/JExEconomy/migrations/import} and the EssentialsX {@code userdata} folder.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public final class MigrationSources {

    private final JavaPlugin plugin;
    private final PlatformScheduler scheduler;
    private final Executor executor;

    /**
     * Creates the finder.
     *
     * @param plugin    this plugin
     * @param scheduler paces reading a Vault economy on the server thread
     * @param executor  reads files
     */
    public MigrationSources(@NotNull JavaPlugin plugin, @NotNull PlatformScheduler scheduler,
                            @NotNull Executor executor) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.executor = executor;
    }

    /**
     * Looks for sources.
     *
     * @return future resolving to the sources
     */
    public @NotNull CompletableFuture<List<MigrationSource>> detect() {
        return CompletableFuture.supplyAsync(this::scan, executor);
    }

    private @NotNull List<MigrationSource> scan() {
        Map<String, MigrationSource> found = new LinkedHashMap<>();
        addVault(Economy.class, found);
        ForeignVault.find(plugin, plugin.getServer().getPluginManager().getPlugins())
                .filter(ForeignVault.Found::separateApi)
                .ifPresent(vault -> addVault(vault.economyClass(), found));
        List<MigrationSource> sources = new ArrayList<>(found.values());
        sources.addAll(csvFiles());
        Path userdata = plugin.getDataFolder().toPath().resolveSibling("Essentials").resolve("userdata");
        if (Files.isDirectory(userdata)) {
            sources.add(new EssentialsMigrationSource(userdata, executor));
        }
        return sources;
    }

    private <T> void addVault(@NotNull Class<T> economy, @NotNull Map<String, MigrationSource> found) {
        for (RegisteredServiceProvider<T> registration
                : plugin.getServer().getServicesManager().getRegistrations(economy)) {
            if (registration.getPlugin() != plugin) {
                String owner = registration.getPlugin().getName();
                found.putIfAbsent(owner, new VaultMigrationSource(owner, readerOf(economy, registration.getProvider()),
                        Bukkit::getOfflinePlayers, task -> scheduler.runDelayed(task, 1L)));
            }
        }
    }

    /** The provider may implement a different copy of the Vault API, so the call goes through its own class. */
    private static <T> @NotNull ToDoubleFunction<OfflinePlayer> readerOf(@NotNull Class<T> economy,
                                                                        @NotNull T provider) {
        Method getBalance;
        try {
            getBalance = economy.getMethod("getBalance", OfflinePlayer.class);
        } catch (NoSuchMethodException incompatible) {
            throw new IllegalStateException("Unexpected Vault API", incompatible);
        }
        return player -> {
            try {
                return (double) getBalance.invoke(provider, player);
            } catch (IllegalAccessException | InvocationTargetException failure) {
                throw new IllegalStateException("Balance could not be read", failure);
            }
        };
    }

    private @NotNull List<MigrationSource> csvFiles() {
        Path folder = plugin.getDataFolder().toPath().resolve("migrations").resolve("import");
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(CsvMigrationSource::isCsv)
                    .map(file -> (MigrationSource) new CsvMigrationSource(file, executor))
                    .toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
