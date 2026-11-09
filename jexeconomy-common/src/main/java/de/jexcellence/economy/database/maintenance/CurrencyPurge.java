package de.jexcellence.economy.database.maintenance;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Deletes a currency together with the rows that reference it. Transaction logs and accounts hold a foreign key on
 * the currency, so they go first, in batches, and the currency row goes last. The caller runs the whole purge in one
 * transaction, so a failure leaves every row in place.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public final class CurrencyPurge {

    /** Rows deleted per statement. */
    public static final int BATCH_SIZE = 500;

    private CurrencyPurge() {
    }

    /** The row operations of one purge, all inside the caller's transaction. */
    public interface Store {

        /**
         * Ids of the next transaction logs of the currency.
         *
         * @param currencyId the currency id
         * @param limit      the batch size
         * @return up to {@code limit} ids
         */
        @NotNull List<Long> logIds(long currencyId, int limit);

        /**
         * Deletes transaction logs.
         *
         * @param ids the log ids
         */
        void deleteLogs(@NotNull List<Long> ids);

        /**
         * Ids of the next accounts of the currency.
         *
         * @param currencyId the currency id
         * @param limit      the batch size
         * @return up to {@code limit} ids
         */
        @NotNull List<Long> accountIds(long currencyId, int limit);

        /**
         * Deletes accounts.
         *
         * @param ids the account ids
         */
        void deleteAccounts(@NotNull List<Long> ids);

        /**
         * Deletes the currency row.
         *
         * @param currencyId the currency id
         */
        void deleteCurrency(long currencyId);
    }

    /**
     * What a purge removed.
     *
     * @param accounts deleted accounts
     * @param logs     deleted transaction logs
     */
    public record Result(int accounts, int logs) {
    }

    /**
     * Deletes logs, then accounts, then the currency.
     *
     * @param store      the row operations
     * @param currencyId the currency id
     * @param batchSize  rows per delete statement, at least one
     * @return the deleted row counts
     */
    public static @NotNull Result run(@NotNull Store store, long currencyId, int batchSize) {
        int size = Math.max(1, batchSize);
        int logs = drain(size, limit -> store.logIds(currencyId, limit), store::deleteLogs);
        int accounts = drain(size, limit -> store.accountIds(currencyId, limit), store::deleteAccounts);
        store.deleteCurrency(currencyId);
        return new Result(accounts, logs);
    }

    private static int drain(int batchSize, @NotNull IdSource source, @NotNull IdSink sink) {
        int total = 0;
        List<Long> batch = source.next(batchSize);
        while (!batch.isEmpty()) {
            sink.delete(batch);
            total += batch.size();
            batch = batch.size() < batchSize ? List.of() : source.next(batchSize);
        }
        return total;
    }

    @FunctionalInterface
    private interface IdSource {
        @NotNull List<Long> next(int limit);
    }

    @FunctionalInterface
    private interface IdSink {
        void delete(@NotNull List<Long> ids);
    }
}
