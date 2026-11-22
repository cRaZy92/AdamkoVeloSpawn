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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/** Last season per player, persisted to a small JSON file on every change. */
public final class LastServerStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Logger logger;
    private final Executor writer;
    private final Map<UUID, String> data = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    public LastServerStore(Path file, Logger logger, Executor writer) {
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
            Map<String, String> raw = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, String>>() { }.getType());
            if (raw != null) {
                raw.forEach((k, v) -> {
                    try {
                        data.put(UUID.fromString(k), v);
                    } catch (IllegalArgumentException ignored) {
                        // skip malformed entry
                    }
                });
            }
        } catch (IOException | RuntimeException e) {
            logger.error("Could not read {}; starting with no last-server data", file, e);
        }
    }

    public Optional<String> get(UUID player) {
        return Optional.ofNullable(data.get(player));
    }

    public void put(UUID player, String season) {
        String old = data.put(player, season);
        if (!season.equals(old)) {
            writer.execute(this::save);
        }
    }

    public void save() {
        synchronized (writeLock) {
            Map<String, String> snapshot = new TreeMap<>();
            data.forEach((k, v) -> snapshot.put(k.toString(), v));
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
