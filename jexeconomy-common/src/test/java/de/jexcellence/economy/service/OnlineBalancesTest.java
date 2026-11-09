package de.jexcellence.economy.service;

import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.jexplatform.logging.JExLogger;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The cache answers from memory, follows bookings at once and never lets an older database read replace them.
 */
class OnlineBalancesTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String COINS = "coins";

    private final Deque<CompletableFuture<Double>> loads = new ArrayDeque<>();
    private final AtomicLong now = new AtomicLong(1_000L);
    private final OnlineBalances balances = new OnlineBalances((player, currency) -> {
        var load = new CompletableFuture<Double>();
        loads.add(load);
        return load;
    }, JExLogger.of("OnlineBalancesTest"), now::get);
    private final OfflinePlayer player = mock(OfflinePlayer.class);
    private final Currency coins = new Currency(COINS);

    @BeforeEach
    void wire() {
        when(player.getUniqueId()).thenReturn(PLAYER);
    }

    private void trackedWith(double balance) {
        balances.track(player, coins);
        loads.remove().complete(balance);
    }

    @Test
    void anUntrackedPlayerHasNoCachedBalance() {
        assertEquals(OptionalDouble.empty(), balances.balance(PLAYER, COINS));
    }

    @Test
    void aBalanceIsAnsweredOnceItIsLoaded() {
        balances.track(player, coins);
        assertEquals(OptionalDouble.empty(), balances.balance(PLAYER, COINS));

        loads.remove().complete(40.0);

        assertEquals(OptionalDouble.of(40.0), balances.balance(PLAYER, COINS));
    }

    @Test
    void anotherCurrencyIsNotAnsweredFromTheEntry() {
        trackedWith(40.0);

        assertEquals(OptionalDouble.empty(), balances.balance(PLAYER, "crystals"));
    }

    @Test
    void aBookingUpdatesTheBalanceAtOnce() {
        trackedWith(40.0);

        balances.committed(PLAYER, COINS, new BigDecimal("15.50"));

        assertEquals(OptionalDouble.of(15.5), balances.balance(PLAYER, COINS));
    }

    @Test
    void aLoadThatStartedBeforeABookingCannotReplaceIt() {
        balances.track(player, coins);
        balances.committed(PLAYER, COINS, new BigDecimal("15.50"));

        loads.remove().complete(40.0);

        assertEquals(OptionalDouble.of(15.5), balances.balance(PLAYER, COINS));
    }

    @Test
    void anOldEntryIsRefreshedInTheBackgroundAndStillAnswers() {
        trackedWith(40.0);
        now.addAndGet(OnlineBalances.REFRESH_AFTER_MS + 1L);

        assertEquals(OptionalDouble.of(40.0), balances.balance(PLAYER, COINS));
        assertEquals(1, loads.size());
        balances.balance(PLAYER, COINS);
        assertEquals(1, loads.size());

        loads.remove().complete(55.0);

        assertEquals(OptionalDouble.of(55.0), balances.balance(PLAYER, COINS));
    }

    @Test
    void aFailedRefreshKeepsTheLastBalance() {
        trackedWith(40.0);
        now.addAndGet(OnlineBalances.REFRESH_AFTER_MS + 1L);
        balances.balance(PLAYER, COINS);

        loads.remove().completeExceptionally(new IllegalStateException("database down"));

        assertEquals(OptionalDouble.of(40.0), balances.balance(PLAYER, COINS));
    }

    @Test
    void aPlayerWhoLeftIsNoLongerCached() {
        trackedWith(40.0);

        balances.forget(PLAYER);
        balances.committed(PLAYER, COINS, new BigDecimal("1.00"));

        assertEquals(OptionalDouble.empty(), balances.balance(PLAYER, COINS));
    }
}
