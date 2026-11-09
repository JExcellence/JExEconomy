package de.jexcellence.economy.database.entity;

import de.jexcellence.jehibernate.entity.base.LongIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Audit row recording that a specific coupon UUID has been redeemed.
 *
 * <p>Duplicate-protection is enforced at the database layer via the
 * unique index on {@code couponId}: redeeming a coupon translates to
 * an {@code INSERT}, and a duplicate {@code couponId} fails the
 * insert - which is the signal that the coupon was already redeemed
 * (possibly by another player who got a copy through creative cloning
 * or a malicious dupe). One row, one redemption, atomic at the DB.
 *
 * <p>Templates themselves live in {@code coupons.yml}; only their
 * identifier is persisted here, so a coupon's payout can be rebalanced
 * later without rewriting historical redemption rows.
 */
@Entity
@Table(
        name = "economy_redeemed_coupon",
        indexes = {
                @Index(name = "ix_coupon_id", columnList = "coupon_id", unique = true),
                @Index(name = "ix_coupon_redeemer", columnList = "redeemer_uuid")
        }
)
public class RedeemedCoupon extends LongIdEntity {

    /** Zone of {@link #getRedeemedAt()}; the column stores a local date-time in this zone. */
    public static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    @Column(name = "coupon_id", unique = true, nullable = false, length = 36)
    private String couponId;

    @Column(name = "template_id", nullable = false, length = 64)
    private String templateId;

    @Column(name = "redeemer_uuid", nullable = false, length = 36)
    private String redeemerUuid;

    @Column(name = "redeemed_at", nullable = false)
    private LocalDateTime redeemedAt;

    @Column(name = "issuer_uuid", length = 36)
    private String issuerUuid;

    /** JPA constructor. */
    protected RedeemedCoupon() {
    }

    public RedeemedCoupon(@NotNull String couponId,
                           @NotNull String templateId,
                           @NotNull UUID redeemer,
                           @Nullable UUID issuer) {
        this.couponId = couponId;
        this.templateId = templateId;
        this.redeemerUuid = redeemer.toString();
        this.issuerUuid = issuer != null ? issuer.toString() : null;
        this.redeemedAt = LocalDateTime.now(ZONE);
    }

    public @NotNull String getCouponId() {
        return this.couponId;
    }

    public @NotNull String getTemplateId() {
        return this.templateId;
    }

    public @NotNull UUID getRedeemerUuid() {
        return UUID.fromString(this.redeemerUuid);
    }

    public @NotNull LocalDateTime getRedeemedAt() {
        return this.redeemedAt;
    }

    public @Nullable UUID getIssuerUuid() {
        return this.issuerUuid != null ? UUID.fromString(this.issuerUuid) : null;
    }
}
