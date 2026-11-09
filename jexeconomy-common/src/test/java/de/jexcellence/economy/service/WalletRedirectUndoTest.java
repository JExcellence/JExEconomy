package de.jexcellence.economy.service;

import de.jexcellence.economy.api.ChangeType;
import de.jexcellence.economy.api.TransactionResult;
import de.jexcellence.economy.api.extension.DepositRedirect;
import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.api.extension.DepositRedirect.Redirected;
import de.jexcellence.economy.service.ledger.AccountKey;
import de.jexcellence.economy.service.ledger.BalanceLedger;
import de.jexcellence.economy.service.ledger.LedgerEntry;
import de.jexcellence.economy.service.ledger.LedgerHooks;
import de.jexcellence.economy.service.ledger.LedgerStore;
import de.jexcellence.jexplatform.logging.JExLogger;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A deposit split between the outside bank (the redirect) and the wallet never pays twice and never vanishes: when the
 * wallet part fails, the bank share is taken back from exactly the bank that got it; when that fails too, the
 * booked share is reported as paid so nothing is refunded twice; a reversal takes both parts back.
 */
class WalletRedirectUndoTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final long TARGET = 42L;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final MemoryStore store = new MemoryStore();
    private final BalanceLedger ledger = new BalanceLedger(store, LedgerHooks.NONE, executor);
    /** Every test deposit keeps a wallet part, so the account reads for a fully redirected deposit are never used. */
    private final WalletService wallet = new WalletService(ledger, null, JExLogger.of("WalletRedirectUndoTest"));
    private final OutsideBank bank = new OutsideBank();

    @AfterEach
    void stop() {
        executor.shutdownNow();
    }

    @Test
    void aFailedWalletPartTakesTheBankShareBack() {
        wallet.setRedirect(bank);
        store.failWrites.set(true);

        WalletService.Receipt receipt = wallet.depositWithReceipt(booking("100"), ChangeType.DEPOSIT).join();

        assertTrue(receipt.result().isFailed());
        assertFalse(receipt.redirected().taken());
        assertEquals(0L, bank.balance.get(), "the 30 paid into the bank were taken back");
        assertEquals(1, bank.undos.get());
    }

    @Test
    void aFailedUndoReportsTheBookedShareAsPaid() {
        bank.refuseUndo.set(true);
        wallet.setRedirect(bank);
        store.failWrites.set(true);

        WalletService.Receipt receipt = wallet.depositWithReceipt(booking("100"), ChangeType.DEPOSIT).join();

        assertTrue(receipt.result().isSuccess(), "reported as paid, so the caller does not refund it again");
        assertEquals(30.0, receipt.result().amount());
        assertEquals(30L, receipt.redirected().amount());
        assertEquals(30L, bank.balance.get());
    }

    @Test
    void aReversalTakesBothPartsBack() {
        wallet.setRedirect(bank);

        WalletService.Receipt receipt = wallet.depositWithReceipt(booking("100"), ChangeType.DEPOSIT).join();
        assertTrue(receipt.result().isSuccess());
        assertEquals(new BigDecimal("70.00"), store.balance());
        assertEquals(30L, bank.balance.get());

        assertTrue(wallet.reverse(booking("100"), receipt).join());
        assertEquals(new BigDecimal("0.00"), store.balance());
        assertEquals(0L, bank.balance.get());
    }

    @Test
    void aThrowingRedirectKeepsTheWholeDepositInTheWallet() {
        wallet.setRedirect((player, currency, amount, reason) -> {
            throw new IllegalStateException("outside bank offline");
        });

        TransactionResult result = wallet.deposit(booking("100"), ChangeType.DEPOSIT).join();

        assertTrue(result.isSuccess());
        assertEquals(new BigDecimal("100.00"), store.balance());
    }

    private static @NotNull WalletService.Booking booking(@NotNull String amount) {
        OfflinePlayer player = mock(OfflinePlayer.class);
        when(player.getUniqueId()).thenReturn(PLAYER);
        when(player.getName()).thenReturn("Steve");
        return new WalletService.Booking(player, new Currency("coins"), new BigDecimal(amount), null, "test");
    }

    /** An outside bank that takes 30 of every deposit and can refuse the undo. */
    private static final class OutsideBank implements DepositRedirect {

        private final AtomicInteger undos = new AtomicInteger();
        private final AtomicLong balance = new AtomicLong();
        private final AtomicBoolean refuseUndo = new AtomicBoolean();

        @Override
        public @NotNull Redirected redirect(@NotNull UUID player, @NotNull String currencyId, double amount,
                                            @NotNull String reason) {
            balance.addAndGet(30L);
            return new Redirected(30L, TARGET, true);
        }

        @Override
        public boolean undo(@NotNull UUID player, @NotNull String currencyId, @NotNull Redirected redirected) {
            undos.incrementAndGet();
            if (refuseUndo.get() || redirected.targetId() == null || redirected.targetId() != TARGET) {
                return false;
            }
            balance.addAndGet(-redirected.amount());
            return true;
        }
    }

    /** One in-memory account; writes can be switched to fail. */
    private static final class MemoryStore implements LedgerStore {

        private final Map<String, LedgerEntry> rows = new ConcurrentHashMap<>();
        private final AtomicBoolean failWrites = new AtomicBoolean();

        @NotNull BigDecimal balance() {
            return rows.values().stream().findFirst().map(LedgerEntry::balance).orElse(BigDecimal.ZERO);
        }

        @Override
        public @NotNull LedgerEntry load(@NotNull AccountKey key) {
            return rows.computeIfAbsent(key.queueKey(), ignored -> new LedgerEntry(1L, 0, new BigDecimal("0.00")));
        }

        @Override
        public void write(@NotNull LedgerEntry entry, @NotNull BigDecimal balance) {
            if (failWrites.get()) {
                throw new IllegalStateException("database down");
            }
            rows.replaceAll((key, row) -> row.accountId() == entry.accountId()
                    ? new LedgerEntry(row.accountId(), row.version() + 1, balance) : row);
        }

        @Override
        public void writeBoth(@NotNull LedgerEntry first, @NotNull BigDecimal firstBalance,
                              @NotNull LedgerEntry second, @NotNull BigDecimal secondBalance) {
            throw new UnsupportedOperationException("not used here");
        }
    }
}
