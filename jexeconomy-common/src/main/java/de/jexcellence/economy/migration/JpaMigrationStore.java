package de.jexcellence.economy.migration;

import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.database.entity.MigrationEntry;
import de.jexcellence.economy.database.entity.MigrationRun;
import de.jexcellence.economy.database.repository.AccountRepository;
import de.jexcellence.economy.database.repository.MigrationEntryRepository;
import de.jexcellence.economy.database.repository.MigrationRunRepository;
import de.jexcellence.economy.database.repository.TransactionLogRepository;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Keeps imports in the economy database.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public final class JpaMigrationStore implements MigrationStore {

    private final MigrationRunRepository runs;
    private final MigrationEntryRepository entries;
    private final AccountRepository accounts;
    private final TransactionLogRepository logs;
    private final Function<String, Optional<Currency>> currencies;

    /**
     * Creates the store.
     *
     * @param repositories the repositories
     * @param currencies   finds a currency by id
     */
    public JpaMigrationStore(@NotNull Repositories repositories,
                             @NotNull Function<String, Optional<Currency>> currencies) {
        this.runs = repositories.runs();
        this.entries = repositories.entries();
        this.accounts = repositories.accounts();
        this.logs = repositories.logs();
        this.currencies = currencies;
    }

    /**
     * The repositories of the store.
     *
     * @param runs     the runs
     * @param entries  the entries
     * @param accounts the accounts
     * @param logs     the transaction log
     */
    public record Repositories(@NotNull MigrationRunRepository runs, @NotNull MigrationEntryRepository entries,
                               @NotNull AccountRepository accounts, @NotNull TransactionLogRepository logs) {
    }

    @Override
    public @NotNull CompletableFuture<MigrationRun> createRun(@NotNull MigrationRun run) {
        return runs.createAsync(run);
    }

    @Override
    public @NotNull CompletableFuture<Optional<MigrationRun>> run(long id) {
        return runs.findByRunIdAsync(id);
    }

    @Override
    public @NotNull CompletableFuture<Optional<MigrationRun>> latestRun() {
        return runs.findLatestAsync();
    }

    @Override
    public @NotNull CompletableFuture<List<MigrationRun>> runsIn(@NotNull MigrationRun.State state) {
        return runs.findInStateAsync(state);
    }

    @Override
    public @NotNull CompletableFuture<Void> progress(long id, @NotNull MigrationRun.State state, int imported,
                                                     int failed) {
        return runs.progressAsync(id, state, imported, failed);
    }

    @Override
    public @NotNull CompletableFuture<List<MigrationEntry>> entries(@NotNull String sourceId,
                                                                    @NotNull String currencyId) {
        return entries.findBySourceAsync(sourceId, currencyId);
    }

    @Override
    public @NotNull CompletableFuture<List<MigrationEntry>> entries(long runId, @NotNull MigrationEntry.State state) {
        return entries.findByRunAsync(runId, state);
    }

    @Override
    public @NotNull CompletableFuture<Void> save(@NotNull Collection<MigrationEntry> rows) {
        return entries.storeAsync(rows);
    }

    @Override
    public @NotNull CompletableFuture<Map<UUID, BigDecimal>> balances(@NotNull String currencyId) {
        return accounts.findHoldersAsync(currency(currencyId)).thenApply(JpaMigrationStore::toBalances);
    }

    @Override
    public @NotNull CompletableFuture<Map<UUID, BigDecimal>> balances(@NotNull String currencyId,
                                                                      @NotNull Collection<UUID> players) {
        return accounts.findHoldersAsync(currency(currencyId), players).thenApply(JpaMigrationStore::toBalances);
    }

    private static @NotNull Map<UUID, BigDecimal> toBalances(@NotNull List<AccountRepository.Holder> holders) {
        Map<UUID, BigDecimal> balances = new HashMap<>();
        holders.forEach(holder -> balances.put(holder.playerUuid(), holder.balance()));
        return balances;
    }

    @Override
    public @NotNull CompletableFuture<Set<UUID>> playersWithHistory(@NotNull String currencyId) {
        return logs.findPlayersWithHistoryAsync(currency(currencyId));
    }

    private @NotNull Currency currency(@NotNull String currencyId) {
        return currencies.apply(currencyId)
                .orElseThrow(() -> new MigrationRefused("currency-unknown"));
    }
}
