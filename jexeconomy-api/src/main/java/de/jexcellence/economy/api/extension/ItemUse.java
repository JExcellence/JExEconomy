package de.jexcellence.economy.api.extension;

import org.jetbrains.annotations.NotNull;

/**
 * Whether a player may redeem a coupon or currency item.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public enum ItemUse {

    /** The item may be redeemed. */
    ALLOWED("allowed"),
    /** The player may only redeem items issued to them, and this one was not. */
    NOT_OWNER("not-owner"),
    /** Only the owner of this item may redeem it. */
    OWNER_ONLY("owner-only");

    private final String key;

    ItemUse(@NotNull String key) {
        this.key = key;
    }

    /**
     * The last part of the translation key of a refusal.
     *
     * @return the key part
     */
    public @NotNull String key() {
        return key;
    }
}
