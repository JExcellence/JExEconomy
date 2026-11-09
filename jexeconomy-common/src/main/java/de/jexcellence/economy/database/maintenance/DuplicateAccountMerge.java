package de.jexcellence.economy.database.maintenance;

import de.jexcellence.economy.database.repository.AccountRepository;
import de.jexcellence.jexplatform.logging.JExLogger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/**
 * One-time startup cleanup that merges duplicate accounts (same player, same currency) before the unique constraint
 * {@code uk_account_player_currency} is relied on. Older versions could create such duplicates through a
 * check-then-insert race. Each merge keeps the oldest row with the summed balance and is logged. A marker file in
 * the database folder records that the cleanup finished, so later starts skip it.
 *
 * <p>The schema update never adds unique constraints to existing tables (strategy {@code SKIP}); after this cleanup
 * the plugin creates the owner and currency index itself when it is missing
 * ({@link UniqueIndexGuard}).
 *
 * @author JExcellence
 * @since 3.1.0
 */
public final class DuplicateAccountMerge {

    /** Marker file name, relative to the plugin's database folder. */
    public static final String MARKER = "account-merge.v1.done";

    private final AccountRepository accounts;
    private final Path marker;
    private final JExLogger logger;

    /**
     * Creates the cleanup.
     *
     * @param accounts       the account repository
     * @param databaseFolder the plugin's database folder that holds the marker
     * @param logger         the plugin logger
     */
    public DuplicateAccountMerge(@NotNull AccountRepository accounts, @NotNull Path databaseFolder,
                                 @NotNull JExLogger logger) {
        this.accounts = accounts;
        this.marker = databaseFolder.resolve(MARKER);
        this.logger = logger;
    }

    /**
     * Runs the cleanup once. Blocking; call it off the main thread.
     *
     * @return the number of merged duplicate groups, {@code 0} when the marker says it already ran
     */
    public int runOnce() {
        if (Files.exists(marker)) {
            return 0;
        }
        int merged = 0;
        for (long[] key : accounts.findDuplicateKeys()) {
            Optional<AccountMergePlan> plan = accounts.mergeDuplicates(key[0], key[1]);
            if (plan.isPresent()) {
                merged++;
                report(key, plan.get());
            }
        }
        if (merged > 0) {
            logger.info("Merged {} duplicate account group(s)", merged);
        }
        writeMarker(merged);
        return merged;
    }

    private void report(long @NotNull [] key, @NotNull AccountMergePlan plan) {
        logger.warn("Merged duplicate accounts of player #{} currency #{}: kept #{} with balance {}, removed {}",
                key[0], key[1], plan.keeperId(), plan.total().toPlainString(), plan.removedIds());
    }

    private void writeMarker(int merged) {
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "merged=" + merged + System.lineSeparator() + "at=" + Instant.now()
                    + System.lineSeparator());
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not write " + marker, failure);
        }
    }
}
