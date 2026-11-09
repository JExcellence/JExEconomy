package de.jexcellence.economy.service;

import de.jexcellence.economy.service.ledger.BalanceLedger;
import de.jexcellence.jexplatform.logging.JExLogger;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;

/**
 * Lets the economy finish its queued balance changes when the plugin disables, before the database closes.
 *
 * <p>The drain refuses new changes requested from the main thread (other plugins disabling), stops waiting for
 * main-thread pre-events (the main thread is in {@code onDisable}; those changes count as not cancelled), then
 * waits until the ledger has no queued account left or the timeout passed. Follow-up legs of running transfers,
 * refunds included, are booked on the workers and still complete.
 *
 * @author JExcellence
 * @since 3.1.0
 */
public final class EconomyDrain {

    private static final long POLL_MILLIS = 50L;

    private final BalanceLedger ledger;
    private final EconomyEvents events;
    private final JExLogger logger;

    /**
     * Creates the drain.
     *
     * @param ledger the balance ledger
     * @param events the event dispatcher
     * @param logger the plugin logger
     */
    public EconomyDrain(@NotNull BalanceLedger ledger, @NotNull EconomyEvents events, @NotNull JExLogger logger) {
        this.ledger = ledger;
        this.events = events;
        this.logger = logger;
    }

    /**
     * Drains the ledger.
     *
     * @param timeout the longest wait
     * @return the number of accounts that still had a queued change when the wait ended
     */
    public int drain(@NotNull Duration timeout) {
        ledger.refuseWhen(Bukkit::isPrimaryThread);
        events.stopMainThreadDispatch();
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean interrupted = false;
        while (ledger.pendingAccounts() > 0 && System.nanoTime() < deadline && !interrupted) {
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                interrupted = true;
            }
        }
        int pending = ledger.pendingAccounts();
        if (pending > 0) {
            logger.warn("{} account(s) still had queued balance changes after {} s; they are lost with the "
                    + "shutdown. Check their transaction logs.", pending, timeout.toSeconds());
        } else {
            logger.info("All queued balance changes were booked before the shutdown");
        }
        return pending;
    }
}
