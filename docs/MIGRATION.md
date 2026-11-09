# Migrating from another economy


`/economy migrate` imports balances once per player from any other Vault economy, from a CSV file (`uuid,name,balance`, placed in `plugins/JExEconomy/migrations/import/`) or from the EssentialsX `userdata` folder. The old plugin is only read.

1. Back up the database and run it during maintenance, because players can still spend while it runs.
2. `/economy migrate sources` lists what was found.
3. `/economy migrate plan <source> [currency] [mode]` is a dry run. It reports the players, totals, rounding and everything left out, and writes `plugins/JExEconomy/migrations/<id>/plan.txt`. The default currency is the Vault currency. Modes: `skip-existing` (default, only accounts without a balance or history), `overwrite`, `add`.
4. `/economy migrate start <id>` runs a plan from the console, within one hour of making it. The source is read again and the balances from before the run go to `before.csv` in the same folder.
5. `/economy migrate status`, `pause` and `resume` follow and control the run. A run cut off by a restart stays paused until `resume`, which works from the console only.
6. `/economy migrate rollback <id>` (console) restores the balances from before the run and leaves every balance alone that changed since. Those players stay marked as imported.

A Vault source only covers players the server knows. Players known only to the old plugin need the CSV or EssentialsX route. Most economy plugins do not run on Folia: migrate on Paper first or use CSV.

## Files

Each plan gets a folder `plugins/JExEconomy/migrations/<id>/` with `plan.txt`. A started run adds `before.csv`, a backup for people to read. `rollback` uses the rows stored in the database.

## Safety

- A player is imported once per source and currency. A player whose balance was rolled back can be imported again.
- `overwrite` and rollback only change a balance that still has the value they expect. `add` keeps every change made meanwhile.
- A database error pauses the run. `resume` checks the real balance of each open row, so nothing is booked twice.
- Only one run can be active per server. Do not migrate from two servers that share a database at the same time.
