package de.jexcellence.economy.service;

import de.jexcellence.economy.api.TransactionResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Why an economy operation failed. Each reason keeps the English text that {@link TransactionResult#error()} has
 * always carried, so API consumers that log or compare it see no change, and maps to a translation key under
 * {@code economy-v2.failure.} for player-facing output. A free-text error (for example the reason a
 * {@code BalanceChangeEvent} listener gave) has no reason and is shown as it is.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public enum FailureReason {

    CURRENCY_NOT_FOUND("Currency '%s' not found"),
    AMOUNT_NOT_POSITIVE("Amount must be positive"),
    BALANCE_NEGATIVE("Balance cannot be negative"),
    INSUFFICIENT_FUNDS("Insufficient funds"),
    BALANCE_CHANGED("Balance changed"),
    CANCELLED("Cancelled by event"),
    NOT_PAYABLE("Currency '%s' cannot be transferred"),
    TRADE_BLOCKED("Trade blocked"),
    TRANSFER_IN_FLIGHT("Transfer already in progress"),
    BANK_DENIED("Bank withdraw not permitted"),
    RECIPIENT_BANK_FULL("Recipient bank full"),
    WHOLE_COINS("Whole coins only"),
    TRANSFER_FAILED("Transfer failed"),
    RESET_FAILED("Reset failed"),
    /** Not returned any more: a failed wallet part takes the external share back, see {@link WalletService}. */
    PARTIAL_DEPOSIT("Deposit only partly booked (%s of %s)"),
    STORAGE_FAILED("Storage error");

    /** Translation key prefix of every reason. */
    public static final String KEY_PREFIX = "economy-v2.failure.";

    private static final String ARGUMENT = "%s";

    private final String text;
    private final Pattern pattern;

    FailureReason(@NotNull String text) {
        this.text = text;
        this.pattern = Pattern.compile(Arrays.stream(text.split(ARGUMENT, -1))
                .map(Pattern::quote)
                .collect(Collectors.joining(".+?")) + "(?:: .*)?", Pattern.DOTALL);
    }

    /**
     * The API error text.
     *
     * @param arguments values for the {@code %s} slots of the text, in order
     * @return the English error text
     */
    public @NotNull String text(@NotNull Object... arguments) {
        return arguments.length == 0 ? text : String.format(Locale.ROOT, text, arguments);
    }

    /**
     * The translation key of this reason, for example {@code economy-v2.failure.insufficient-funds}.
     *
     * @return the translation key
     */
    public @NotNull String translationKey() {
        return KEY_PREFIX + name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * A failed result with this reason.
     *
     * @param amount    the attempted amount
     * @param balance   the unchanged balance
     * @param arguments values for the text, see {@link #text(Object...)}
     * @return the failure result
     */
    public @NotNull TransactionResult result(double amount, double balance, @NotNull Object... arguments) {
        return TransactionResult.failure(amount, balance, text(arguments));
    }

    /**
     * Resolves the reason behind an API error text. An error with a detail suffix ({@code "Transfer failed: ..."})
     * resolves to its reason.
     *
     * @param error the error of a {@link TransactionResult}, may be {@code null}
     * @return the reason, or empty for a free-text error
     */
    public static @NotNull Optional<FailureReason> of(@Nullable String error) {
        if (error == null || error.isBlank()) {
            return Optional.empty();
        }
        for (FailureReason reason : values()) {
            if (reason.pattern.matcher(error).matches()) {
                return Optional.of(reason);
            }
        }
        return Optional.empty();
    }
}
