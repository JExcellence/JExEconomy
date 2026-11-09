package de.jexcellence.economy.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Stored bytes the codec does not recognise stay unread instead of becoming a wrong item.
 */
class ItemCodecTest {

    @Test
    void emptyDataIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ItemCodec.decode(new byte[0]));
    }

    @Test
    void anUnknownFormatIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ItemCodec.decode(new byte[]{9, 1, 2}));
    }
}
