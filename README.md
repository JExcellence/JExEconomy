# JExEconomy

A multi-currency economy plugin for Paper, Folia and Spigot 26.x that can take Vault's place for economy plugins: anything using the Vault economy API works without a separate Vault jar.

- Any number of currencies, with symbols, prefixes, suffixes and icons
- Exact balances, stored as decimals and booked one at a time per account
- Vault provider that is available while plugins enable
- Import from any Vault economy, CSV or EssentialsX, with dry run, pause, resume and rollback
- Transaction log per player, coupons and currency items, GUI management, leaderboard
- English and German texts, every text editable
- API and extension interface for other plugins

## About this repository

This repository publishes the source code of JExEconomy so you can read and review it. It cannot be built from here: it depends on libraries that are not public, and the build lives in a private repository. Ready-to-use jars come with each release.

The license is the Apache License 2.0 with the Commons Clause. JExEconomy is **not open source**: you may not sell it or a service whose value comes mainly from it. Servers that earn money, for example from ranks, may use it. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

## Download

Get the jar from the [releases page](https://github.com/JExcellence/JExEconomy/releases).

## Requirements

- Java 25
- Paper, Folia or Spigot 26.x
- Network access on the first start: the plugin downloads Hibernate, database drivers and other libraries from Maven repositories and caches them. A download is checked against the published SHA-1 checksum when the repository provides one.
- Optional: PlaceholderAPI

## Installation

1. Put the JExEconomy jar into `plugins/`.
2. Start the server. The configuration and translation files are generated.
3. Currencies listed in `currencies.yml` are created when missing. See them with `/currencies overview`.

Economy plugins do not need a separate Vault jar. If one is installed, JExEconomy works with it and logs that it can be removed. JExEconomy provides only the Vault economy service, so keep the Vault jar if a plugin on your server uses Vault for permissions or chat. To move from another economy, read [docs/MIGRATION.md](docs/MIGRATION.md).

## Configuration

| File | Purpose |
|------|---------|
| `currencies.yml` | Currency seed definitions (`symbol`, `prefix`, `suffix`, `icon`) |
| `coupons.yml` | Coupon templates (`name`, `lore`, `icon`, `glint`, `payouts`, `commands`) |
| `database/hibernate.properties` | Database selection (`database.type`, default `H2`) and connection settings |
| `logs/logging.yml` | Logging settings |
| `translations/en_US.yml`, `translations/de_DE.yml` | Messages |

### Database

`database.type` accepts `H2`, `MYSQL`, `MARIADB`, `POSTGRESQL`, `ORACLE` and `MSSQL_SERVER`; `hibernate.properties` has a connection block for each. Default is an embedded H2 file database under `plugins/JExEconomy/database/`.

## Commands

### Player Commands

| Command | Aliases | Description |
|---------|---------|-------------|
| `/balance [currency] [player]` | `/money`, `/bal`, `/wallet`, `/pcurrency` | Show balances; another player needs `currency.command.other` |
| `/balance all [player]` | | List every currency balance |
| `/balance top [currency] [limit]` | | Top holders (limit 1..50, default 10) |
| `/pay <target> <amount> [currency]` | `/send`, `/ppay` | Pay another player; currency defaults to the primary one |
| `/currencies overview\|list\|info\|help` | `/pcurrencies`, `/ccurrencies` | GUI overview, chat list, details |
| `/currencies create [identifier] [symbol] [prefix] [suffix]` | | Create a currency (GUI when no args) |
| `/currencies edit <currency> <field> <value>` | | Field: `identifier\|symbol\|prefix\|suffix\|icon` |
| `/currencies delete <currency>` | `remove` | Delete a currency |
| `/currencylog view [page]` | `/pcurrencylog`, `/txlog` | Your transaction log |
| `/currencylog details <id>` | | Single log entry |
| `/currencylog player <player> [page]` | | Another player's log |
| `/currencylog clear [days]` | | Prune entries older than N days (default 90, minimum 7) |

### Admin Commands

`/economy` (aliases `/eco`, `/econ`, console or player):

| Subcommand | Description |
|------------|-------------|
| `give <target> <amount> [currency]` | Add currency |
| `take <target> <amount> [currency]` | Remove currency |
| `set <target> <amount> [currency]` | Set a balance |
| `reset <target> [currency]` | Reset a balance to zero |
| `giveall <amount> [currency]`, then `giveall confirm <amount> [currency]` | Give to every online player, with a confirm step |
| `migrate <sources\|plan\|start\|pause\|resume\|status\|rollback>` | See [MIGRATION.md](docs/MIGRATION.md) |
| `reload` | Reload currency cache and translations |

`/coupon` (alias `/coupons`): `give <player> <template> [amount]`, `list`, `reload`, `history <player>`.

## Permissions

See [docs/PERMISSIONS.md](docs/PERMISSIONS.md).

## Documentation

- [Vault](docs/VAULT.md)
- [Migrating from another economy](docs/MIGRATION.md)
- [API, extensions and events](docs/INTEGRATION.md)
- [Changelog](CHANGELOG.md)
- [Security](SECURITY.md)
- [Contributing](CONTRIBUTING.md)

## Credits

By [JExcellence](https://jexcellence.de). The configuration and expression libraries are based on GPEEE, BBConfigMapper and BukkitEvaluable by BlvckBytes. Third-party components are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
