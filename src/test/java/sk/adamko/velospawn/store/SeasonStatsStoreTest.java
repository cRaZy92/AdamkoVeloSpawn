package sk.adamko.velospawn.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;
import sk.adamko.velospawn.store.SeasonStatsStore.SeasonStats;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeasonStatsStoreTest {

    private static SeasonStats stats(int players, long collectedAt) {
        return new SeasonStats(players, 166_320_000L, 512, "Adamko", 36_000_000L, 28_896_000L, collectedAt);
    }

    private static SeasonStatsStore store(Path file) {
        return new SeasonStatsStore(file, NOPLogger.NOP_LOGGER, Runnable::run);
    }

    @Test
    void roundTripsThroughDisk(@TempDir Path dir) {
        Path file = dir.resolve("season-stats.json");
        store(file).put("s2", stats(41, 1000));

        SeasonStatsStore reloaded = store(file);
        reloaded.load();
        assertEquals(Optional.of(stats(41, 1000)), reloaded.get("s2"));
        assertEquals(Optional.empty(), reloaded.get("s1"));
    }

    @Test
    void newestCollectedAtWins(@TempDir Path dir) {
        SeasonStatsStore s = store(dir.resolve("season-stats.json"));
        assertTrue(s.put("s2", stats(41, 200)));
        assertFalse(s.put("s2", stats(40, 100)));
        assertEquals(41, s.get("s2").orElseThrow().players());
    }

    @Test
    void nullTopNameSurvivesRoundTrip(@TempDir Path dir) {
        Path file = dir.resolve("season-stats.json");
        SeasonStats nobody = new SeasonStats(0, 0, 0, null, 0, 24_000, 5);
        store(file).put("s1", nobody);
        SeasonStatsStore reloaded = store(file);
        reloaded.load();
        assertEquals(Optional.of(nobody), reloaded.get("s1"));
    }

    @Test
    void corruptFileStartsEmpty(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("season-stats.json");
        Files.writeString(file, "{not json");
        SeasonStatsStore s = store(file);
        s.load();
        assertEquals(Optional.empty(), s.get("s2"));
    }

    @Test
    void missingFileStartsEmpty(@TempDir Path dir) {
        SeasonStatsStore s = store(dir.resolve("season-stats.json"));
        s.load();
        assertEquals(Optional.empty(), s.get("s2"));
    }
}
