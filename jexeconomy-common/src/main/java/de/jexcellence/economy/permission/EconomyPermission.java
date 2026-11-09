package de.jexcellence.economy.permission;

import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.PermissionDefault;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Every permission node of JExEconomy under the {@code jexeconomy.*} namespace, with the node it replaced.
 *
 * <p>The old nodes stay valid for now. Code checks go through {@link #has(Permissible)}, which accepts the
 * new node or its old alias. The command YAML trees name only the new nodes; {@code plugin.yml} and
 * {@code paper-plugin.yml} declare every old node with the new node as a child, so a group that still holds the old
 * node passes the command gate as well.
 *
 * <p>{@code jexeconomy.admin.reset} keeps its meaning (reset a whole currency) as the alias of
 * {@link #CURRENCIES_RESET};
 * the balance reset of one player ({@code /economy reset}) got its own node {@link #ADMIN_RESET_PLAYER}.
 * {@code docs/PERMISSIONS.md} lists all pairs.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public enum EconomyPermission {

    BALANCE("jexeconomy.balance", PermissionDefault.TRUE, "currency.command"),
    BALANCE_OTHER("jexeconomy.balance.other", PermissionDefault.OP, "currency.command.other"),
    BALANCE_TOP("jexeconomy.balance.top", PermissionDefault.TRUE, "currency.command.top"),
    PAY("jexeconomy.pay", PermissionDefault.TRUE, "pay.command"),
    CURRENCYLOG("jexeconomy.currencylog", PermissionDefault.TRUE, "currencylog.command"),
    CURRENCYLOG_PLAYER("jexeconomy.currencylog.player", PermissionDefault.OP, "currencylog.command.player"),
    CURRENCYLOG_CLEAR("jexeconomy.currencylog.clear", PermissionDefault.OP, "currencylog.command.clear"),
    CURRENCIES("jexeconomy.currencies", PermissionDefault.OP, "currencies.command"),
    CURRENCIES_OVERVIEW("jexeconomy.currencies.overview", PermissionDefault.OP, "currencies.command.overview"),
    CURRENCIES_CREATE("jexeconomy.currencies.create", PermissionDefault.OP, "currencies.command.create"),
    CURRENCIES_EDIT("jexeconomy.currencies.edit", PermissionDefault.OP, "currencies.command.update",
            "jexeconomy.admin.edit"),
    CURRENCIES_DELETE("jexeconomy.currencies.delete", PermissionDefault.OP, "currencies.command.delete"),
    CURRENCIES_RESET("jexeconomy.currencies.reset", PermissionDefault.OP, "jexeconomy.admin.reset"),
    ADMIN("jexeconomy.admin", PermissionDefault.OP, "economy.command"),
    ADMIN_GIVE("jexeconomy.admin.give", PermissionDefault.OP, "economy.command.give"),
    ADMIN_TAKE("jexeconomy.admin.take", PermissionDefault.OP, "economy.command.take"),
    ADMIN_SET("jexeconomy.admin.set", PermissionDefault.OP, "economy.command.set"),
    ADMIN_RESET_PLAYER("jexeconomy.admin.reset-player", PermissionDefault.OP, "economy.command.reset"),
    ADMIN_GIVEALL("jexeconomy.admin.giveall", PermissionDefault.OP, "economy.command.giveall"),
    ADMIN_MIGRATE("jexeconomy.admin.migrate", PermissionDefault.OP, "economy.command.migrate"),
    ADMIN_RELOAD("jexeconomy.admin.reload", PermissionDefault.OP, "economy.command.reload"),
    COUPON("jexeconomy.coupon", PermissionDefault.OP, "economy.coupon"),
    COUPON_GIVE("jexeconomy.coupon.give", PermissionDefault.OP, "economy.coupon.give"),
    COUPON_LIST("jexeconomy.coupon.list", PermissionDefault.OP, "economy.coupon.list"),
    COUPON_RELOAD("jexeconomy.coupon.reload", PermissionDefault.OP, "economy.coupon.reload"),
    COUPON_HISTORY("jexeconomy.coupon.history", PermissionDefault.OP, "economy.coupon.history");

    /** Wildcard that grants every administrative screen of the currency views. */
    public static final String ADMIN_WILDCARD = "jexeconomy.admin.*";

    private final String node;
    private final List<String> legacyNodes;
    private final PermissionDefault defaultValue;

    EconomyPermission(@NotNull String node, @NotNull PermissionDefault defaultValue, @NotNull String... legacyNodes) {
        this.node = node;
        this.defaultValue = defaultValue;
        this.legacyNodes = List.of(legacyNodes);
    }

    /**
     * The node under {@code jexeconomy.*}.
     *
     * @return the permission node
     */
    public @NotNull String node() {
        return node;
    }

    /**
     * The nodes this permission replaced, accepted until the aliases are retired.
     *
     * @return the old permission nodes, empty for a permission without a predecessor
     */
    public @NotNull List<String> legacyNodes() {
        return legacyNodes;
    }

    /**
     * Who holds the node without an explicit grant.
     *
     * @return the Bukkit default
     */
    public @NotNull PermissionDefault defaultValue() {
        return defaultValue;
    }

    /**
     * Whether {@code permissible} holds this permission. An explicit grant or denial of the new node wins; without
     * one, the first old node that is explicitly granted or denied decides; otherwise the default of the new node
     * applies.
     *
     * @param permissible the player or console
     * @return {@code true} when the permission is held
     */
    public boolean has(@NotNull Permissible permissible) {
        if (permissible.isPermissionSet(node)) {
            return permissible.hasPermission(node);
        }
        for (String legacyNode : legacyNodes) {
            if (permissible.isPermissionSet(legacyNode)) {
                return permissible.hasPermission(legacyNode);
            }
        }
        return permissible.hasPermission(node);
    }

    /**
     * Whether {@code permissible} is an operator, holds this permission or holds {@link #ADMIN_WILDCARD}.
     *
     * @param permissible the player
     * @return {@code true} when the administrative action is allowed
     */
    public boolean hasOrAdmin(@NotNull Permissible permissible) {
        return permissible.isOp() || has(permissible) || permissible.hasPermission(ADMIN_WILDCARD);
    }

    /**
     * The permission with the given new node.
     *
     * @param node the node under {@code jexeconomy.*}
     * @return the permission, empty when JExEconomy does not define it
     */
    public static @NotNull Optional<EconomyPermission> byNode(@Nullable String node) {
        if (node == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(permission -> permission.node.equals(node)).findFirst();
    }
}
