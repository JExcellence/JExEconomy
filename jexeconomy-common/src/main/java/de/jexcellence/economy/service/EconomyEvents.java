package de.jexcellence.economy.service;

import de.jexcellence.economy.concurrent.ServerThreads;
import de.jexcellence.jexplatform.scheduler.PlatformScheduler;
import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dispatches the economy's Bukkit events on the main thread, on Folia on the global region thread.
 *
 * <p>Pre-events ({@link #fireCancellable(Event, BooleanSupplier)}) block the calling worker until that thread ran the
 * listeners, so a listener can still cancel the change. Situations that must not block:
 * <ul>
 *   <li>the caller already is that thread: the event fires directly;</li>
 *   <li>the caller says that thread waits for this very change (a synchronous Vault call on the same account): the
 *       pre-event is skipped and the change counts as not cancelled;</li>
 *   <li>the plugin is disabling ({@link #stopMainThreadDispatch()}): pre-events of the changes that still drain are
 *       skipped and count as not cancelled.</li>
 * </ul>
 * A worker that already waits re-checks these states every 100 ms.
 * Post-events ({@link #fire(Event)}) are always scheduled and never block; once the plugin is disabling they are
 * dropped, because a disabled plugin can no longer schedule tasks.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public final class EconomyEvents {

    /** Longest wait of a worker for the main thread to run a pre-event. */
    public static final long DISPATCH_TIMEOUT_SECONDS = 30L;

    private static final Logger LOGGER = Logger.getLogger(EconomyEvents.class.getName());

    private static final long SLICE_MILLIS = 100L;

    private final PlatformScheduler scheduler;
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    /**
     * Creates the dispatcher.
     *
     * @param scheduler the platform scheduler for main-thread hand-offs
     */
    public EconomyEvents(@NotNull PlatformScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * Stops waiting for the main thread: from now on pre-events are skipped and their changes count as not
     * cancelled. Called when the plugin disables, because the main thread is busy in {@code onDisable} and could
     * never run the listeners, while the changes already queued must still complete.
     */
    public void stopMainThreadDispatch() {
        shuttingDown.set(true);
    }

    /**
     * Fires a pre-event and waits for its listeners, see the class documentation for the non-blocking cases.
     *
     * @param event the event
     * @return {@code true} when a listener cancelled it
     */
    public boolean fireCancellable(@NotNull Event event) {
        return fireCancellable(event, () -> false);
    }

    /**
     * Fires a pre-event and waits for its listeners unless the dispatching thread is blocked.
     *
     * @param event           the event
     * @param dispatchBlocked {@code true} while the thread that would run the listeners waits for this change
     * @return {@code true} when a listener cancelled it
     */
    public boolean fireCancellable(@NotNull Event event, @NotNull BooleanSupplier dispatchBlocked) {
        if (ServerThreads.onGlobalThread()) {
            Bukkit.getPluginManager().callEvent(event);
            return isCancelled(event);
        }
        if (mainThreadUnavailable(dispatchBlocked)) {
            return false;
        }
        return switch (dispatchAndWait(event, dispatchBlocked)) {
            case RAN -> isCancelled(event);
            case SKIPPED -> false;
            case REFUSED -> true;
            default -> throw new IllegalStateException("Unexpected dispatch outcome");
        };
    }

    private static boolean isCancelled(@NotNull Event event) {
        return event instanceof Cancellable cancellable && cancellable.isCancelled();
    }

    private boolean mainThreadUnavailable(@NotNull BooleanSupplier dispatchBlocked) {
        return shuttingDown.get() || dispatchBlocked.getAsBoolean();
    }

    /**
     * Runs the listeners on the main thread and waits in short slices, at most {@value #DISPATCH_TIMEOUT_SECONDS}
     * seconds in total. Between the slices the main-thread state is checked again: when the main thread starts to
     * wait for this change or the plugin stops while this worker waits, the pre-event is skipped instead of blocking
     * both sides until the timeout.
     */
    private @NotNull Dispatch dispatchAndWait(@NotNull Event event, @NotNull BooleanSupplier dispatchBlocked) {
        var dispatched = new CompletableFuture<Void>();
        scheduler.runSync(() -> {
            try {
                Bukkit.getPluginManager().callEvent(event);
                dispatched.complete(null);
            } catch (RuntimeException failure) {
                dispatched.completeExceptionally(failure);
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DISPATCH_TIMEOUT_SECONDS);
        try {
            while (System.nanoTime() < deadline) {
                if (awaitSlice(dispatched)) {
                    return Dispatch.RAN;
                }
                if (mainThreadUnavailable(dispatchBlocked)) {
                    return Dispatch.SKIPPED;
                }
            }
            LOGGER.log(Level.WARNING, () -> event.getEventName() + " was not dispatched within "
                    + DISPATCH_TIMEOUT_SECONDS + " s, the change is refused");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException failure) {
            LOGGER.log(Level.WARNING, () -> event.getEventName() + " failed, the change is refused: "
                    + failure.getMessage());
        }
        return Dispatch.REFUSED;
    }

    private static boolean awaitSlice(@NotNull CompletableFuture<Void> dispatched)
            throws InterruptedException, ExecutionException {
        try {
            dispatched.get(SLICE_MILLIS, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException sliceOver) {
            return false;
        }
    }

    private enum Dispatch {
        RAN,
        SKIPPED,
        REFUSED
    }

    /**
     * Fires a post-event on the main thread without waiting; does nothing once the plugin is disabling.
     *
     * @param event the event
     */
    public void fire(@NotNull Event event) {
        if (shuttingDown.get()) {
            return;
        }
        scheduler.runSync(() -> Bukkit.getPluginManager().callEvent(event));
    }
}
