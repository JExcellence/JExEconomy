package de.jexcellence.economy.database;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerNamesTest {

    private static final UUID PLAYER = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

    @Test
    void namesAreCutToTheColumnLength() {
        assertEquals("Steve", PlayerNames.clamp("Steve"));
        assertEquals(".BedrockPlayer12", PlayerNames.clamp(".BedrockPlayer123"));
        assertEquals(PlayerNames.MAX_LENGTH, PlayerNames.clamp(PLAYER.toString()).length());
    }

    @Test
    void unknownNamesUseTheUuidFallback() {
        assertEquals("0f8fad5b-d9cb-46", PlayerNames.storable(PLAYER, null));
        assertEquals("0f8fad5b-d9cb-46", PlayerNames.storable(PLAYER, " "));
        assertEquals("Alex", PlayerNames.storable(PLAYER, "Alex"));
    }

    @Test
    void uniqueIndexMatchesExactColumnsOnly() {
        Set<String> wanted = Set.of("player_id", "currency_id");

        assertTrue(UniqueIndexes.matches(Map.of("UK_X", Set.of("currency_id", "player_id")), wanted));
        assertFalse(UniqueIndexes.matches(Map.of("PRIMARY", Set.of("id")), wanted));
        assertFalse(UniqueIndexes.matches(Map.of("WIDE", Set.of("player_id", "currency_id", "balance")), wanted));
    }
}
