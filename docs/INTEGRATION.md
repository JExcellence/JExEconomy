# Integrating with JExEconomy

## API


`jexeconomy-api` provides `JExEconomyAPI` (static lookup) and the `EconomyProvider` interface, registered on Bukkit's `ServicesManager`. Value types: `CurrencySnapshot`, `AccountSnapshot`, `TransactionResult`, `CurrencyDefinition`, `CurrencyPolicy`, `ChangeType`.

```java
EconomyProvider economy = JExEconomyAPI.get();   // null when JExEconomy is not loaded
if (economy == null) {
    return;
}

economy.getCurrency("coins").ifPresent(currency ->
        economy.getBalance(player, currency.identifier()).thenAccept(balance ->
                getLogger().info(player.getName() + ": " + currency.format(balance))));

economy.deposit(player, "coins", 100.0, null, "quest reward").thenAccept(result -> {
    if (result.isSuccess()) {
        getLogger().info("New balance: " + result.balance());
    } else {
        getLogger().warning("Deposit failed: " + result.error());
    }
});

economy.transfer(sender, recipient, "coins", 25.0);

economy.getTopAccounts("coins", 10).thenAccept(top ->
        top.forEach(account -> getLogger().info(account.playerName() + " " + account.balance())));
```

Other methods: `getCurrencies()`, `hasAccount`, `has`, `withdraw`, `depositAll`, `registerCurrency(CurrencyDefinition)`, `resetCurrency(identifier, reason)`, `getPolicy(identifier)`. `getExactBalance(UUID, currencyId)` returns the balance as `BigDecimal`; `deposit` and `withdraw` also take a `UUID` and a `BigDecimal` amount, rounded to two decimal places. Use `JExEconomyAPI.require()` to fail fast and `isAvailable()` to check. All balance and transaction methods return `CompletableFuture`; schedule Bukkit API calls back onto the correct thread.

`EconomyProvider` is registered once the database is ready, which is after your plugin's `onEnable` in most load orders. Look it up when you need it, or listen for `ServiceRegisterEvent`. Vault plugins are not affected, see [VAULT.md](VAULT.md).

## Thread rules

- API methods return a `CompletableFuture` and do the database work off the server thread. Do not call `join()` or `get()` on a server thread.
- A callback runs on a worker thread. Use your scheduler before touching the world or a player. On Folia that means the entity or region scheduler.
- `BalanceChangeEvent` can be raised off the server thread. Keep listeners short.

## Extensions


A plugin can change who may trade and where money is held by registering an `EconomyExtension` from `jexeconomy-api` in the Bukkit services manager. Every method has a default that keeps plain JExEconomy behaviour, so an extension overrides only what it needs:

| Method | Does |
|---|---|
| `isTradeBlocked` | Refuses payments between two players |
| `displayBalance`, `isExternalBalance` | Shows a balance held outside the wallet |
| `depositRedirect`, `transferRoute` | Books deposits and `/pay` legs outside the wallet |
| `stampIssuedItem`, `checkItemUse` | Binds coupons and currency items to a player |
| `leaderboardCard` | Adds a block to the leaderboard card |

The registration with the highest `ServicePriority` is used, and it ends when its plugin is disabled. Calls can come from any thread unless the Javadoc says otherwise. `jexeconomy-example` is a complete small extension.

## Events


Package `de.jexcellence.economy.api.event`:

| Event | Description |
|-------|-------------|
| `BalanceChangeEvent` | Before a balance modification (cancellable, except for Vault calls on a server thread, see [VAULT.md](VAULT.md)) |
| `BalanceChangedEvent` | After a successful balance modification |
| `CurrencyCreateEvent` | Before a currency is created (cancellable) |
| `CurrencyCreatedEvent` | After a currency is created |
| `CurrencyDeleteEvent` | Before a currency is deleted (cancellable) |
| `CurrencyDeletedEvent` | After a currency is deleted |
| `CurrencyResetEvent` | A currency was reset |
