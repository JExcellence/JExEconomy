package de.jexcellence.economy.api.extension;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * Lets another plugin change how JExEconomy treats money that is not in a wallet, and who may trade. Every method has
 * a default that keeps plain JExEconomy behaviour, so an extension overrides only what it needs.
 *
 * <p>Register an implementation in the Bukkit services manager; it applies at once and ends when the owning plugin is
 * disabled. With several registrations the one with the highest {@code ServicePriority} is used.
 *
 * <pre>{@code
 * getServer().getServicesManager().register(EconomyExtension.class, new MyExtension(), this, ServicePriority.Normal);
 * }</pre>
 *
 * <p>Unless stated otherwise a method can be called from any thread, also while a balance is being booked, so it has
 * to be fast and must not touch world or player state that needs a specific server thread.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public interface EconomyExtension {

    /** Keeps plain JExEconomy behaviour. */
    EconomyExtension NONE = new EconomyExtension() {
    };

    /**
     * Whether payments between two players are refused, in both directions.
     *
     * @param first  one player
     * @param second the other player
     * @return {@code true} to refuse
     */
    default boolean isTradeBlocked(@NotNull UUID first, @NotNull UUID second) {
        return false;
    }

    /**
     * The balance to show a player instead of the wallet.
     *
     * @param player     the player
     * @param currencyId the currency
     * @return the balance, empty to show the wallet
     */
    default @NotNull OptionalDouble displayBalance(@NotNull UUID player, @NotNull String currencyId) {
        return OptionalDouble.empty();
    }

    /**
     * Whether the balance from {@link #displayBalance} is marked as held outside the wallet.
     *
     * @param player     the player
     * @param currencyId the currency
     * @return {@code true} for an external balance
     */
    default boolean isExternalBalance(@NotNull UUID player, @NotNull String currencyId) {
        return false;
    }

    /**
     * Where deposits may go instead of the wallet.
     *
     * @return the redirect
     */
    default @NotNull DepositRedirect depositRedirect() {
        return DepositRedirect.NONE;
    }

    /**
     * Where the two legs of a player-to-player payment book their money.
     *
     * @return the route
     */
    default @NotNull TransferRoute transferRoute() {
        return TransferRoute.WALLET;
    }

    /**
     * Marks a minted coupon or currency item as issued to a player.
     *
     * @param item  the item
     * @param owner the player it was issued to
     */
    default void stampIssuedItem(@NotNull ItemStack item, @NotNull UUID owner) {
    }

    /**
     * Whether a player may redeem a coupon or currency item. Called on the player's server thread.
     *
     * @param item the item
     * @param user the player
     * @return the verdict
     */
    default @NotNull ItemUse checkItemUse(@NotNull ItemStack item, @NotNull UUID user) {
        return ItemUse.ALLOWED;
    }

    /**
     * A block to add to a player's leaderboard card.
     *
     * @param owner  the ranked player
     * @param locale the viewer's locale, for example {@code de_de}
     * @return the block, empty for none
     */
    default @NotNull Optional<ContextCard> leaderboardCard(@NotNull UUID owner, @NotNull String locale) {
        return Optional.empty();
    }
}
