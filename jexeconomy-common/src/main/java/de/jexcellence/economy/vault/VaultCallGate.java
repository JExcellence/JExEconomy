package de.jexcellence.economy.vault;

import de.jexcellence.economy.concurrent.ServerThreads;
import de.jexcellence.jexplatform.logging.JExLogger;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Limits how long Vault's synchronous contract can hold a thread.
 *
 * <p>Every wait ends after {@link Timing#waitLimit()}. After a timeout on a server thread, further calls from server
 * threads are refused for {@link Timing#cooldown()} without touching the database, so an outage does not turn into
 * one full wait per call and stall the server. Calls from other threads keep waiting. Waits on a server thread
 * longer than {@link Timing#slowCall()} are logged, at most once per {@link Timing#warnInterval()}.
 *
 * @author JExcellence
 * @since 3.2.0
 */
final class VaultCallGate {

    private static final long NEVER = Long.MIN_VALUE;

    private final Threads threads;
    private final Timing timing;
    private final JExLogger logger;
    private final AtomicLong refusedUntil = new AtomicLong(NEVER);
    private final AtomicLong nextWarning = new AtomicLong(NEVER);
    private final AtomicInteger slowSinceWarning = new AtomicInteger();

    /**
     * How the caller's thread is classified.
     *
     * @param onServerThread   {@code true} on any thread that ticks the world (main thread, Folia regions)
     * @param onDispatchThread {@code true} on the thread that runs the economy's pre-events
     */
    record Threads(@NotNull BooleanSupplier onServerThread, @NotNull BooleanSupplier onDispatchThread) {

        static @NotNull Threads ofServer() {
            return new Threads(Bukkit::isPrimaryThread, ServerThreads::onGlobalThread);
        }
    }

    /**
     * The limits of the gate.
     *
     * @param waitLimit    longest wait of a call
     * @param slowCall     a server-thread wait above this is reported
     * @param cooldown     how long server-thread calls are refused after a timeout
     * @param warnInterval shortest time between two slow-call warnings
     */
    record Timing(@NotNull Duration waitLimit, @NotNull Duration slowCall, @NotNull Duration cooldown,
                  @NotNull Duration warnInterval) {

        static final Timing DEFAULTS = new Timing(Duration.ofSeconds(2L), Duration.ofMillis(50L),
                Duration.ofSeconds(5L), Duration.ofSeconds(10L));
    }

    VaultCallGate(@NotNull Threads threads, @NotNull Timing timing, @NotNull JExLogger logger) {
        this.threads = threads;
        this.timing = timing;
        this.logger = logger;
    }

    boolean onServerThread() {
        return threads.onServerThread().getAsBoolean();
    }

    boolean onDispatchThread() {
        return threads.onDispatchThread().getAsBoolean();
    }

    /**
     * Whether this caller is refused because a recent server-thread call timed out.
     *
     * @return {@code true} to answer without touching the database
     */
    boolean refusesCaller() {
        long until = refusedUntil.get();
        return until != NEVER && onServerThread() && System.nanoTime() - until < 0L;
    }

    /**
     * Waits for a result within the wait limit.
     *
     * @param future the running call
     * @param <T>    the result type
     * @return the result
     * @throws InterruptedException when the thread was interrupted
     * @throws ExecutionException   when the call failed
     * @throws TimeoutException     when the limit passed; the call keeps running
     */
    <T> T await(@NotNull CompletableFuture<T> future)
            throws InterruptedException, ExecutionException, TimeoutException {
        long started = System.nanoTime();
        try {
            return future.get(timing.waitLimit().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timedOut) {
            if (onServerThread()) {
                refusedUntil.set(System.nanoTime() + timing.cooldown().toNanos());
            }
            throw timedOut;
        } finally {
            reportIfSlow(System.nanoTime() - started);
        }
    }

    private void reportIfSlow(long waitedNanos) {
        if (waitedNanos <= timing.slowCall().toNanos() || !onServerThread()) {
            return;
        }
        int slowCalls = slowSinceWarning.incrementAndGet();
        long now = System.nanoTime();
        long due = nextWarning.get();
        if (due != NEVER && now - due < 0L) {
            return;
        }
        if (nextWarning.compareAndSet(due, now + timing.warnInterval().toNanos())) {
            slowSinceWarning.addAndGet(-slowCalls);
            logger.warn("Vault call held a server thread for {} ms ({} slow call(s) since the last warning). "
                    + "Check the database connection.", TimeUnit.NANOSECONDS.toMillis(waitedNanos), slowCalls);
        }
    }
}
