package de.jexcellence.economy.command.player.currencies;

import com.raindropcentral.commands.v2.argument.ArgumentType;
import de.jexcellence.economy.command.TokenArgumentType;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * The currency fields {@code /currencies edit} can change. The token is the field id the views and translations
 * use ({@code economy-v2.field.<id>.label}).
 *
 * @author JExcellence
 * @since 3.1.0
 */
public enum CurrencyField {

    IDENTIFIER,
    SYMBOL,
    PREFIX,
    SUFFIX,
    ICON;

    /** Type id of the command argument. */
    public static final String ARGUMENT_TYPE = "currency_field";

    /**
     * The field id, for example {@code symbol}.
     *
     * @return the lower-case field id
     */
    public @NotNull String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The command argument type with tab completion of every field id.
     *
     * @return the argument type
     */
    public static @NotNull ArgumentType<CurrencyField> argumentType() {
        return TokenArgumentType.of(ARGUMENT_TYPE, CurrencyField.class, CurrencyField::id);
    }
}
