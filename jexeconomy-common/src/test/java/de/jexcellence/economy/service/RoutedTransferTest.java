package de.jexcellence.economy.service;

import de.jexcellence.economy.api.TransactionResult;
import de.jexcellence.economy.api.extension.TransferRoute;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the {@code /pay} transfer legs: with an external balance the coins leave the sender's bank
 * (not the empty JExEconomy wallet, the bug behind "insufficient funds" while {@code /balance} showed the bank) and
 * arrive in the recipient's bank; a failed deposit gives the coins back to the bank they came from, and a sender
 * cannot run two transfers at once.
 */
class RoutedTransferTest {

    private static final Executor DIRECT = Runnable::run;
    private static final UUID SENDER = UUID.randomUUID();
    private static final UUID RECIPIENT = UUID.randomUUID();
    private static final RoutedTransfer.Request PAY_500 =
            new RoutedTransfer.Request(SENDER, RECIPIENT, "coins", 500.0);

    @Test
    void bankLegsMoveCoinsWithoutTouchingTheWallet() {
        FakeRoute route = new FakeRoute(bankLeg(TransferRoute.Outcome.DONE, 1_500.0),
                bankLeg(TransferRoute.Outcome.DONE, 800.0));
        CountingWallet wallet = new CountingWallet(true);

        RoutedTransfer.Receipt receipt = run(route, wallet);

        assertTrue(receipt.result().isSuccess());
        assertEquals(1_500.0, receipt.result().balance(), "sender sees the bank balance after the payment");
        assertEquals(0, wallet.calls.size(), "no wallet leg runs when both banks book the payment");
        assertFalse(receipt.lost());
    }

    @Test
    void recipientBankFullRefundsTheSenderBank() {
        FakeRoute route = new FakeRoute(bankLeg(TransferRoute.Outcome.DONE, 1_500.0),
                bankLeg(TransferRoute.Outcome.BANK_FULL, 9_999.0));

        RoutedTransfer.Receipt receipt = run(route, new CountingWallet(true));

        assertTrue(receipt.result().isFailed());
        assertEquals(RoutedTransfer.ERROR_RECIPIENT_FULL, receipt.result().error());
        assertEquals(500.0, route.refunded, "the withdrawn coins go back into the sender's bank");
        assertEquals(2_000.0, receipt.result().balance());
        assertFalse(receipt.lost());
    }

    @Test
    void throwingDepositStillRefunds() {
        FakeRoute route = new FakeRoute(bankLeg(TransferRoute.Outcome.DONE, 100.0), null);

        RoutedTransfer.Receipt receipt = run(route, new CountingWallet(true));

        assertTrue(receipt.result().isFailed());
        assertEquals(500.0, route.refunded);
    }

    @Test
    void refusedBankWithdrawalsNeverDeposit() {
        for (TransferRoute.Outcome refusal : List.of(TransferRoute.Outcome.INSUFFICIENT, TransferRoute.Outcome.DENIED,
                TransferRoute.Outcome.WHOLE_COINS_ONLY)) {
            FakeRoute route = new FakeRoute(bankLeg(refusal, 200.0), bankLeg(TransferRoute.Outcome.DONE, 0.0));
            CountingWallet wallet = new CountingWallet(true);

            RoutedTransfer.Receipt receipt = run(route, wallet);

            assertTrue(receipt.result().isFailed(), refusal.name());
            assertEquals(0, route.deposits, refusal + " must not reach the recipient");
            assertEquals(0, wallet.calls.size());
        }
        assertEquals(RoutedTransfer.ERROR_BANK_DENIED, run(new FakeRoute(bankLeg(TransferRoute.Outcome.DENIED, 0.0),
                null), new CountingWallet(true)).result().error());
        assertEquals(RoutedTransfer.ERROR_INSUFFICIENT, run(new FakeRoute(bankLeg(TransferRoute.Outcome.INSUFFICIENT,
                0.0), null), new CountingWallet(true)).result().error());
    }

    @Test
    void walletRouteKeepsTheWalletLegs() {
        CountingWallet wallet = new CountingWallet(false);

        RoutedTransfer.Receipt receipt = run(TransferRoute.WALLET, wallet);

        assertTrue(receipt.result().isFailed(), "the recipient deposit failed");
        assertEquals(List.of("withdraw", "deposit", "refund"), wallet.calls);
    }

    @Test
    void secondTransferOfTheSameSenderIsRefusedWhileTheFirstRuns() {
        CompletableFuture<TransactionResult> pendingWithdraw = new CompletableFuture<>();
        RoutedTransfer transfers = new RoutedTransfer(() -> TransferRoute.WALLET, DIRECT);
        RoutedTransfer.WalletLegs slow = new RoutedTransfer.WalletLegs(() -> pendingWithdraw,
                () -> CompletableFuture.completedFuture(TransactionResult.success(500.0, 0.0)),
                () -> CompletableFuture.completedFuture(TransactionResult.success(500.0, 0.0)));

        CompletableFuture<RoutedTransfer.Receipt> first = transfers.execute(PAY_500, slow);
        RoutedTransfer.Receipt second = transfers.execute(PAY_500, slow).join();

        assertEquals(RoutedTransfer.ERROR_IN_FLIGHT, second.result().error());
        pendingWithdraw.complete(TransactionResult.success(500.0, 100.0));
        assertTrue(first.join().result().isSuccess());
        assertFalse(transfers.isInFlight(SENDER), "the guard is released after the transfer");
    }

    private static RoutedTransfer.Receipt run(@NotNull TransferRoute route, @NotNull CountingWallet wallet) {
        return new RoutedTransfer(() -> route, DIRECT).execute(PAY_500, wallet.legs()).join();
    }

    private static TransferRoute.Leg bankLeg(@NotNull TransferRoute.Outcome outcome, double balance) {
        return new TransferRoute.Leg(outcome, 7L, balance);
    }

    private static final class FakeRoute implements TransferRoute {
        private final Leg withdrawal;
        private final Leg deposit;
        private int deposits;
        private double refunded;

        FakeRoute(@NotNull Leg withdrawal, Leg deposit) {
            this.withdrawal = withdrawal;
            this.deposit = deposit;
        }

        @Override
        public @NotNull Leg withdraw(@NotNull UUID sender, @NotNull UUID recipient, @NotNull String currencyId,
                                     double amount) {
            return withdrawal;
        }

        @Override
        public @NotNull Leg deposit(@NotNull UUID recipient, @NotNull UUID sender, @NotNull String currencyId,
                                    double amount) {
            deposits++;
            if (deposit == null) {
                throw new IllegalStateException("bank unavailable");
            }
            return deposit;
        }

        @Override
        public boolean refund(@NotNull Leg withdrawn, @NotNull UUID recipient, double amount) {
            refunded += amount;
            return true;
        }
    }

    private static final class CountingWallet {
        private final List<String> calls = new ArrayList<>();
        private final boolean depositSucceeds;

        CountingWallet(boolean depositSucceeds) {
            this.depositSucceeds = depositSucceeds;
        }

        RoutedTransfer.WalletLegs legs() {
            return new RoutedTransfer.WalletLegs(
                    () -> record("withdraw", TransactionResult.success(500.0, 1_000.0)),
                    () -> record("deposit", depositSucceeds
                            ? TransactionResult.success(500.0, 500.0)
                            : TransactionResult.failure(500.0, 0.0, "no account")),
                    () -> record("refund", TransactionResult.success(500.0, 1_500.0)));
        }

        private CompletableFuture<TransactionResult> record(@NotNull String leg, @NotNull TransactionResult result) {
            calls.add(leg);
            return CompletableFuture.completedFuture(result);
        }
    }
}
