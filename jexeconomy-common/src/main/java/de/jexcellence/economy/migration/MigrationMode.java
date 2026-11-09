package de.jexcellence.economy.migration;

import com.raindropcentral.commands.v2.argument.ArgumentType;
import de.jexcellence.economy.command.TokenArgumentType;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * What an import does to a balance that already exists.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public enum MigrationMode {

    /** Imports only into empty accounts with no history. */
    SKIP_EXISTING,
    /** Replaces the balance. */
    OVERWRITE,
    /** Adds to the balance. */
    ADD;

    /** Type id of the command argument. */
    public static final String ARGUMENT_TYPE = "migration_mode";

    /**
     * The token typed in the command.
     *
     * @return the lower-case token
     */
    public @NotNull String token() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * The command argument type with tab completion of every mode.
     *
     * @return the argument type
     */
    public static @NotNull ArgumentType<MigrationMode> argumentType() {
        return TokenArgumentType.of(ARGUMENT_TYPE, MigrationMode.class, MigrationMode::token);
    }
}
