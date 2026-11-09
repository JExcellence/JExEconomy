package de.jexcellence.economy.service;

import de.jexcellence.economy.api.ChangeType;
import de.jexcellence.economy.api.CurrencyPolicy;
import de.jexcellence.economy.api.TransactionResult;
import de.jexcellence.economy.api.extension.DepositRedirect;
import de.jexcellence.economy.api.extension.TransferRoute;
import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.database.entity.TransactionLog;
import de.jexcellence.economy.money.Money;
import de.jexcellence.economy.api.extension.DepositRedirect.Redirected;
import de.jexcellence.economy.service.currency.CurrencyRegistry;
import de.jexcellence.economy.service.ledger.AccountKey;
import de.jexcellence.economy.service.ledger.BalanceLedger;
import de.jexcellence.economy.service.ledger.LedgerEntry;
import de.jexcellence.economy.service.ledger.LedgerHooks;
import de.jexcellence.economy.service.ledger.LedgerStore;
import de.jexcellence.jexplatform.logging.JExLogger;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The part of a transfer's wallet deposit that a redirect moved to an external balance is written to the transaction log,
 * because the ledger only records the part that stayed in the wallet.
 */
class TransferRedirectLogTest {

    private static final UUID SENDER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID RECIPIENT = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final long TARGET = 42L;
    private static final long JOURNAL_ENTRY = 7L;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final MemoryStore store = new MemoryStore();
    private final JExLogger logger = JExLogger.of("TransferRedirectLogTest");
    private final WalletService wallets = new WalletService(new BalanceLedger(store, LedgerHooks.NONE, executor), null,
            logger);
    private final TransactionLogger transactionLogger = mock(TransactionLogger.class);
    private final CurrencyRegistry registry = mock(CurrencyRegistry.class);
    private final Currency coins = new Currency("coins");
    private final Player sender = mock(Player.class);
    private final OfflinePlayer recipient = mock(OfflinePlayer.class);
    private final TransferJournal journal = mock(TransferJournal.class);
    private TransferService transfers;

    @BeforeEach
    void wire() {
        when(registry.policy("coins")).thenReturn(CurrencyPolicy.DEFAULT);
        when(journal.open(any())).thenReturn(CompletableFuture.completedFuture(JOURNAL_ENTRY));
        when(sender.getUniqueId()).thenReturn(SENDER);
        when(sender.getName()).thenReturn("Steve");
        when(recipient.getUniqueId()).thenReturn(RECIPIENT);
        when(recipient.getName()).thenReturn("Alex");
        wallets.setRedirect(new DepositRedirect() {
            @Override
            public @NotNull Redirected redirect(@NotNull UUID player, @NotNull String currencyId, double amount,
                                                @NotNull String reason) {
                return new Redirected(30L, TARGET, true);
            }
        });
        transfers = new TransferService(wallets, registry, transactionLogger, logger, executor, journal);
        transfers.setRoute(new TransferRoute() {
            @Override
            public @NotNull Leg withdraw(@NotNull UUID from, @NotNull UUID to, @NotNull String currencyId,
                                         double amount) {
                return Leg.wallet();
            }

            @Override
            public @NotNull Leg deposit(@NotNull UUID to, @NotNull UUID from, @NotNull String currencyId,
                                        double amount) {
                return Leg.wallet();
            }

            @Override
            public boolean refund(@NotNull Leg withdrawn, @NotNull UUID to, double amount) {
                return false;
            }
        });
    }

    @AfterEach
    void stop() {
        executor.shutdownNow();
    }

    @Test
    void aRedirectedShareOfATransferIsLogged() {
        store.seed(SENDER, "100.00");

        TransactionResult result = transfers.transfer(sender, recipient, coins, 50.0).join();

        assertTrue(result.isSuccess());
        ArgumentCaptor<TransactionLog> logged = ArgumentCaptor.forClass(TransactionLog.class);
        verify(transactionLogger).log(logged.capture());
        TransactionLog entry = logged.getValue();
        assertEquals(ChangeType.TRANSFER_IN, entry.getChangeType());
        assertEquals(RECIPIENT, entry.getPlayerUuid());
        assertEquals(new BigDecimal("30.00"), entry.getAmount());
        assertTrue(String.valueOf(entry.getReason()).contains("external balance #42"), entry.getReason());
    }

    @Test
    void aRefusedTransferLogsNoShare() {
        store.seed(SENDER, "1.00");

        assertFalse(transfers.transfer(sender, recipient, coins, 50.0).join().isSuccess());

        verify(transactionLogger, never()).log(any());
    }

    @Test
    void aFinishedLegByLegPaymentClosesItsJournalEntry() {
        store.seed(SENDER, "100.00");

        assertTrue(transfers.transfer(sender, recipient, coins, 50.0).join().isSuccess());

        verify(journal).close(JOURNAL_ENTRY);
    }

    @Test
    void aPaymentThatCannotBeJournaledDoesNotMoveMoney() {
        store.seed(SENDER, "100.00");
        when(journal.open(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("database down")));

        assertFalse(transfers.transfer(sender, recipient, coins, 50.0).join().isSuccess());

        assertEquals(0, new BigDecimal("100.00").compareTo(store.balance(SENDER)));
        verify(journal, never()).close(anyLong());
    }

    private static final class MemoryStore implements LedgerStore {

        private final Map<UUID, LedgerEntry> rows = new ConcurrentHashMap<>();
        private final AtomicLong ids = new AtomicLong();

        @NotNull BigDecimal balance(@NotNull UUID player) {
            return rows.get(player).balance();
        }

        void seed(@NotNull UUID player, @NotNull String balance) {
            rows.put(player, new LedgerEntry(ids.incrementAndGet(), 0, Money.of(new BigDecimal(balance))));
        }

        @Override
        public @NotNull LedgerEntry load(@NotNull AccountKey key) {
            return rows.computeIfAbsent(key.playerId(),
                    ignored -> new LedgerEntry(ids.incrementAndGet(), 0, Money.ZERO));
        }

        @Override
        public void write(@NotNull LedgerEntry entry, @NotNull BigDecimal balance) {
            rows.replaceAll((player, row) -> row.accountId() == entry.accountId()
                    ? new LedgerEntry(row.accountId(), row.version() + 1, balance) : row);
        }

        @Override
        public void writeBoth(@NotNull LedgerEntry first, @NotNull BigDecimal firstBalance,
                              @NotNull LedgerEntry second, @NotNull BigDecimal secondBalance) {
            throw new UnsupportedOperationException("not used here");
        }
    }
}
