package de.jexcellence.economy.view;

import de.jexcellence.economy.api.extension.ContextCard;
import de.jexcellence.economy.api.extension.EconomyExtension;
import de.jexcellence.economy.database.entity.Account;
import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.format.CurrencyFormat;
import de.jexcellence.economy.text.EconomyText;
import de.jexcellence.jexplatform.gui.component.CardLore;
import de.jexcellence.jexplatform.utility.item.HeadBuilder;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Cards of the currency leaderboard: the header and one head card per ranked player, with what an extension adds.
 *
 * @author JExcellence
 * @since 4.1.0
 */
final class LeaderboardCards {

    private static final String KEY = EconomyCards.VIEW + "leaderboard.";
    private static final int PODIUM = 3;

    private LeaderboardCards() {
    }

    static @NotNull ItemStack header(@NotNull Player player, @NotNull Currency currency, int shown) {
        return EconomyCards.card(EconomyCards.material(currency.getIcon()),
                EconomyCards.name(EconomyText.msg(KEY + "header.name")
                        .with("currency", CurrencyFormat.displayName(player, currency)), player),
                CardLore.create()
                        .block(EconomyCards.paragraph(player, KEY + "header.description"))
                        .section(EconomyCards.section(player, "now"), List.of(EconomyCards.row(player,
                                KEY + "label.shown", EconomyCards.value(player, String.valueOf(shown)))))
                        .build());
    }

    static @NotNull ItemStack entry(@NotNull Player player, @NotNull Currency currency, @NotNull Account account,
                                    int rank, @NotNull EconomyExtension extension) {
        var owner = account.getPlayer();
        String nameKey = rank <= PODIUM ? KEY + "entry.podium-" + rank : KEY + "entry.name";
        ItemStack head = HeadBuilder.fromPlayerCached(owner.getUniqueId()).build();
        CardLore lore = CardLore.create()
                .section(EconomyCards.section(player, "balance"), List.of(EconomyCards.row(player,
                        KEY + "label.balance", CurrencyFormat.compact(player, currency, account.getBalance().doubleValue()))));
        extension.leaderboardCard(owner.getUniqueId(), player.getLocale())
                .ifPresent(context -> lore.section(EconomyCards.sectionTitle(player, context.title()),
                        contextRows(player, context)));
        ItemStack card = EconomyCards.card(head,
                EconomyCards.name(EconomyText.msg(nameKey).with("rank", rank)
                        .with("player", owner.getPlayerName()), player),
                lore.build());
        if (rank <= PODIUM) {
            addGlint(card);
        }
        return card;
    }

    private static void addGlint(@NotNull ItemStack card) {
        ItemMeta meta = card.getItemMeta();
        if (meta != null) {
            meta.setEnchantmentGlintOverride(true);
            card.setItemMeta(meta);
        }
    }

    static @NotNull List<Currency> sorted(@NotNull Collection<Currency> currencies) {
        List<Currency> sorted = new ArrayList<>(currencies);
        sorted.sort(Comparator.comparing(Currency::getIdentifier));
        return sorted;
    }

    static int indexOf(@NotNull List<Currency> currencies, @NotNull Currency active) {
        for (int i = 0; i < currencies.size(); i++) {
            if (currencies.get(i).getIdentifier().equalsIgnoreCase(active.getIdentifier())) {
                return i;
            }
        }
        return 0;
    }

    private static @NotNull List<Component> contextRows(@NotNull Player player, @NotNull ContextCard context) {
        return context.rows().stream()
                .map(row -> EconomyCards.labelledRow(player, row.label(), EconomyCards.valueOrNone(player, row.value())))
                .toList();
    }
}
