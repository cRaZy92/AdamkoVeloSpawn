package sk.adamko.velospawn.companion;

import com.google.gson.JsonObject;

import java.util.Comparator;
import java.util.List;

/**
 * This season's history numbers for the proxy's /seasons menu. Field names match the proxy's
 * {@code SeasonStatsStore.SeasonStats}. Ticks are play-time statistic ticks; {@code gameTime} is the
 * overworld's game time; {@code collectedAt} epoch millis; {@code topName} null when nobody played.
 */
record SeasonStats(int players, long playtimeTicks, long deaths, String topName, long topTicks, long gameTime,
                   long collectedAt) {

    /** One player's statistics; {@code name} is the last known name, or the UUID when unknown. */
    record PlayerStat(String name, long playTicks, long deaths) {
    }

    /** Players are those who actually played (play time above zero); ties for most played go to the first name. */
    static SeasonStats aggregate(List<PlayerStat> stats, long gameTime, long collectedAt) {
        List<PlayerStat> played = stats.stream().filter(s -> s.playTicks() > 0).toList();
        PlayerStat top = played.stream()
                .max(Comparator.comparingLong(PlayerStat::playTicks)
                        .thenComparing(PlayerStat::name, Comparator.reverseOrder()))
                .orElse(null);
        return new SeasonStats(
                played.size(),
                played.stream().mapToLong(PlayerStat::playTicks).sum(),
                stats.stream().mapToLong(PlayerStat::deaths).sum(),
                top == null ? null : top.name(),
                top == null ? 0 : top.playTicks(),
                gameTime,
                collectedAt);
    }

    JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("type", "stats");
        o.addProperty("players", players);
        o.addProperty("playtimeTicks", playtimeTicks);
        o.addProperty("deaths", deaths);
        o.addProperty("topName", topName);
        o.addProperty("topTicks", topTicks);
        o.addProperty("gameTime", gameTime);
        o.addProperty("collectedAt", collectedAt);
        return o;
    }
}
