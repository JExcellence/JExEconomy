package de.jexcellence.economy.service;

import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.service.ledger.AccountKey;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * Builds ledger keys from Bukkit players. A player without a known name is stored under the start of the UUID
 * and keeps an existing stored name.
 *
 * @author JExcellence
 * @since 3.1.0
 */
final class AccountKeys {

    private AccountKeys() {
    }

    static @NotNull AccountKey of(@NotNull OfflinePlayer player, @NotNull Currency currency) {
        return AccountKey.of(player.getUniqueId(), player.getName(), currency.getIdentifier());
    }
}
