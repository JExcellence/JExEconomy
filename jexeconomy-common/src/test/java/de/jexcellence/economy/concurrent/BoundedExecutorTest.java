package de.jexcellence.economy.concurrent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedExecutorTest {

    @Test
    void neverRunsMoreTasksThanPermits() throws InterruptedException {
        ExecutorService virtual = Executors.newVirtualThreadPerTaskExecutor();
        BoundedExecutor bounded = new BoundedExecutor(virtual, 3);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        for (int task = 0; task < 60; task++) {
            tasks.add(CompletableFuture.runAsync(() -> {
                peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                sleepQuietly();
                running.decrementAndGet();
            }, bounded));
        }

        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();

        assertTrue(peak.get() <= 3, "peak " + peak.get());
        assertEquals(0, running.get());
        bounded.shutdown();
        assertTrue(bounded.awaitTermination(5, TimeUnit.SECONDS));
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
