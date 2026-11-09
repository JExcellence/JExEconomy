package de.jexcellence.economy.service;

import de.jexcellence.economy.database.repository.AccountRepository;
import de.jexcellence.economy.database.repository.CurrencyRepository;
import de.jexcellence.economy.database.repository.OpenTransferRepository;
import de.jexcellence.economy.database.repository.PlayerRepository;
import de.jexcellence.economy.service.currency.CurrencyLifecycle;
import de.jexcellence.economy.service.currency.CurrencyRegistry;
import de.jexcellence.economy.service.ledger.BalanceLedger;
import de.jexcellence.economy.service.ledger.JpaLedgerStore;
import de.jexcellence.jexplatform.logging.JExLogger;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ExecutorService;

/**
 * Wires the economy collaborators into an {@link EconomyService}.
 *
 * <pre>
 * EconomyService (EconomyProvider facade)
 *  |- CurrencyRegistry   cache, policies, currencies.yml
 *  |- CurrencyLifecycle  create / edit / delete / reset with events
 *  |- AccountDirectory   account reads, display balances
 *  |- WalletService      deposit (with redirect), credit, withdraw, set
 *  |    '- BalanceLedger per-account queue, retry on version conflicts
 *  |         |- JpaLedgerStore         rows and version-checked writes
 *  |         '- BalanceChangeNotifier  BalanceChange(d)Event, transaction log, OnlineBalances
 *  |- OnlineBalances     Vault balance of online players
 *  '- TransferService    /pay legs through RoutedTransfer, TransferJournal
 * </pre>
 *
 * @author JExcellence
 * @since 3.1.0
 */
public final class EconomyServices {

    private EconomyServices() {
    }

    /**
     * The repositories the economy needs.
     *
     * @param currencies the currency repository
     * @param players    the player repository
     * @param accounts   the account repository
     * @param openTransfers the payments whose legs are still running
     */
    public record Repositories(@NotNull CurrencyRepository currencies, @NotNull PlayerRepository players,
                               @NotNull AccountRepository accounts, @NotNull OpenTransferRepository openTransfers) {
    }

    /**
     * The platform pieces the economy needs.
     *
     * @param logger    the plugin logger
     * @param scheduler the platform scheduler
     * @param plugin    the plugin
     * @param executor  the economy executor limited to the database capacity; never the common pool
     * @param waiting   the unlimited executor for work that only waits, such as a pre-event waiting for a tick
     */
    public record Context(@NotNull JExLogger logger, @NotNull PlatformScheduler scheduler,
                          @NotNull JavaPlugin plugin, @NotNull ExecutorService executor,
                          @NotNull ExecutorService waiting) {
    }

    /**
     * Builds the service graph.
     *
     * @param repositories      the repositories
     * @param transactionLogger the transaction log writer
     * @param context           the platform pieces
     * @return the economy facade
     */
    public static @NotNull EconomyService create(@NotNull Repositories repositories,
                                                 @NotNull TransactionLogger transactionLogger,
                                                 @NotNull Context context) {
        var logger = context.logger();
        var executor = context.executor();
        var events = new EconomyEvents(context.scheduler());
        var registry = new CurrencyRegistry(repositories.currencies(), context.plugin(), logger);
        var store = new JpaLedgerStore(repositories.players(), repositories.accounts(), registry::find);
        var accounts = new AccountDirectory(repositories.players(), repositories.accounts());
        var onlineBalances = new OnlineBalances(accounts::balance, logger);
        var ledger = new BalanceLedger(store,
                new BalanceChangeNotifier(events, transactionLogger, registry, onlineBalances),
                executor, context.waiting());
        var wallets = new WalletService(ledger, accounts, logger);
        var lifecycle = new CurrencyLifecycle(registry,
                new CurrencyLifecycle.Stores(repositories.currencies(), repositories.accounts()), ledger, events,
                logger, executor);
        var journal = new JpaTransferJournal(repositories.openTransfers(), logger, executor);
        var transfers = new TransferService(wallets, registry, transactionLogger, logger, executor, journal);
        var runtime = new EconomyService.Infrastructure(executor, logger, new EconomyDrain(ledger, events, logger),
                journal);
        return new EconomyService(new EconomyService.Parts(registry, lifecycle, accounts, wallets, transfers,
                onlineBalances, runtime));
    }
}
