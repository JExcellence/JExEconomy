package de.jexcellence.economy.text;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The client language code becomes a locale without Paper's {@code Player.locale()}.
 */
class PlayerLocaleTest {

    @Test
    @SuppressWarnings("deprecation")
    void theClientLanguageCodeBecomesALocale() {
        Player player = mock(Player.class);
        when(player.getLocale()).thenReturn("de_de");

        assertEquals(Locale.GERMANY, PlayerLocale.of(player));
    }

    @Test
    @SuppressWarnings("deprecation")
    void aMissingLanguageCodeFallsBackToTheRootLocale() {
        Player player = mock(Player.class);
        when(player.getLocale()).thenReturn("");

        assertEquals(Locale.ROOT, PlayerLocale.of(player));
    }
}
