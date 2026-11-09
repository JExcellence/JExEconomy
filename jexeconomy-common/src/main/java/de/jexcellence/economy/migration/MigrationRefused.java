package de.jexcellence.economy.migration;

import org.jetbrains.annotations.NotNull;

/**
 * An import step that was refused for a reason the admin can act on.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public final class MigrationRefused extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String reason;

    /**
     * Creates the refusal.
     *
     * @param reason the last part of the translation key {@code migration.error.<reason>}
     */
    public MigrationRefused(@NotNull String reason) {
        super(reason);
        this.reason = reason;
    }

    /**
     * Why the step was refused.
     *
     * @return the reason
     */
    public @NotNull String reason() {
        return reason;
    }
}
