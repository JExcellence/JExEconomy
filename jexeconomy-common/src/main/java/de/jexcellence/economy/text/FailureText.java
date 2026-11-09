package de.jexcellence.economy.text;

import de.jexcellence.economy.service.FailureReason;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Turns the error of a failed economy result into the viewer's language: a known {@link FailureReason} uses its
 * {@code economy-v2.failure.*} translation, a free-text error (for example a listener's cancellation reason) is shown
 * as it is, and a missing error becomes {@code economy-v2.error.unknown}.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public final class FailureText {

    private static final String UNKNOWN_KEY = EconomyText.ROOT + "error.unknown";

    private FailureText() {
    }

    /**
     * The plain error text for a viewer.
     *
     * @param viewer the viewer, {@code null} for the console
     * @param error  the result error, may be {@code null}
     * @return the text for a {@code {error}} placeholder
     */
    public static @NotNull String of(@Nullable Player viewer, @Nullable String error) {
        if (error == null || error.isBlank()) {
            return EconomyText.msg(UNKNOWN_KEY).plain(viewer);
        }
        return FailureReason.of(error)
                .map(reason -> EconomyText.msg(reason.translationKey()).plain(viewer))
                .orElse(error);
    }
}
