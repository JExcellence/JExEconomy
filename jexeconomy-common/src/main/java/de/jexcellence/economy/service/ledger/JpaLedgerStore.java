package de.jexcellence.economy.service.ledger;

import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.database.repository.AccountRepository;
import de.jexcellence.economy.database.repository.PlayerRepository;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The database side of the ledger: finds or creates player and account rows and writes balances with a version
 * check.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public final class JpaLedgerStore implements LedgerStore {

    private final PlayerRepository players;
    private final AccountRepository accounts;
    private final Function<String, Optional<Currency>> currencies;

    /**
     * Creates the store.
     *
     * @param players    the player repository
     * @param accounts   the account repository
     * @param currencies resolves a currency identifier to its cached entity
     */
    public JpaLedgerStore(@NotNull PlayerRepository players, @NotNull AccountRepository accounts,
                          @NotNull Function<String, Optional<Currency>> currencies) {
        this.players = players;
        this.accounts = accounts;
        this.currencies = currencies;
    }

    @Override
    public @NotNull LedgerEntry load(@NotNull AccountKey key) {
        Currency currency = currencies.apply(key.currencyId())
                .orElseThrow(() -> new IllegalStateException("Unknown currency " + key.currencyId()));
        var player = players.findOrCreate(key.playerId(), key.storedName());
        var account = accounts.findOrCreate(player, currency);
        return new LedgerEntry(account.getId(), account.getVersion(), account.getBalance());
    }

    @Override
    public void write(@NotNull LedgerEntry entry, @NotNull BigDecimal balance) {
        accounts.writeBalance(entry.accountId(), entry.version(), balance);
    }

    @Override
    public void writeBoth(@NotNull LedgerEntry first, @NotNull BigDecimal firstBalance,
                          @NotNull LedgerEntry second, @NotNull BigDecimal secondBalance) {
        accounts.writeBalances(List.of(
                new AccountRepository.VersionedBalance(first.accountId(), first.version(), firstBalance),
                new AccountRepository.VersionedBalance(second.accountId(), second.version(), secondBalance)));
    }
}
