package de.jexcellence.economy.service;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the failure reasons: every reason has a translation in English and German, the API error texts stay the
 * ones consumers already see, and an error text resolves back to its reason.
 */
class FailureReasonTest {

    private static final String[] LOCALES = {"en_US", "de_DE"};
    private static final Pattern KEY_LINE = Pattern.compile("^( *)([A-Za-z0-9_-]+):(.*)$");

    @Test
    void everyReasonIsTranslated() throws IOException {
        for (String locale : LOCALES) {
            assertTranslated(locale);
        }
    }

    private static void assertTranslated(@NotNull String locale) throws IOException {
        Set<String> keys = translationKeys("translations/" + locale + ".yml");
        for (FailureReason reason : FailureReason.values()) {
            assertTrue(keys.contains(reason.translationKey()), locale + " misses " + reason.translationKey());
        }
        assertTrue(keys.contains("economy-v2.item.redeem.busy"), locale + " misses the redeem busy line");
    }

    @Test
    void apiTextsStayUnchanged() {
        assertEquals("Insufficient funds", FailureReason.INSUFFICIENT_FUNDS.text());
        assertEquals("Currency 'gems' not found", FailureReason.CURRENCY_NOT_FOUND.text("gems"));
        assertEquals("Transfer already in progress", RoutedTransfer.ERROR_IN_FLIGHT);
        assertEquals("Amount must be positive", FailureReason.AMOUNT_NOT_POSITIVE.text());
    }

    @Test
    void errorTextsResolveToTheirReason() {
        assertEquals(Optional.of(FailureReason.INSUFFICIENT_FUNDS), FailureReason.of("Insufficient funds"));
        assertEquals(Optional.of(FailureReason.CURRENCY_NOT_FOUND), FailureReason.of("Currency 'season_points' not found"));
        assertEquals(Optional.of(FailureReason.NOT_PAYABLE), FailureReason.of("Currency 'crystals' cannot be transferred"));
        assertEquals(Optional.of(FailureReason.TRANSFER_FAILED), FailureReason.of("Transfer failed: Insufficient funds"));
        assertEquals(Optional.of(FailureReason.PARTIAL_DEPOSIT),
                FailureReason.of(FailureReason.PARTIAL_DEPOSIT.text(40L, "40.50")));
    }

    @Test
    void freeTextHasNoReason() {
        assertTrue(FailureReason.of("Shop is closed today").isEmpty());
        assertTrue(FailureReason.of(null).isEmpty());
        assertTrue(FailureReason.of(" ").isEmpty());
    }

    /** Collects the dotted paths of every key line, following the two-space indentation of the files. */
    private static @NotNull Set<String> translationKeys(@NotNull String resource) throws IOException {
        Set<String> keys = new HashSet<>();
        Deque<String> path = new ArrayDeque<>();
        Deque<Integer> indents = new ArrayDeque<>();
        try (InputStream input = FailureReasonTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource + " is missing");
            for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                Matcher matcher = KEY_LINE.matcher(line);
                if (line.isBlank() || line.trim().startsWith("#") || !matcher.matches()) {
                    continue;
                }
                int indent = matcher.group(1).length();
                while (!indents.isEmpty() && indents.peek() >= indent) {
                    indents.pop();
                    path.pop();
                }
                indents.push(indent);
                path.push(matcher.group(2));
                keys.add(String.join(".", path.reversed()));
            }
        }
        return keys;
    }
}
