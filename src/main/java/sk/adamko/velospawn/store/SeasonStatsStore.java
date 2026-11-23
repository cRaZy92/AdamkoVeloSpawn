package sk.adamko.velospawn.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Latest history numbers per season, as reported by the companion plugin on that season, for the
 * /seasons menu. Persisted to a small JSON file so finished seasons never need to run again.
 */
public final class SeasonStatsStore {

    /**
     * One season's numbers. {@code playtimeTicks}/{@code topTicks} are play-time statistic ticks,
     * {@code gameTime} the overworld's game time in ticks, {@code collectedAt} epoch millis.
     * {@code topName} is null when nobody has played.
     */
    public record SeasonStats(int players, long playtimeTicks, long deaths, String topName, long topTicks,
                              long gameTime, long collectedAt) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Logger logger;
    private final Executor writer;
    private final Map<String, SeasonStats> data = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    public SeasonStatsStore(Path file, Logger logger, Executor writer) {
        this.file = file;
        this.logger = logger;
        this.writer = writer;
    }

    public void load() {
        data.clear();
        if (Files.notExists(file)) {
            return;
        }
        try {
            Map<String, SeasonStats> raw = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, SeasonStats>>() { }.getType());
            if (raw != null) {
                raw.forEach((k, v) -> {
                    if (k != null && v != null) {
                        data.put(k, v);
                    }
                });
            }
        } catch (IOException | RuntimeException e) {
            logger.error("Could not read {}; starting with no season stats", file, e);
        }
    }

    public Optional<SeasonStats> get(String server) {
        return Optional.ofNullable(data.get(server));
    }

    /** Stores the snapshot unless an equally new or newer one is already there. */
    public boolean put(String server, SeasonStats stats) {
        boolean[] changed = {false};
        data.compute(server, (k, old) -> {
            if (old != null && old.collectedAt() >= stats.collectedAt()) {
                return old;
            }
            changed[0] = true;
            return stats;
        });
        if (changed[0]) {
            writer.execute(this::save);
        }
        return changed[0];
    }

    public void save() {
        synchronized (writeLock) {
            Map<String, SeasonStats> snapshot = new TreeMap<>(data);
            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, GSON.toJson(snapshot), StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                logger.error("Could not write {}", file, e);
            }
        }
    }
}
