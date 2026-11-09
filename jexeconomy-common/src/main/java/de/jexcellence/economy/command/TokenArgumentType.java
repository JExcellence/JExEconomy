package de.jexcellence.economy.command;

import com.raindropcentral.commands.v2.argument.ArgumentType;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * JExCommand 2.0 argument type for a fixed set of values: an enum whose constants are typed by a lower-case token.
 * Parsing ignores case; tab completion offers the tokens that start with the typed text, in declaration order. An
 * unknown token reports {@code jexcommand.error.invalid-enum} with every valid token.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public final class TokenArgumentType {

    private static final String ERROR_KEY = "jexcommand.error.invalid-enum";

    private TokenArgumentType() {
    }

    /**
     * Builds the argument type.
     *
     * @param id    the type id used in the command YAML
     * @param type  the enum class
     * @param token the token a player types for a constant
     * @param <E>   the enum type
     * @return the argument type
     */
    public static <E extends Enum<E>> @NotNull ArgumentType<E> of(@NotNull String id, @NotNull Class<E> type,
                                                                 @NotNull Function<E, String> token) {
        List<E> constants = Arrays.asList(type.getEnumConstants());
        String options = String.join(", ", constants.stream().map(token).toList());
        return ArgumentType.custom(id, type,
                (sender, raw) -> constants.stream()
                        .filter(constant -> token.apply(constant).equalsIgnoreCase(raw))
                        .findFirst()
                        .map(ArgumentType.ParseResult::ok)
                        .orElseGet(() -> ArgumentType.ParseResult.err(ERROR_KEY,
                                Map.of("value", raw, "options", options))),
                (sender, partial) -> {
                    String lower = partial.toLowerCase(Locale.ROOT);
                    return constants.stream().map(token).filter(value -> value.startsWith(lower)).toList();
                });
    }
}
