# Vault


JExEconomy registers itself as the Vault `Economy` provider with `ServicePriority.Highest`. Because it bundles the Vault API and declares `provides: [Vault]`, plugins that soft-depend on Vault work without a separate Vault jar.

Vault knows one currency: `vault.currency` in `currencies.yml` (default `coins`). If that currency does not exist, the provider reports itself as disabled and never falls back to another currency.

Vault must answer immediately, so a call waits at most 2 seconds for the database. A deposit or withdrawal that takes longer is reported as failed and taken back when it finishes. After such a timeout on a server thread, Vault calls from server threads are refused for 5 seconds. Listeners of `BalanceChangeEvent` cannot cancel changes that a Vault plugin requests on a server thread; `BalanceChangedEvent` still fires.

`getBalance`, `has` and `hasAccount` of online players are answered from memory, so they can be up to 5 seconds behind a change made by another server on the same database. Bookings always check the database.

The provider is available as soon as the server enables its plugins and reports itself as disabled until the database is ready. A separate Vault or VaultUnlocked jar can stay installed; JExEconomy registers with it and logs that it can be removed unless another plugin needs it.
