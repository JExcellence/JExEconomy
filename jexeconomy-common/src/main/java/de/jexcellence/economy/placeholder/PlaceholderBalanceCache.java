package de.jexcellence.economy.placeholder;

import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.service.EconomyService;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Display balances for PlaceholderAPI without blocking the requesting thread (often the main thread for
 * scoreboards). A request answers from the cache and starts an asynchronous refresh when the value is older than
 * {@link #REFRESH_AFTER_MS}; the first request of a player and currency answers empty until the refresh arrived.
 * Entries of a player are dropped when the player quits. Above {@value #SWEEP_SIZE} entries, at most once per
 * {@value #SWEEP_INTERVAL_MS} ms, every entry nobody asked for within {@value #EVICT_AFTER_MS} ms is evicted, loaded
 * or not; if the cache is still above twice that size, entries idle for more than {@link #REFRESH_AFTER_MS} go as
 * well, so the cache stays bounded and a request never scans the map on every call.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public final class PlaceholderBalanceCache implements Listener {

    /** Age after which a request triggers a refresh. */
    public static final long REFRESH_AFTER_MS = 3_000L;

    /** Idle time after which an entry is evicted by a sweep. */
    public static final long EVICT_AFTER_MS = 600_000L;
    /** Entry count above which sweeps run. */
    public static final int SWEEP_SIZE = 2_048;
    /** Shortest time between two sweeps. */
    public static final long SWEEP_INTERVAL_MS = 10_000L;

    private record Key(@NotNull UUID player, @NotNull String currencyId) {
    }

    private static final class Entry {
        private volatile double balance;
        private volatile long loadedAt;
        private volatile boolean loaded;
        private volatile boolean refreshing;
        private volatile long touchedAt;
    }

    private final EconomyService economy;
    private final LongSupplier clock;
    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong lastSweep = new AtomicLong(Long.MIN_VALUE / 2);

    /**
     * Creates the cache.
     *
     * @param economy the economy service
     */
    public PlaceholderBalanceCache(@NotNull EconomyService economy) {
        this(economy, System::currentTimeMillis);
    }

    PlaceholderBalanceCache(@NotNull EconomyService economy, @NotNull LongSupplier clock) {
        this.economy = economy;
        this.clock = clock;
    }

    /**
     * The cached display balance; schedules a refresh when it is missing or old.
     *
     * @param player   the player
     * @param currency the currency
     * @return the balance, empty until the first load finished
     */
    public @NotNull OptionalDouble balance(@NotNull OfflinePlayer player, @NotNull Currency currency) {
        var key = new Key(player.getUniqueId(), currency.getIdentifier());
        var entry = entries.computeIfAbsent(key, ignored -> new Entry());
        long now = clock.getAsLong();
        entry.touchedAt = now;
        if (!entry.refreshing && (!entry.loaded || now - entry.loadedAt > REFRESH_AFTER_MS)) {
            refresh(player, currency, entry);
        }
        sweepIfLarge(now);
        return entry.loaded ? OptionalDouble.of(entry.balance) : OptionalDouble.empty();
    }

    private void sweepIfLarge(long now) {
        long previous = lastSweep.get();
        if (entries.size() <= SWEEP_SIZE || now - previous < SWEEP_INTERVAL_MS
                || !lastSweep.compareAndSet(previous, now)) {
            return;
        }
        entries.values().removeIf(entry -> now - entry.touchedAt > EVICT_AFTER_MS);
        if (entries.size() > 2 * SWEEP_SIZE) {
            entries.values().removeIf(entry -> now - entry.touchedAt > REFRESH_AFTER_MS);
        }
    }

    /**
     * Number of cached entries.
     *
     * @return the entry count
     */
    int size() {
        return entries.size();
    }

    private void refresh(@NotNull OfflinePlayer player, @NotNull Currency currency, @NotNull Entry entry) {
        entry.refreshing = true;
        economy.getDisplayBalance(player, currency).whenComplete((balance, failure) -> {
            if (failure == null && balance != null) {
                entry.balance = balance;
                entry.loaded = true;
            }
            entry.loadedAt = clock.getAsLong();
            entry.refreshing = false;
        });
    }

    /**
     * Drops the entries of a player who left.
     *
     * @param event the quit event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        entries.keySet().removeIf(key -> key.player().equals(player));
    }
}
