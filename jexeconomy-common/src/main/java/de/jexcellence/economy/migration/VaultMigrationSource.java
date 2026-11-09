package de.jexcellence.economy.migration;

import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

/**
 * Reads another plugin's Vault economy for every player the server knows. Such plugins expect the server thread, so
 * the players are read a few milliseconds per tick.
 *
 * @author JExcellence
 * @since 3.2.0
 */
final class VaultMigrationSource implements MigrationSource {

    private static final long TICK_BUDGET_NANOS = 5_000_000L;

    private final String pluginName;
    private final ToDoubleFunction<OfflinePlayer> reader;
    private final Supplier<OfflinePlayer[]> players;
    private final Consumer<Runnable> nextTick;

    private static final class Cursor {
        private final CompletableFuture<SourceRead> result = new CompletableFuture<>();
        private final List<SourceBalance> balances = new ArrayList<>();
        private OfflinePlayer[] players;
        private int next;
        private int unreadable;
    }

    VaultMigrationSource(@NotNull String pluginName, @NotNull ToDoubleFunction<OfflinePlayer> reader,
                         @NotNull Supplier<OfflinePlayer[]> players, @NotNull Consumer<Runnable> nextTick) {
        this.pluginName = pluginName;
        this.reader = reader;
        this.players = players;
        this.nextTick = nextTick;
    }

    @Override
    public @NotNull String id() {
        return "vault:" + pluginName.toLowerCase(Locale.ROOT);
    }

    @Override
    public @NotNull CompletableFuture<SourceRead> read() {
        var cursor = new Cursor();
        nextTick.accept(() -> readTick(cursor));
        return cursor.result;
    }

    private void readTick(@NotNull Cursor cursor) {
        try {
            if (cursor.players == null) {
                cursor.players = players.get();
            }
            long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
            while (cursor.next < cursor.players.length && System.nanoTime() < deadline) {
                readOne(cursor, cursor.players[cursor.next]);
                cursor.next++;
            }
            if (cursor.next < cursor.players.length) {
                nextTick.accept(() -> readTick(cursor));
            } else {
                cursor.result.complete(new SourceRead(cursor.balances, cursor.unreadable));
            }
        } catch (RuntimeException failure) {
            cursor.result.completeExceptionally(failure);
        }
    }

    private void readOne(@NotNull Cursor cursor, @NotNull OfflinePlayer player) {
        try {
            double balance = reader.applyAsDouble(player);
            cursor.balances.add(new SourceBalance(player.getUniqueId(), player.getName(), BigDecimal.valueOf(balance)));
        } catch (RuntimeException unreadable) {
            cursor.unreadable++;
        }
    }
}
