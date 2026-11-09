package de.jexcellence.economy.coupon;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Declarative definition of a coupon - its display layer + the bundle
 * of currencies it pays out on redeem.
 *
 * <p>Templates are loaded from {@code coupons.yml} at plugin start.
 * Issued coupons reference their template by {@link #id()}; payout
 * happens at redeem time by looking up the template fresh, so
 * rebalancing the payout in YAML retroactively changes the value of
 * un-redeemed coupons (intentional - admins can buff/nerf without
 * minting new ones).
 *
 * @param id          unique key, e.g. {@code "starter_pack"}
 * @param displayName MiniMessage display name shown on the ItemStack
 * @param lore        MiniMessage lore lines (payout details are appended automatically)
 * @param icon        Material used for the ItemStack
 * @param glint       true to apply enchantment glint without enchantments
 * @param payouts     currencies + amounts deposited on redeem
 * @param commands    console commands run on redeem, {@code {player}} substituted with
 *                    the redeemer's name (e.g. crate keys, fly time, item kits) - lets a
 *                    coupon grant more than currency. Runs after the payouts, main thread.
 */
public record CouponTemplate(
        @NotNull String id,
        @NotNull String displayName,
        @NotNull List<String> lore,
        @NotNull Material icon,
        boolean glint,
        @NotNull List<CouponPayout> payouts,
        @NotNull List<String> commands
) {

    /** A single (currency, amount) payout entry within a coupon. */
    public record CouponPayout(@NotNull String currency, double amount) {
    }
}
