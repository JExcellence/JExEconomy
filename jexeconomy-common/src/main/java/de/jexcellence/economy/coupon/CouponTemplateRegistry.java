package de.jexcellence.economy.coupon;

import de.jexcellence.jexplatform.logging.JExLogger;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads {@link CouponTemplate} definitions from
 * {@code plugins/JExEconomy/coupons.yml} and serves them up by
 * identifier. Reload-friendly - call {@link #reload(JavaPlugin)} to
 * refresh after editing the YAML.
 *
 * <p>The YAML schema is:
 * <pre>{@code
 * coupons:
 *   starter_pack:
 *     name: "<gradient:...>Starter Pack</gradient>"
 *     lore:
 *       - "<gray>A welcome gift."
 *     icon: PAPER
 *     glint: true
 *     payouts:
 *       coins: 500
 * }</pre>
 */
public final class CouponTemplateRegistry {

    private final Map<String, CouponTemplate> templates = new LinkedHashMap<>();
    private final JExLogger logger;

    public CouponTemplateRegistry(@NotNull JExLogger logger) {
        this.logger = logger;
    }

    public void reload(@NotNull JavaPlugin plugin) {
        this.templates.clear();
        final File file = new File(plugin.getDataFolder(), "coupons.yml");
        if (!file.exists()) {
            plugin.saveResource("coupons.yml", false);
        }
        if (!file.exists()) {
            this.logger.warn("coupons.yml missing and not shipped as a resource - no templates loaded");
            return;
        }
        final var cfg = YamlConfiguration.loadConfiguration(file);
        final var section = cfg.getConfigurationSection("coupons");
        if (section == null) {
            this.logger.warn("coupons.yml has no `coupons:` root section - no templates loaded");
            return;
        }
        for (final String id : section.getKeys(false)) {
            final var spec = section.getConfigurationSection(id);
            if (spec == null) continue;
            try {
                final CouponTemplate template = parse(id, spec);
                this.templates.put(id, template);
            } catch (final RuntimeException ex) {
                this.logger.error("coupon template {} failed to load: {}", id, ex.getMessage());
            }
        }
        this.logger.info("Coupon templates loaded: {}", this.templates.size());
    }

    private static @NotNull CouponTemplate parse(@NotNull String id,
                                                  @NotNull ConfigurationSection spec) {
        final String displayName = spec.getString("name", id);
        final List<String> lore = spec.getStringList("lore");
        final String iconName = spec.getString("icon", "PAPER");
        final Material icon = parseMaterial(iconName);
        final boolean glint = spec.getBoolean("glint", false);

        final List<CouponTemplate.CouponPayout> payouts = new ArrayList<>();
        final var payoutSection = spec.getConfigurationSection("payouts");
        if (payoutSection != null) {
            for (final String currency : payoutSection.getKeys(false)) {
                final double amount = payoutSection.getDouble(currency);
                if (amount <= 0) continue;
                payouts.add(new CouponTemplate.CouponPayout(currency, amount));
            }
        }
        final List<String> commands = spec.getStringList("commands");
        return new CouponTemplate(id, displayName, List.copyOf(lore), icon, glint,
                List.copyOf(payouts), List.copyOf(commands));
    }

    private static @NotNull Material parseMaterial(@NotNull String name) {
        try {
            return Material.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException ex) {
            return Material.PAPER;
        }
    }

    public @Nullable CouponTemplate get(@NotNull String id) {
        return this.templates.get(id);
    }

    public @NotNull Collection<CouponTemplate> all() {
        return Collections.unmodifiableCollection(this.templates.values());
    }
}
