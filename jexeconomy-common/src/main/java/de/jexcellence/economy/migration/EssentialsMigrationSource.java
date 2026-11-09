package de.jexcellence.economy.migration;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

/**
 * Reads the EssentialsX {@code userdata} folder directly, so it also works on a server that no longer runs Essentials.
 * Each file is named after the player's UUID and holds the balance under {@code money}.
 *
 * @author JExcellence
 * @since 3.2.0
 */
final class EssentialsMigrationSource implements MigrationSource {

    private static final String EXTENSION = ".yml";

    private final Path folder;
    private final Executor executor;

    EssentialsMigrationSource(@NotNull Path folder, @NotNull Executor executor) {
        this.folder = folder;
        this.executor = executor;
    }

    @Override
    public @NotNull String id() {
        return "essentials";
    }

    @Override
    public @NotNull CompletableFuture<SourceRead> read() {
        return CompletableFuture.supplyAsync(this::readFolder, executor);
    }

    private @NotNull SourceRead readFolder() {
        List<SourceBalance> balances = new ArrayList<>();
        int unreadable = 0;
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(EXTENSION)).toList()) {
                try {
                    SourceBalance balance = readFile(file);
                    if (balance != null) {
                        balances.add(balance);
                    }
                } catch (IOException | InvalidConfigurationException | IllegalArgumentException malformed) {
                    unreadable++;
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return new SourceRead(balances, unreadable);
    }

    /** A file without a balance belongs to a player who never held money, and is not an error. */
    private static @Nullable SourceBalance readFile(@NotNull Path file)
            throws IOException, InvalidConfigurationException {
        var data = new YamlConfiguration();
        data.load(file.toFile());
        String money = data.getString("money");
        if (money == null) {
            return null;
        }
        String fileName = file.getFileName().toString();
        UUID player = UUID.fromString(fileName.substring(0, fileName.length() - EXTENSION.length()));
        String name = data.getString("last-account-name", data.getString("lastAccountName"));
        return new SourceBalance(player, name, new BigDecimal(money));
    }
}
