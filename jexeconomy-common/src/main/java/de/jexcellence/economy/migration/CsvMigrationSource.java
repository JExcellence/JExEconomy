package de.jexcellence.economy.migration;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Reads balances from a {@code uuid,name,balance} file. The name may be empty and a header line is allowed.
 *
 * @author JExcellence
 * @since 3.2.0
 */
final class CsvMigrationSource implements MigrationSource {

    private static final String EXTENSION = ".csv";
    private static final int COLUMNS = 3;

    private final Path file;
    private final Executor executor;

    CsvMigrationSource(@NotNull Path file, @NotNull Executor executor) {
        this.file = file;
        this.executor = executor;
    }

    static boolean isCsv(@NotNull Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(EXTENSION);
    }

    @Override
    public @NotNull String id() {
        String name = file.getFileName().toString();
        return "csv:" + name.substring(0, name.length() - EXTENSION.length());
    }

    @Override
    public @NotNull CompletableFuture<SourceRead> read() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return parse(Files.readAllLines(file));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }, executor);
    }

    private static @NotNull SourceRead parse(@NotNull List<String> lines) {
        List<SourceBalance> balances = new ArrayList<>(lines.size());
        int unreadable = 0;
        for (String line : lines) {
            if (!line.isBlank() && !line.toLowerCase(Locale.ROOT).startsWith("uuid,")) {
                try {
                    balances.add(parseLine(line));
                } catch (IllegalArgumentException malformed) {
                    unreadable++;
                }
            }
        }
        return new SourceRead(balances, unreadable);
    }

    private static @NotNull SourceBalance parseLine(@NotNull String line) {
        String[] cells = line.split(",", -1);
        if (cells.length != COLUMNS) {
            throw new IllegalArgumentException("Expected uuid,name,balance");
        }
        String name = cells[1].trim();
        return new SourceBalance(UUID.fromString(cells[0].trim()), name.isEmpty() ? null : name,
                new BigDecimal(cells[2].trim()));
    }
}
