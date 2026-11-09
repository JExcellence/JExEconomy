package de.jexcellence.economy.concurrent;

import org.bukkit.Bukkit;

import java.util.function.BooleanSupplier;

/**
 * Thread checks that work on every supported server. Paper and Folia know a global tick thread. Spigot has only the
 * main thread, which plays the same role there.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public final class ServerThreads {

    private static final BooleanSupplier GLOBAL_THREAD = hasGlobalTickThread()
            ? Bukkit::isGlobalTickThread
            : Bukkit::isPrimaryThread;

    private ServerThreads() {
    }

    /**
     * Whether the caller runs on the thread that dispatches global work.
     *
     * @return {@code true} on the global tick thread, or on Spigot the main thread
     */
    public static boolean onGlobalThread() {
        return GLOBAL_THREAD.getAsBoolean();
    }

    private static boolean hasGlobalTickThread() {
        try {
            Bukkit.class.getMethod("isGlobalTickThread");
            return true;
        } catch (NoSuchMethodException spigot) {
            return false;
        }
    }
}
