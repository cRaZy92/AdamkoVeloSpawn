package sk.adamko.velospawn.companion;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import sk.adamko.velospawn.companion.SeasonStats.PlayerStat;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeasonStatsTest {

    @Test
    void aggregateSumsAndPicksTop() {
        SeasonStats s = SeasonStats.aggregate(List.of(
                new PlayerStat("Adamko", 36_000_000, 10),
                new PlayerStat("Bob", 1_000, 2),
                new PlayerStat("Ghost", 0, 0)), 28_896_000, 5);
        assertEquals(2, s.players());
        assertEquals(36_001_000, s.playtimeTicks());
        assertEquals(12, s.deaths());
        assertEquals("Adamko", s.topName());
        assertEquals(36_000_000, s.topTicks());
        assertEquals(28_896_000, s.gameTime());
        assertEquals(5, s.collectedAt());
    }

    @Test
    void tieOnPlayTimeGoesToTheFirstName() {
        SeasonStats s = SeasonStats.aggregate(List.of(
                new PlayerStat("Zed", 100, 0), new PlayerStat("Anna", 100, 0)), 0, 0);
        assertEquals("Anna", s.topName());
    }

    @Test
    void aggregateOfNobodyHasNoTop() {
        SeasonStats s = SeasonStats.aggregate(List.of(), 24_000, 1);
        assertEquals(0, s.players());
        assertNull(s.topName());
        assertEquals(0, s.topTicks());
    }

    @Test
    void jsonCarriesTypeAndFields() {
        JsonObject o = SeasonStats.aggregate(List.of(new PlayerStat("Adamko", 72_000, 1)), 48_000, 7).toJson();
        assertEquals("stats", o.get("type").getAsString());
        assertEquals(1, o.get("v").getAsInt());
        assertEquals(1, o.get("players").getAsInt());
        assertEquals(72_000, o.get("playtimeTicks").getAsLong());
        assertEquals(1, o.get("deaths").getAsLong());
        assertEquals("Adamko", o.get("topName").getAsString());
        assertEquals(72_000, o.get("topTicks").getAsLong());
        assertEquals(48_000, o.get("gameTime").getAsLong());
        assertEquals(7, o.get("collectedAt").getAsLong());
    }

    @Test
    void jsonOfNobodyHasNullTop() {
        JsonObject o = SeasonStats.aggregate(List.of(), 0, 0).toJson();
        assertTrue(o.get("topName").isJsonNull());
    }
}
