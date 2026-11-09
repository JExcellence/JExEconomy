package de.jexcellence.economy.database.maintenance;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the delete order of a currency: transaction logs and accounts reference the currency, so they must be
 * gone before the currency row, and large tables are deleted in batches.
 */
class CurrencyPurgeTest {

    private static final long CURRENCY = 7L;

    @Test
    void dependentsGoBeforeTheCurrency() {
        var store = new RecordingStore(3, 2);

        var result = CurrencyPurge.run(store, CURRENCY, 500);

        assertEquals(List.of("logs:3", "accounts:2", "currency:7"), store.calls);
        assertEquals(new CurrencyPurge.Result(2, 3), result);
    }

    @Test
    void largeTablesAreDeletedInBatches() {
        var store = new RecordingStore(5, 4);

        var result = CurrencyPurge.run(store, CURRENCY, 2);

        assertEquals(List.of("logs:2", "logs:2", "logs:1", "accounts:2", "accounts:2", "currency:7"), store.calls);
        assertEquals(new CurrencyPurge.Result(4, 5), result);
    }

    @Test
    void currencyWithoutDependentsIsDeletedAlone() {
        var store = new RecordingStore(0, 0);

        CurrencyPurge.run(store, CURRENCY, 500);

        assertEquals(List.of("currency:7"), store.calls);
    }

    /** Rows held in memory; every delete is recorded in call order. */
    private static final class RecordingStore implements CurrencyPurge.Store {

        private final List<String> calls = new ArrayList<>();
        private final List<Long> logs;
        private final List<Long> accounts;

        RecordingStore(int logCount, int accountCount) {
            this.logs = new ArrayList<>(LongStream.rangeClosed(1, logCount).boxed().toList());
            this.accounts = new ArrayList<>(LongStream.rangeClosed(1, accountCount).boxed().toList());
        }

        @Override
        public @NotNull List<Long> logIds(long currencyId, int limit) {
            return List.copyOf(logs.subList(0, Math.min(limit, logs.size())));
        }

        @Override
        public void deleteLogs(@NotNull List<Long> ids) {
            calls.add("logs:" + ids.size());
            logs.removeAll(ids);
        }

        @Override
        public @NotNull List<Long> accountIds(long currencyId, int limit) {
            return List.copyOf(accounts.subList(0, Math.min(limit, accounts.size())));
        }

        @Override
        public void deleteAccounts(@NotNull List<Long> ids) {
            calls.add("accounts:" + ids.size());
            accounts.removeAll(ids);
        }

        @Override
        public void deleteCurrency(long currencyId) {
            calls.add("currency:" + currencyId);
        }
    }
}
