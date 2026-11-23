package sk.adamko.velospawn.companion;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;

/**
 * Counts this season's players, playtime, deaths and in-game days for the proxy's /seasons menu, at
 * startup and then every few minutes. Plugin messages ride on a player's connection, so a snapshot is
 * sent through whoever is online; with nobody on, the next player to arrive carries it.
 */
final class StatsCollector {

    private final Companion plugin;
    private volatile SeasonStats latest;

    StatsCollector(Companion plugin) {
        this.plugin = plugin;
    }

    void start(long intervalMinutes) {
        long period = Math.max(1, intervalMinutes) * 60 * 20;
        // First run one tick after enable, when the worlds are loaded.
        Bukkit.getScheduler().runTaskTimer(plugin, this::collectAndSend, 1L, period);
    }

    Optional<SeasonStats> latest() {
        return Optional.ofNullable(latest);
    }

    private void collectAndSend() {
        try {
            latest = collect();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not count season stats", e);
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.sendJson(p, latest.toJson())) {
                return; // one copy is enough
            }
        }
    }

    /** Main thread: offline statistics are read through Paper, which knows the world layout. */
    private SeasonStats collect() {
        List<SeasonStats.PlayerStat> stats = new ArrayList<>();
        for (OfflinePlayer p : Bukkit.getOfflinePlayers()) {
            try {
                String name = p.getName() != null ? p.getName() : p.getUniqueId().toString();
                stats.add(new SeasonStats.PlayerStat(name,
                        p.getStatistic(Statistic.PLAY_ONE_MINUTE), p.getStatistic(Statistic.DEATHS)));
            } catch (RuntimeException e) {
                plugin.getLogger().fine("No statistics for " + p.getUniqueId() + ": " + e);
            }
        }
        World overworld = Bukkit.getWorlds().get(0);
        return SeasonStats.aggregate(stats, overworld.getGameTime(), System.currentTimeMillis());
    }
}
