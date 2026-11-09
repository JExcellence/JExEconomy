package de.jexcellence.economy;

import com.raindropcentral.commands.CommandFactory;
import com.raindropcentral.commands.v2.argument.ArgumentTypeRegistry;
import de.jexcellence.economy.command.CouponTemplateArgumentType;
import de.jexcellence.economy.command.CurrencyArgumentType;
import de.jexcellence.economy.command.R18nCommandMessages;
import de.jexcellence.economy.command.admin.economy.EconomyHandler;
import de.jexcellence.economy.command.admin.economy.MigrateAction;
import de.jexcellence.economy.command.player.coupon.CouponHandler;
import de.jexcellence.economy.command.player.currencies.CurrenciesHandler;
import de.jexcellence.economy.command.player.currencies.CurrencyField;
import de.jexcellence.economy.command.player.currency.BalanceHandler;
import de.jexcellence.economy.command.player.currencylog.CurrencyLogHandler;
import de.jexcellence.economy.command.player.pay.PayHandler;
import de.jexcellence.economy.migration.MigrationMode;
import org.jetbrains.annotations.NotNull;

/**
 * Registers every YAML command tree of JExEconomy against its handler map, with the shared argument types and
 * the {@code economy-v2} message bridge.
 *
 * @author JExcellence
 * @since 4.1.0
 */
final class EconomyCommands {

    private EconomyCommands() {
    }

    static void register(@NotNull JExEconomy economy) {
        var factory = new CommandFactory(economy.getPlugin(), economy);
        var service = economy.economyService();
        var registry = ArgumentTypeRegistry.defaults()
                .register(CurrencyArgumentType.of(service))
                .register(CouponTemplateArgumentType.of(economy.couponRegistry()))
                .register(CurrencyField.argumentType())
                .register(MigrateAction.argumentType())
                .register(MigrationMode.argumentType());
        var messages = new R18nCommandMessages();
        factory.registerTree("commands/pay.yml", new PayHandler(service, economy::bedrockForms, economy.platform().scheduler()).handlerMap(),
                messages, registry);
        factory.registerTree("commands/balance.yml", new BalanceHandler(service).handlerMap(), messages, registry);
        factory.registerTree("commands/currencies.yml", new CurrenciesHandler(economy).handlerMap(), messages,
                registry);
        factory.registerTree("commands/currencylog.yml", new CurrencyLogHandler(economy).handlerMap(), messages,
                registry);
        factory.registerTree("commands/economy.yml",
                new EconomyHandler(service, economy.platform().scheduler(), economy.migration()).handlerMap(), messages, registry);
        factory.registerTree("commands/coupon.yml", new CouponHandler(economy).handlerMap(), messages, registry);
        factory.registerAllCommandsAndListeners();
    }
}
