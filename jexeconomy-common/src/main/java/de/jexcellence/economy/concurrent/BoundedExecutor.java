package de.jexcellence.economy.concurrent;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Runs tasks on a delegate executor, at most {@code permits} at the same time. The economy uses virtual threads, so
 * a wave of work (a currency reset, {@code depositAll}, a login wave) would otherwise open as many database sessions
 * as there are tasks and exhaust the connection pool; with as many permits as the pool has connections, the waiting
 * tasks park cheaply instead of timing out on the pool.
 *
 * <p>A task waits for its permit on its own virtual thread, never on the caller's thread. Tasks must not block on
 * other tasks of the same executor while they hold a permit.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public final class BoundedExecutor extends AbstractExecutorService {

    private final ExecutorService delegate;
    private final Semaphore permits;

    /**
     * Creates the executor.
     *
     * @param delegate runs the tasks, usually a virtual-thread-per-task executor
     * @param permits  the most tasks that run at the same time, at least 1
     */
    public BoundedExecutor(@NotNull ExecutorService delegate, int permits) {
        this.delegate = delegate;
        this.permits = new Semaphore(Math.max(1, permits), true);
    }

    @Override
    public void execute(@NotNull Runnable task) {
        delegate.execute(() -> runBounded(task));
    }

    private void runBounded(@NotNull Runnable task) {
        try {
            permits.acquire();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            task.run();
        } finally {
            permits.release();
        }
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public @NotNull List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, @NotNull TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}
