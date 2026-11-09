package de.jexcellence.economy.database.entity;

import de.jexcellence.economy.api.AccountSnapshot;
import de.jexcellence.economy.money.Money;
import de.jexcellence.jehibernate.entity.base.LongIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;

/**
 * A player's balance for a specific currency.
 *
 * <p>Business logic (deposit, withdraw, balance checks) lives in the balance ledger, not here. Owner and currency
 * load lazily: repository queries that hand accounts to views fetch both in the same query, everything else works
 * with ids. One account per player and currency is enforced by {@code uk_account_player_currency}; the startup
 * duplicate merge runs before the constraint is relied on.
 *
 * @author JExcellence
 * @since 3.0.0
 */
@Entity
@Table(name = "economy_account",
        uniqueConstraints = @UniqueConstraint(name = Account.UNIQUE_PLAYER_CURRENCY,
                columnNames = {"player_id", "currency_id"}),
        indexes = @Index(name = "idx_account_currency_balance", columnList = "currency_id, balance"))
public class Account extends LongIdEntity {

    /** Name of the unique constraint on owner and currency. */
    public static final String UNIQUE_PLAYER_CURRENCY = "uk_account_player_currency";

    private static final int MONEY_PRECISION = 64;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_id", nullable = false)
    private EconomyPlayer player;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "currency_id", nullable = false)
    private Currency currency;

    /**
     * Precision and scale instead of a {@code columnDefinition}: a definition with {@code DEFAULT} ends up inside the
     * schema update's {@code ALTER COLUMN ... SET DATA TYPE}, which H2 rejects. New accounts start at zero in code.
     */
    @Column(name = "balance", nullable = false, precision = MONEY_PRECISION, scale = Money.SCALE)
    private BigDecimal balance = Money.ZERO;

    /** JPA constructor. */
    protected Account() {
    }

    /**
     * Creates a zero-balance account.
     *
     * @param player   the account owner
     * @param currency the currency
     */
    public Account(@NotNull EconomyPlayer player, @NotNull Currency currency) {
        this.player = player;
        this.currency = currency;
        this.balance = Money.ZERO;
    }

    /**
     * Returns the account owner. Outside a repository query that fetched it, only its id may be read.
     *
     * @return the player
     */
    public @NotNull EconomyPlayer getPlayer() {
        return player;
    }

    /**
     * Returns the currency. Outside a repository query that fetched it, only its id may be read.
     *
     * @return the currency
     */
    public @NotNull Currency getCurrency() {
        return currency;
    }

    /**
     * Returns the current balance with two decimals.
     *
     * @return the balance
     */
    public @NotNull BigDecimal getBalance() {
        return Money.of(balance);
    }

    /**
     * Sets the balance; the value is rounded to two decimals.
     *
     * @param balance the new balance, never negative
     */
    public void setBalance(@NotNull BigDecimal balance) {
        if (balance.signum() < 0) {
            throw new IllegalArgumentException("Balance cannot be negative");
        }
        this.balance = Money.of(balance);
    }

    /**
     * Creates an API-safe snapshot of this account. Needs owner and currency fetched.
     *
     * @return a lightweight, immutable snapshot
     */
    public @NotNull AccountSnapshot toSnapshot() {
        return new AccountSnapshot(
                player.getUniqueId(), player.getPlayerName(),
                currency.getIdentifier(), Money.toDouble(getBalance()));
    }

    @Override
    public String toString() {
        return "Account[#" + getId() + "=" + balance + "]";
    }
}
