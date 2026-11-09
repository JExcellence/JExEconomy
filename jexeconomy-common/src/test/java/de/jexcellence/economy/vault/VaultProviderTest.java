package de.jexcellence.economy.vault;

import de.jexcellence.economy.api.TransactionResult;
import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.api.extension.DepositRedirect;
import de.jexcellence.economy.service.EconomyService;
import de.jexcellence.economy.service.WalletService;
import de.jexcellence.economy.service.ledger.BalanceLedger;
import de.jexcellence.jexplatform.logging.JExLogger;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Vault bridge answers what Vault plugins expect and never holds a thread longer than its limit.
 */
class VaultProviderTest {

    private static final double AMOUNT = 25.0;

    private final EconomyService economy = mock(EconomyService.class);
    private final OfflinePlayer player = mock(OfflinePlayer.class);
    private final Currency coins = new Currency("coins");
    private final AtomicBoolean serverThread = new AtomicBoolean();
    private final AtomicBoolean dispatchThread = new AtomicBoolean();
    private final AtomicBoolean parked = new AtomicBoolean();
    private final VaultProvider vault = new VaultProvider(() -> economy, JExLogger.of("VaultProviderTest"),
            new VaultCallGate(new VaultCallGate.Threads(serverThread::get, dispatchThread::get),
                    new VaultCallGate.Timing(Duration.ofMillis(100L), Duration.ofMillis(50L), Duration.ofMinutes(1L),
                            Duration.ofMinutes(1L)),
                    JExLogger.of("VaultCallGateTest")));

    @BeforeEach
    void wire() {
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(economy.vaultCurrency()).thenReturn(Optional.of(coins));
        when(economy.getBalance(player, coins)).thenReturn(CompletableFuture.completedFuture(40.0));
        BalanceLedger.Parking parking = () -> parked.set(false);
        when(economy.parkDispatchThread(player, coins)).thenAnswer(call -> {
            parked.set(true);
            return parking;
        });
    }

    @Test
    void beforeTheDatabaseIsReadyTheProviderIsDisabledAndRefusesBookings() {
        var starting = new VaultProvider(() -> null, JExLogger.of("VaultProviderTest"));

        assertFalse(starting.isEnabled());
        assertEquals(0.0, starting.getBalance(player));
        assertFalse(starting.hasAccount(player));
        assertEquals(ResponseType.FAILURE, starting.depositPlayer(player, AMOUNT).type);
        assertEquals(ResponseType.FAILURE, starting.withdrawPlayer(player, AMOUNT).type);
    }

    @Test
    void aCachedBalanceAnswersWithoutWaitingForTheDatabase() {
        when(economy.cachedBalance(player, coins)).thenReturn(OptionalDouble.of(12.5));
        when(economy.getBalance(player, coins)).thenReturn(new CompletableFuture<>());

        assertEquals(12.5, vault.getBalance(player));
        assertTrue(vault.has(player, 12.5));
        assertTrue(vault.hasAccount(player));
    }

    @Test
    void aMissingVaultCurrencyDisablesTheProviderWithoutFallback() {
        when(economy.vaultCurrency()).thenReturn(Optional.empty());

        assertFalse(vault.isEnabled());
        EconomyResponse response = vault.depositPlayer(player, AMOUNT);

        assertEquals(ResponseType.FAILURE, response.type);
        verify(economy, never()).depositForWaitingCaller(any(), any(), anyDouble(), anyBoolean());
    }

    @Test
    void aZeroAmountIsASuccessfulNoOp() {
        EconomyResponse response = vault.depositPlayer(player, 0.0);

        assertEquals(ResponseType.SUCCESS, response.type);
        assertEquals(0.0, response.amount);
        assertEquals(40.0, response.balance);
        verify(economy, never()).depositForWaitingCaller(any(), any(), anyDouble(), anyBoolean());
    }

    @Test
    void anAmountThatRoundsToNothingIsASuccessfulNoOp() {
        assertEquals(ResponseType.SUCCESS, vault.withdrawPlayer(player, 0.001).type);
        verify(economy, never()).withdrawForWaitingCaller(any(), any(), anyDouble(), anyBoolean());
    }

    @Test
    void negativeAndInvalidAmountsFailWithoutAReadOrAWrite() {
        assertEquals(ResponseType.FAILURE, vault.withdrawPlayer(player, -1.0).type);
        assertEquals(ResponseType.FAILURE, vault.depositPlayer(player, Double.NaN).type);

        verify(economy, never()).getBalance(player, coins);
    }

    @Test
    void aRefusedWithdrawalReportsNothingWasModified() {
        when(economy.withdrawForWaitingCaller(player, coins, AMOUNT, false)).thenReturn(CompletableFuture
                .completedFuture(TransactionResult.failure(AMOUNT, 3.0, "Insufficient funds")));

        EconomyResponse response = vault.withdrawPlayer(player, AMOUNT);

        assertEquals(ResponseType.FAILURE, response.type);
        assertEquals(0.0, response.amount);
        assertEquals(3.0, response.balance);
    }

    @Test
    void aSuccessReportsTheBookedAmount() {
        when(economy.withdrawForWaitingCaller(player, coins, AMOUNT, false)).thenReturn(CompletableFuture
                .completedFuture(TransactionResult.success(20.0, 5.0)));

        EconomyResponse response = vault.withdrawPlayer(player, AMOUNT);

        assertEquals(ResponseType.SUCCESS, response.type);
        assertEquals(20.0, response.amount);
    }

    @Test
    void aServerThreadSkipsThePreEventAndOnlyTheDispatchThreadParks() {
        serverThread.set(true);
        when(economy.withdrawForWaitingCaller(player, coins, AMOUNT, true)).thenReturn(CompletableFuture
                .completedFuture(TransactionResult.success(AMOUNT, 5.0)));

        vault.withdrawPlayer(player, AMOUNT);
        verify(economy).withdrawForWaitingCaller(player, coins, AMOUNT, true);
        verify(economy, never()).parkDispatchThread(player, coins);

        dispatchThread.set(true);
        vault.withdrawPlayer(player, AMOUNT);
        verify(economy).parkDispatchThread(player, coins);
        assertFalse(parked.get(), "the park ends with the call");
    }

    @Test
    void aWithdrawalThatOutlivesItsWaitFailsAndIsTakenBack() {
        var late = new CompletableFuture<TransactionResult>();
        when(economy.withdrawForWaitingCaller(player, coins, AMOUNT, false)).thenReturn(late);
        when(economy.creditWallet(eq(player), eq(coins), eq(AMOUNT), any()))
                .thenReturn(CompletableFuture.completedFuture(TransactionResult.success(AMOUNT, 40.0)));

        EconomyResponse response = vault.withdrawPlayer(player, AMOUNT);

        assertEquals(ResponseType.FAILURE, response.type);
        late.complete(TransactionResult.success(AMOUNT, 15.0));
        verify(economy, timeout(2_000L)).creditWallet(eq(player), eq(coins), eq(AMOUNT), any());
    }

    @Test
    void aLateFailedWithdrawalIsNotTakenBack() {
        var late = new CompletableFuture<TransactionResult>();
        when(economy.withdrawForWaitingCaller(player, coins, AMOUNT, false)).thenReturn(late);

        vault.withdrawPlayer(player, AMOUNT);

        late.complete(TransactionResult.failure(AMOUNT, 3.0, "Insufficient funds"));
        verify(economy, never()).creditWallet(any(), any(), anyDouble(), any());
    }

    @Test
    void aDepositThatOutlivesItsWaitIsReversedExactly() {
        var late = new CompletableFuture<WalletService.Receipt>();
        when(economy.depositForWaitingCaller(player, coins, AMOUNT, false)).thenReturn(late);
        when(economy.reverse(eq(player), eq("coins"), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(true));

        assertEquals(ResponseType.FAILURE, vault.depositPlayer(player, AMOUNT).type);

        late.complete(receipt());
        verify(economy, timeout(2_000L)).reverse(eq(player), eq("coins"), any(), any());
    }

    @Test
    void afterATimeoutServerThreadsAreRefusedWithoutTouchingTheDatabase() {
        serverThread.set(true);
        when(economy.withdrawForWaitingCaller(player, coins, AMOUNT, true)).thenReturn(new CompletableFuture<>());
        vault.withdrawPlayer(player, AMOUNT);

        assertEquals(VaultProvider.ERROR_BUSY, vault.depositPlayer(player, AMOUNT).errorMessage);
        assertEquals(0.0, vault.getBalance(player));
        verify(economy, never()).depositForWaitingCaller(any(), any(), anyDouble(), anyBoolean());
        verify(economy, never()).getBalance(player, coins);
    }

    @Test
    void otherThreadsKeepWaitingAfterATimeout() {
        when(economy.withdrawForWaitingCaller(player, coins, AMOUNT, false)).thenReturn(new CompletableFuture<>());
        vault.withdrawPlayer(player, AMOUNT);

        assertEquals(40.0, vault.getBalance(player));
    }

    @Test
    void aFailingBackendReadsAsEmpty() {
        when(economy.getBalance(player, coins)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException()));

        assertEquals(0.0, vault.getBalance(player));
        assertTrue(vault.isEnabled());
    }

    private static @NotNull WalletService.Receipt receipt() {
        return new WalletService.Receipt(TransactionResult.success(AMOUNT, 65.0), new BigDecimal("25.00"),
                DepositRedirect.Redirected.NONE);
    }
}
