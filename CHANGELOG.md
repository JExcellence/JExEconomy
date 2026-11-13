# Changelog

## 3.2.0

- Vault: the provider is available while plugins enable and reports disabled until the database is ready. It works next to a separate Vault or VaultUnlocked jar.
- Vault: calls wait at most 2 seconds for the database, and balances of online players are answered from memory.
- Folia and Spigot: thread use corrected, Paper-only calls replaced. On Spigot, leaderboard heads of offline players without a cached skin are plain.
- New: `/economy migrate` imports from any Vault economy, CSV or EssentialsX. It replaces the `supported` and `info` screens.
- New: `EconomyExtension` lets other plugins change trading rules and hold balances elsewhere.
- Changed: `crystals` is no longer created or blocked by default, and `coupons.yml` ships neutral examples.
- Changed: a payment refused by the trade guard now reports the error text `Trade blocked`.
- Fixed: transfers inside the wallets are booked in one transaction.

## 3.1.0

- Rebuilt persistence, services and commands. Permissions moved to `jexeconomy.*`.
