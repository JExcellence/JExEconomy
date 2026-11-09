package de.jexcellence.economy.command.admin.economy;

import com.raindropcentral.commands.v2.argument.ArgumentType;
import de.jexcellence.economy.command.TokenArgumentType;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * The steps of {@code /economy migrate}.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public enum MigrateAction {

    SOURCES,
    PLAN,
    START,
    PAUSE,
    RESUME,
    STATUS,
    ROLLBACK;

    /** Type id of the command argument. */
    public static final String ARGUMENT_TYPE = "migrate_action";

    /**
     * The token typed after {@code /economy migrate}.
     *
     * @return the lower-case token
     */
    public @NotNull String token() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The command argument type with tab completion of every action.
     *
     * @return the argument type
     */
    public static @NotNull ArgumentType<MigrateAction> argumentType() {
        return TokenArgumentType.of(ARGUMENT_TYPE, MigrateAction.class, MigrateAction::token);
    }
}
