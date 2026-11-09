package de.jexcellence.economy.command;

import com.raindropcentral.commands.v2.argument.ArgumentType;
import de.jexcellence.economy.coupon.CouponTemplate;
import de.jexcellence.economy.coupon.CouponTemplateRegistry;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Map;

/**
 * JExCommand 2.0 argument type resolving raw tokens into
 * {@link CouponTemplate} entries via the in-memory
 * {@link CouponTemplateRegistry}.
 *
 * <p>YAML schema uses the id {@code coupon_template}:
 * <pre>
 * argumentSchema:
 *   - { name: template, type: coupon_template, required: true }
 * </pre>
 *
 * <p>On failure, emits the i18n key
 * {@code coupon.error.template-not-found} with the placeholder
 * {@code {template}} equal to the raw token.
 */
public final class CouponTemplateArgumentType {

    private CouponTemplateArgumentType() {
    }

    public static @NotNull ArgumentType<CouponTemplate> of(@NotNull CouponTemplateRegistry registry) {
        return ArgumentType.custom(
                "coupon_template",
                CouponTemplate.class,
                (sender, raw) -> {
                    final CouponTemplate template = registry.get(raw);
                    if (template != null) return ArgumentType.ParseResult.ok(template);
                    return ArgumentType.ParseResult.err(
                            "coupon.error.template-not-found",
                            Map.of("template", raw));
                },
                (sender, partial) -> {
                    final String lower = partial.toLowerCase(Locale.ROOT);
                    return registry.all().stream()
                            .map(CouponTemplate::id)
                            .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(lower))
                            .sorted()
                            .toList();
                });
    }
}
