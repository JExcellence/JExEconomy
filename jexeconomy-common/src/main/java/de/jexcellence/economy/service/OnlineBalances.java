package de.jexcellence.economy.service;

import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.money.Money;
import de.jexcellence.jexplatform.logging.JExLogger;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

/**
 * The Vault currency balance of online players, so Vault reads on a server thread need no database round trip. Each
 * booked change updates the entry at once. A read also refreshes an entry older than {@value #REFRESH_AFTER_MS} ms in
 * the background, which picks up changes made by another server on the same database. Bookings always check the real
 * balance, so a slightly old read can never overspend.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public final class OnlineBalances {

    static final long REFRESH_AFTER_MS = 5_000L;

    private record Target(@NotNull OfflinePlayer player, @NotNull Currency currency) {

        boolean holds(@NotNull String currencyId) {
            return currency.getIdentifier().equals(currencyId);
        }
    }

    /** {@code stamp} is unique per state, so a load that started before a booking cannot overwrite it. */
    private record Entry(@NotNull Target target, @NotNull OptionalDouble balance, long stamp, long loadedAt,
                         boolean refreshing) {
    }

    private final BiFunction<OfflinePlayer, Currency, CompletableFuture<Double>> loader;
    private final JExLogger logger;
    private final LongSupplier clock;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong stamps = new AtomicLong();

    /**
     * Creates the cache.
     *
     * @param loader reads a balance from the database
     * @param logger the plugin logger
     */
    public OnlineBalances(@NotNull BiFunction<OfflinePlayer, Currency, CompletableFuture<Double>> loader,
                          @NotNull JExLogger logger) {
        this(loader, logger, System::currentTimeMillis);
    }

    OnlineBalances(@NotNull BiFunction<OfflinePlayer, Currency, CompletableFuture<Double>> loader,
                   @NotNull JExLogger logger, @NotNull LongSupplier clock) {
        this.loader = loader;
        this.logger = logger;
        this.clock = clock;
    }

    /**
     * Starts caching a player who joined.
     *
     * @param player   the player
     * @param currency the Vault currency
     */
    public void track(@NotNull OfflinePlayer player, @NotNull Currency currency) {
        var entry = new Entry(new Target(player, currency), OptionalDouble.empty(), stamps.incrementAndGet(),
                clock.getAsLong(), true);
        entries.put(player.getUniqueId(), entry);
        load(player.getUniqueId(), entry);
    }

    /**
     * Stops caching a player who left.
     *
     * @param player the player
     */
    public void forget(@NotNull UUID player) {
        entries.remove(player);
    }

    /**
     * The cached balance.
     *
     * @param player     the player
     * @param currencyId the currency
     * @return the balance, empty when it is not cached yet
     */
    public @NotNull OptionalDouble balance(@NotNull UUID player, @NotNull String currencyId) {
        Entry entry = entries.get(player);
        if (entry == null || !entry.target().holds(currencyId)) {
            return OptionalDouble.empty();
        }
        refreshIfOld(player, entry);
        return entry.balance();
    }

    /**
     * Takes over a booked change.
     *
     * @param player     the player
     * @param currencyId the currency
     * @param balance    the balance after the booking
     */
    public void committed(@NotNull UUID player, @NotNull String currencyId, @NotNull BigDecimal balance) {
        entries.computeIfPresent(player, (id, entry) -> entry.target().holds(currencyId)
                ? new Entry(entry.target(), OptionalDouble.of(Money.toDouble(balance)), stamps.incrementAndGet(),
                        clock.getAsLong(), false)
                : entry);
    }

    private void refreshIfOld(@NotNull UUID player, @NotNull Entry seen) {
        if (seen.refreshing() || clock.getAsLong() - seen.loadedAt() <= REFRESH_AFTER_MS) {
            return;
        }
        var refreshing = new Entry(seen.target(), seen.balance(), stamps.incrementAndGet(), seen.loadedAt(), true);
        if (entries.replace(player, seen, refreshing)) {
            load(player, refreshing);
        }
    }

    private void load(@NotNull UUID player, @NotNull Entry started) {
        try {
            loader.apply(started.target().player(), started.target().currency())
                    .whenComplete((balance, failure) -> {
                        if (failure != null) {
                            reportFailedLoad(player, failure);
                        }
                        finish(player, started.stamp(), balance);
                    });
        } catch (RuntimeException rejected) {
            reportFailedLoad(player, rejected);
            finish(player, started.stamp(), null);
        }
    }

    private void reportFailedLoad(@NotNull UUID player, @NotNull Throwable failure) {
        logger.debug("Vault balance of {} could not be loaded, the last value stays: {}", player,
                String.valueOf(failure.getMessage()));
    }

    private void finish(@NotNull UUID player, long stamp, @Nullable Double balance) {
        entries.computeIfPresent(player, (id, current) -> {
            if (current.stamp() != stamp) {
                return current;
            }
            OptionalDouble value = balance != null ? OptionalDouble.of(balance) : current.balance();
            return new Entry(current.target(), value, stamps.incrementAndGet(), clock.getAsLong(), false);
        });
    }
}
