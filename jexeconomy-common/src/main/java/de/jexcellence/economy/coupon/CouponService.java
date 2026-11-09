package de.jexcellence.economy.coupon;

import de.jexcellence.economy.database.PersistenceFailures;
import de.jexcellence.economy.database.entity.RedeemedCoupon;
import de.jexcellence.economy.database.repository.RedeemedCouponRepository;
import de.jexcellence.economy.service.EconomyService;
import de.jexcellence.economy.service.WalletService;
import de.jexcellence.jexplatform.logging.JExLogger;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Mint and redeem orchestration for coupons. Minting builds an item with a fresh UUID and touches no database row.
 *
 * <p>Redeeming:
 * <ol>
 *   <li>Insert a {@link RedeemedCoupon} row keyed by the coupon UUID. Its unique constraint is the duplicate guard:
 *       a unique violation means the coupon was already redeemed ({@link Status#ALREADY_REDEEMED}); any other insert
 *       failure is logged and reported as {@link Status#ERROR}, the coupon stays unspent.</li>
 *   <li>Deposit every payout one after the other. When one fails, the payouts already booked are withdrawn again
 *       and the row is deleted, so the coupon can be redeemed later ({@link Status#ERROR}). When that undo fails as
 *       well, the row stays (no second payout) and the case is logged for manual compensation.</li>
 * </ol>
 *
 * @author JExcellence
 * @since 3.0.0
 */
public final class CouponService {

    private static final String REDEEM_REASON = "coupon-redeem:";
    private static final String UNDO_REASON = "coupon-undo:";

    private final RedeemedCouponRepository repo;
    private final CouponTemplateRegistry registry;
    private final EconomyService economy;
    private final JExLogger logger;

    /** One redemption in progress. */
    private record Redemption(@NotNull Player player, @NotNull String couponId, @NotNull CouponTemplate template,
                              @NotNull RedeemedCoupon row) {
    }

    /**
     * Creates the service.
     *
     * @param repo     the redemption repository
     * @param registry the coupon templates
     * @param economy  the economy provider for payouts
     * @param logger   the plugin logger
     */
    public CouponService(@NotNull RedeemedCouponRepository repo,
                         @NotNull CouponTemplateRegistry registry,
                         @NotNull EconomyService economy,
                         @NotNull JExLogger logger) {
        this.repo = repo;
        this.registry = registry;
        this.economy = economy;
        this.logger = logger;
    }

    /**
     * Attempts to redeem a coupon for the player.
     *
     * @param player     the redeeming player
     * @param couponId   the coupon UUID from the item
     * @param templateId the template id from the item
     * @param issuer     who issued the coupon, may be {@code null}
     * @return future resolving to the outcome
     */
    public @NotNull CompletableFuture<RedeemResult> redeem(@NotNull Player player,
                                                           @NotNull String couponId,
                                                           @NotNull String templateId,
                                                           @Nullable UUID issuer) {
        final CouponTemplate template = this.registry.get(templateId);
        if (template == null) {
            return CompletableFuture.completedFuture(new RedeemResult(Status.TEMPLATE_MISSING, templateId, null));
        }
        final RedeemedCoupon row = new RedeemedCoupon(couponId, templateId, player.getUniqueId(), issuer);
        return this.repo.createAsync(row)
                .handle((saved, failure) -> failure == null ? null : insertFailure(couponId, failure))
                .thenCompose(refused -> refused != null
                        ? CompletableFuture.completedFuture(new RedeemResult(refused, templateId, null))
                        : payOut(new Redemption(player, couponId, template, row)));
    }

    private @NotNull Status insertFailure(@NotNull String couponId, @NotNull Throwable failure) {
        if (PersistenceFailures.isUniqueViolation(failure)) {
            return Status.ALREADY_REDEEMED;
        }
        this.logger.error("Coupon {} could not be marked as redeemed, nothing was paid: {}", couponId,
                failure.getMessage(), failure);
        return Status.ERROR;
    }

    private @NotNull CompletableFuture<RedeemResult> payOut(@NotNull Redemption redemption) {
        List<PaidPayout> paid = new ArrayList<>();
        CompletableFuture<Boolean> chain = CompletableFuture.completedFuture(true);
        for (final var payout : redemption.template().payouts()) {
            chain = chain.thenCompose(allPaid -> Boolean.TRUE.equals(allPaid)
                    ? depositOne(redemption, payout, paid)
                    : CompletableFuture.completedFuture(false));
        }
        String templateId = redemption.template().id();
        return chain.exceptionally(failure -> false).thenCompose(allPaid -> Boolean.TRUE.equals(allPaid)
                ? CompletableFuture.completedFuture(new RedeemResult(Status.OK, templateId, redemption.template()))
                : undo(redemption, paid).thenApply(ignored -> new RedeemResult(Status.ERROR, templateId, null)));
    }

    private @NotNull CompletableFuture<Boolean> depositOne(@NotNull Redemption redemption,
                                                           @NotNull CouponTemplate.CouponPayout payout,
                                                           @NotNull List<PaidPayout> paid) {
        return this.economy.depositReversible(redemption.player(), payout.currency(), payout.amount(),
                        redemption.player(), REDEEM_REASON + redemption.template().id())
                .thenApply(receipt -> {
                    if (receipt.result().isSuccess()) {
                        paid.add(new PaidPayout(payout, receipt));
                        return true;
                    }
                    this.logger.warn("Coupon {} payout of {} {} failed: {}", redemption.couponId(), payout.amount(),
                            payout.currency(), receipt.result().error());
                    return false;
                });
    }

    private @NotNull CompletableFuture<Void> undo(@NotNull Redemption redemption, @NotNull List<PaidPayout> paid) {
        String reason = UNDO_REASON + redemption.template().id();
        List<CompletableFuture<Boolean>> refunds = paid.stream()
                .map(done -> this.economy.reverse(redemption.player(), done.payout().currency(), done.receipt(),
                                reason)
                        .exceptionally(failure -> false))
                .toList();
        return CompletableFuture.allOf(refunds.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> refunds.stream().allMatch(refund -> Boolean.TRUE.equals(refund.join())))
                .thenCompose(undone -> Boolean.TRUE.equals(undone)
                        ? releaseRow(redemption)
                        : keepRow(redemption, paid));
    }

    private @NotNull CompletableFuture<Void> releaseRow(@NotNull Redemption redemption) {
        Long id = redemption.row().getId();
        if (id == null) {
            return CompletableFuture.completedFuture(null);
        }
        return this.repo.deleteAsync(id).exceptionally(failure -> {
            this.logger.error("Coupon {} stays marked as redeemed although nothing was paid: {}",
                    redemption.couponId(), failure.getMessage());
            return null;
        });
    }

    private @NotNull CompletableFuture<Void> keepRow(@NotNull Redemption redemption,
                                                     @NotNull List<PaidPayout> paid) {
        this.logger.error("Coupon {} of {} was paid only in part and the paid part could not be taken back ({}). "
                        + "The coupon stays redeemed; compensate manually.", redemption.couponId(),
                redemption.player().getName(), paid.stream().map(PaidPayout::payout).toList());
        return CompletableFuture.completedFuture(null);
    }

    private record PaidPayout(@NotNull CouponTemplate.CouponPayout payout, @NotNull WalletService.Receipt receipt) {
    }

    /** Outcome of a redeem attempt. */
    public enum Status {
        OK,
        ALREADY_REDEEMED,
        TEMPLATE_MISSING,
        ERROR
    }

    /**
     * The result of a redeem attempt.
     *
     * @param status     outcome of the redeem
     * @param templateId template the coupon referenced
     * @param template   resolved template, only non-null on {@link Status#OK}
     */
    public record RedeemResult(@NotNull Status status,
                               @NotNull String templateId,
                               @Nullable CouponTemplate template) {
    }
}
