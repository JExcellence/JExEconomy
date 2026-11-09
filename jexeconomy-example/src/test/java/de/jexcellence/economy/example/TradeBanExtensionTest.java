package de.jexcellence.economy.example;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeBanExtensionTest {

    private static final UUID BANNED = new UUID(1L, 1L);
    private static final UUID FREE = new UUID(1L, 2L);
    private final TradeBanExtension extension = new TradeBanExtension(Set.of(BANNED),
            new TradeBanExtension.Texts("Trading", "Status", "Allowed", "Banned"));

    @Test
    void aBanWorksInBothDirections() {
        assertTrue(extension.isTradeBlocked(BANNED, FREE));
        assertTrue(extension.isTradeBlocked(FREE, BANNED));
        assertFalse(extension.isTradeBlocked(FREE, new UUID(1L, 3L)));
    }

    @Test
    void theCardShowsThePlayersState() {
        assertEquals("Banned", extension.leaderboardCard(BANNED, "en_us").orElseThrow().rows().getFirst().value());
        assertEquals("Allowed", extension.leaderboardCard(FREE, "en_us").orElseThrow().rows().getFirst().value());
    }
}
