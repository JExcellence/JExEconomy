package de.jexcellence.economy.database.repository;

import de.jexcellence.economy.database.entity.RedeemedCoupon;
import jakarta.persistence.EntityManagerFactory;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Repository for {@link RedeemedCoupon} audit rows. The unique constraint on {@code couponId} is what guarantees
 * one-time redemption: try-insert, and a unique violation means the coupon was already spent.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public class RedeemedCouponRepository extends LoggedCrudRepository<RedeemedCoupon> {

    /**
     * Creates the repository.
     *
     * @param executor    the executor for async operations
     * @param emf         the entity manager factory
     * @param entityClass the entity class
     */
    public RedeemedCouponRepository(@NotNull ExecutorService executor,
                                    @NotNull EntityManagerFactory emf,
                                    @NotNull Class<RedeemedCoupon> entityClass) {
        super(executor, emf, entityClass);
    }

    /**
     * Returns the audit row for a specific coupon UUID, or empty when the coupon has not been redeemed yet.
     *
     * @param couponId the coupon UUID
     * @return the row, or empty
     */
    public @NotNull Optional<RedeemedCoupon> findByCouponId(@NotNull String couponId) {
        return query().and("couponId", couponId).first();
    }

    /**
     * Returns every redemption performed by a given player, most recent first.
     *
     * @param redeemer the player
     * @return the rows
     */
    public @NotNull List<RedeemedCoupon> findByRedeemer(@NotNull UUID redeemer) {
        return query()
                .and("redeemerUuid", redeemer.toString())
                .orderByDesc("redeemedAt")
                .list();
    }

    /**
     * Asynchronous {@link #findByRedeemer(UUID)} on the repository executor.
     *
     * @param redeemer the player
     * @return future resolving to the rows
     */
    public @NotNull CompletableFuture<List<RedeemedCoupon>> findByRedeemerAsync(@NotNull UUID redeemer) {
        return async(() -> findByRedeemer(redeemer));
    }
}
