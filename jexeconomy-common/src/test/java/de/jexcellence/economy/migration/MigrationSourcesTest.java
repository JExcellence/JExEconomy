package de.jexcellence.economy.migration;

import de.jexcellence.economy.migration.MigrationSource.SourceRead;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Each source delivers valid balances and counts what it cannot read, instead of stopping.
 */
class MigrationSourcesTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @TempDir
    private Path folder;

    private SourceRead readCsv(String... lines) throws IOException {
        Path file = folder.resolve("balances.csv");
        Files.write(file, List.of(lines));
        return new CsvMigrationSource(file, Runnable::run).read().join();
    }

    @Test
    void aCsvFileIsNamedAfterItself() {
        assertEquals("csv:balances", new CsvMigrationSource(folder.resolve("balances.csv"), Runnable::run).id());
    }

    @Test
    void aCsvFileReadsUuidNameAndBalanceAndSkipsItsHeader() throws IOException {
        SourceRead read = readCsv("uuid,name,balance", ALEX + ",Alex,12.34");

        assertEquals(1, read.balances().size());
        assertEquals(ALEX, read.balances().getFirst().player());
        assertEquals("Alex", read.balances().getFirst().name());
        assertEquals(new BigDecimal("12.34"), read.balances().getFirst().balance());
    }

    @Test
    void anEmptyCsvNameIsUnknown() throws IOException {
        assertEquals(null, readCsv(ALEX + ",,1").balances().getFirst().name());
    }

    @Test
    void aMalformedCsvLineIsCounted() throws IOException {
        SourceRead read = readCsv("not-a-uuid,x,1", ALEX + ",x,not-a-number", "too,few", ALEX + ",x,1");

        assertEquals(3, read.unreadable());
        assertEquals(1, read.balances().size());
    }

    @Test
    void essentialsUserdataIsReadByFileName() throws IOException {
        Files.writeString(folder.resolve(ALEX + ".yml"), "money: '250.75'\nlast-account-name: Alex\n");

        SourceRead read = new EssentialsMigrationSource(folder, Runnable::run).read().join();

        assertEquals(new BigDecimal("250.75"), read.balances().getFirst().balance());
        assertEquals("Alex", read.balances().getFirst().name());
    }

    @Test
    void anEssentialsFileWithoutMoneyIsNotAnError() throws IOException {
        Files.writeString(folder.resolve(ALEX + ".yml"), "last-account-name: Alex\n");

        SourceRead read = new EssentialsMigrationSource(folder, Runnable::run).read().join();

        assertTrue(read.balances().isEmpty());
        assertEquals(0, read.unreadable());
    }

    @Test
    void aBrokenEssentialsFileIsCounted() throws IOException {
        Files.writeString(folder.resolve("not-a-uuid.yml"), "money: '1'\n");

        assertEquals(1, new EssentialsMigrationSource(folder, Runnable::run).read().join().unreadable());
    }

    @Test
    void aVaultSourceCountsAPlayerItCannotRead() {
        OfflinePlayer good = player(ALEX);
        OfflinePlayer bad = player(UUID.randomUUID());
        var source = new VaultMigrationSource("Essentials", player -> {
            if (player == bad) {
                throw new IllegalStateException("no account");
            }
            return 8.5;
        }, () -> new OfflinePlayer[]{good, bad}, Runnable::run);

        SourceRead read = source.read().join();

        assertEquals("vault:essentials", source.id());
        assertEquals(1, read.unreadable());
        assertEquals(new BigDecimal("8.5"), read.balances().getFirst().balance());
    }

    @Test
    void aVaultSourceContinuesOnLaterTicks() {
        List<Runnable> ticks = new ArrayList<>();
        var players = new OfflinePlayer[20_000];
        java.util.Arrays.fill(players, player(ALEX));
        var source = new VaultMigrationSource("Slow", player -> {
            spin();
            return 1.0;
        }, () -> players, ticks::add);

        var running = source.read();
        int ticked = 0;
        while (!ticks.isEmpty()) {
            ticks.removeFirst().run();
            ticked++;
        }

        assertTrue(ticked > 1);
        assertEquals(20_000, running.join().balances().size());
    }

    private static void spin() {
        long end = System.nanoTime() + 5_000L;
        while (System.nanoTime() < end) {
            Thread.onSpinWait();
        }
    }

    private static OfflinePlayer player(UUID id) {
        OfflinePlayer player = mock(OfflinePlayer.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }
}
