package de.jexcellence.economy.item;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;

/**
 * Turns an item into bytes for storage. Paper and Folia use the native item format, Spigot has only Bukkit's object
 * stream. The first byte names the format, so an item stored on Spigot can still be read after a move to Paper.
 *
 * @author JExcellence
 * @since 3.2.0
 */
final class ItemCodec {

    private static final byte NATIVE_FORMAT = 0;
    private static final byte BUKKIT_STREAM_FORMAT = 1;
    private static final boolean NATIVE_AVAILABLE = hasNativeFormat();

    private ItemCodec() {
    }

    static byte @NotNull [] encode(@NotNull ItemStack item) {
        return NATIVE_AVAILABLE
                ? withFormat(NATIVE_FORMAT, item.serializeAsBytes())
                : withFormat(BUKKIT_STREAM_FORMAT, writeStream(item));
    }

    static @NotNull ItemStack decode(byte @NotNull [] data) {
        if (data.length == 0) {
            throw new IllegalArgumentException("Stored item is empty");
        }
        byte[] body = Arrays.copyOfRange(data, 1, data.length);
        return switch (data[0]) {
            case NATIVE_FORMAT -> readNative(body);
            case BUKKIT_STREAM_FORMAT -> readStream(body);
            default -> throw new IllegalArgumentException("Unknown stored item format " + data[0]);
        };
    }

    private static @NotNull ItemStack readNative(byte @NotNull [] body) {
        if (!NATIVE_AVAILABLE) {
            throw new IllegalArgumentException("Stored item uses the Paper format, which this server cannot read");
        }
        return ItemStack.deserializeBytes(body);
    }

    private static byte @NotNull [] withFormat(byte format, byte @NotNull [] body) {
        byte[] data = new byte[body.length + 1];
        data[0] = format;
        System.arraycopy(body, 0, data, 1, body.length);
        return data;
    }

    private static byte @NotNull [] writeStream(@NotNull ItemStack item) {
        var bytes = new ByteArrayOutputStream();
        try (var stream = new BukkitObjectOutputStream(bytes)) {
            stream.writeObject(item);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return bytes.toByteArray();
    }

    private static @NotNull ItemStack readStream(byte @NotNull [] body) {
        try (var stream = new BukkitObjectInputStream(new ByteArrayInputStream(body))) {
            return (ItemStack) stream.readObject();
        } catch (IOException | ClassNotFoundException failure) {
            throw new IllegalArgumentException("Stored item cannot be read", failure);
        }
    }

    private static boolean hasNativeFormat() {
        try {
            ItemStack.class.getMethod("serializeAsBytes");
            return true;
        } catch (NoSuchMethodException spigot) {
            return false;
        }
    }
}
