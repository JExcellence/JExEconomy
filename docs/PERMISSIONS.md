# JExEconomy permissions

> **Status:** valid from JExEconomy 3.1.0. **Source of truth:** `EconomyPermission` in
> `jexeconomy-common/src/main/java/de/jexcellence/economy/permission/`. `plugin.yml` and `paper-plugin.yml`
> declare the same nodes; a unit test checks that the three agree.

All nodes live under `jexeconomy.*`. The old nodes keep working for now; a later major version removes them:

- Command gates (the `permission:` fields of the command YAML trees) name only the new nodes. Every old node is
  declared in the plugin descriptors with `default: false` and the new node as a child, so a group that holds the
  old node also holds the new one. LuckPerms resolves these children with its default
  `apply-bukkit-child-permissions: true`.
- Checks in code (`EconomyPermission.has`) accept the new node or the old one. An explicit grant or denial of the new
  node wins over the old node.
- Bundles: `jexeconomy.*` grants everything, `jexeconomy.admin.*` every admin node plus the currency manager,
  `/currencylog player|clear` and `/balance <player>`, `jexeconomy.coupon.*` every coupon node.

## Old to new

| New node | Default | Old node(s) | Used for |
|---|---|---|---|
| `jexeconomy.balance` | true | `currency.command` | Use /balance |
| `jexeconomy.balance.other` | op | `currency.command.other` | Show the balance of another player |
| `jexeconomy.balance.top` | true | `currency.command.top` | Use /balance top |
| `jexeconomy.pay` | true | `pay.command` | Use /pay |
| `jexeconomy.currencylog` | true | `currencylog.command` | Use /currencylog for the own log |
| `jexeconomy.currencylog.player` | op | `currencylog.command.player` | Read the transaction log of another player |
| `jexeconomy.currencylog.clear` | op | `currencylog.command.clear` | Delete old transaction log entries |
| `jexeconomy.currencies` | op | `currencies.command` | Use /currencies |
| `jexeconomy.currencies.overview` | op | `currencies.command.overview` | Open the currency manager |
| `jexeconomy.currencies.create` | op | `currencies.command.create` | Create a currency |
| `jexeconomy.currencies.edit` | op | `currencies.command.update`, `jexeconomy.admin.edit` | Edit a currency |
| `jexeconomy.currencies.delete` | op | `currencies.command.delete` | Delete a currency with all its balances |
| `jexeconomy.currencies.reset` | op | `jexeconomy.admin.reset` | Reset every balance of a currency |
| `jexeconomy.admin` | op | `economy.command` | Use /economy |
| `jexeconomy.admin.give` | op | `economy.command.give` | Give currency to a player |
| `jexeconomy.admin.take` | op | `economy.command.take` | Take currency from a player |
| `jexeconomy.admin.set` | op | `economy.command.set` | Set the balance of a player |
| `jexeconomy.admin.reset-player` | op | `economy.command.reset` | Reset the balance of a player |
| `jexeconomy.admin.giveall` | op | `economy.command.giveall` | Give currency to every online player |
| `jexeconomy.admin.migrate` | op | `economy.command.migrate` | Show the migration screens |
| `jexeconomy.admin.reload` | op | `economy.command.reload` | Reload currencies and texts |
| `jexeconomy.coupon` | op | `economy.coupon` | Use /coupon |
| `jexeconomy.coupon.give` | op | `economy.coupon.give` | Mint and give a coupon to a player |
| `jexeconomy.coupon.list` | op | `economy.coupon.list` | List coupon templates |
| `jexeconomy.coupon.reload` | op | `economy.coupon.reload` | Reload coupons.yml |
| `jexeconomy.coupon.history` | op | `economy.coupon.history` | Show the coupon redemptions of a player |

`jexeconomy.admin.reset` keeps what it meant before 3.1.0: the "reset every balance" button of the currency manager.
It is the alias of `jexeconomy.currencies.reset`. The balance reset of one player (`/economy reset`, formerly
`economy.command.reset`) has its own node `jexeconomy.admin.reset-player`, so nobody gains a reset they did not have.

## Migrating groups (LuckPerms)

Run these for every group or user that holds an old node, then check the result with
`/lp group <group> permission info`. Replace `<group>` with the group name, for example `default` or `admin`.
Nodes that are granted to everyone by default (`jexeconomy.balance`, `jexeconomy.balance.top`, `jexeconomy.pay`,
`jexeconomy.currencylog`) only need a command when the group sets the old node explicitly.

Find who holds an old node:

```text
/lp search currency.command
/lp search currency.command.other
/lp search currency.command.top
/lp search pay.command
/lp search currencylog.command
/lp search currencylog.command.player
/lp search currencylog.command.clear
/lp search currencies.command
/lp search currencies.command.overview
/lp search currencies.command.create
/lp search currencies.command.update
/lp search jexeconomy.admin.edit
/lp search currencies.command.delete
/lp search jexeconomy.admin.reset
/lp search economy.command
/lp search economy.command.give
/lp search economy.command.take
/lp search economy.command.set
/lp search economy.command.reset
/lp search economy.command.giveall
/lp search economy.command.migrate
/lp search economy.command.reload
/lp search economy.coupon
/lp search economy.coupon.give
/lp search economy.coupon.list
/lp search economy.coupon.reload
/lp search economy.coupon.history
```

Grant the new node and remove the old one (one pair per old node the group holds):

```text
/lp group <group> permission set jexeconomy.balance true
/lp group <group> permission unset currency.command
/lp group <group> permission set jexeconomy.balance.other true
/lp group <group> permission unset currency.command.other
/lp group <group> permission set jexeconomy.balance.top true
/lp group <group> permission unset currency.command.top
/lp group <group> permission set jexeconomy.pay true
/lp group <group> permission unset pay.command
/lp group <group> permission set jexeconomy.currencylog true
/lp group <group> permission unset currencylog.command
/lp group <group> permission set jexeconomy.currencylog.player true
/lp group <group> permission unset currencylog.command.player
/lp group <group> permission set jexeconomy.currencylog.clear true
/lp group <group> permission unset currencylog.command.clear
/lp group <group> permission set jexeconomy.currencies true
/lp group <group> permission unset currencies.command
/lp group <group> permission set jexeconomy.currencies.overview true
/lp group <group> permission unset currencies.command.overview
/lp group <group> permission set jexeconomy.currencies.create true
/lp group <group> permission unset currencies.command.create
/lp group <group> permission set jexeconomy.currencies.edit true
/lp group <group> permission unset currencies.command.update
/lp group <group> permission set jexeconomy.currencies.edit true
/lp group <group> permission unset jexeconomy.admin.edit
/lp group <group> permission set jexeconomy.currencies.delete true
/lp group <group> permission unset currencies.command.delete
/lp group <group> permission set jexeconomy.currencies.reset true
/lp group <group> permission unset jexeconomy.admin.reset
/lp group <group> permission set jexeconomy.admin true
/lp group <group> permission unset economy.command
/lp group <group> permission set jexeconomy.admin.give true
/lp group <group> permission unset economy.command.give
/lp group <group> permission set jexeconomy.admin.take true
/lp group <group> permission unset economy.command.take
/lp group <group> permission set jexeconomy.admin.set true
/lp group <group> permission unset economy.command.set
/lp group <group> permission set jexeconomy.admin.reset-player true
/lp group <group> permission unset economy.command.reset
/lp group <group> permission set jexeconomy.admin.giveall true
/lp group <group> permission unset economy.command.giveall
/lp group <group> permission set jexeconomy.admin.migrate true
/lp group <group> permission unset economy.command.migrate
/lp group <group> permission set jexeconomy.admin.reload true
/lp group <group> permission unset economy.command.reload
/lp group <group> permission set jexeconomy.coupon true
/lp group <group> permission unset economy.coupon
/lp group <group> permission set jexeconomy.coupon.give true
/lp group <group> permission unset economy.coupon.give
/lp group <group> permission set jexeconomy.coupon.list true
/lp group <group> permission unset economy.coupon.list
/lp group <group> permission set jexeconomy.coupon.reload true
/lp group <group> permission unset economy.coupon.reload
/lp group <group> permission set jexeconomy.coupon.history true
/lp group <group> permission unset economy.coupon.history
```

For a single player use `/lp user <name> permission set|unset ...` with the same nodes. A denied old node
(`... set currency.command false`) becomes `... set jexeconomy.balance false`.

## Retiring the aliases

After the season: remove the old-node entries from `plugin.yml` and `paper-plugin.yml` and the old nodes from
`EconomyPermission`, then run `/lp search` for each old node once more to confirm no group still depends on it.
