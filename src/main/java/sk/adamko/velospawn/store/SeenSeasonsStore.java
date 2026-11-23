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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/** Which seasons each player has already been told about by the /seasons login hint. */
public final class SeenSeasonsStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Logger logger;
    private final Executor writer;
    private final Map<UUID, Set<String>> data = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    public SeenSeasonsStore(Path file, Logger logger, Executor writer) {
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
            Map<String, List<String>> raw = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, List<String>>>() { }.getType());
            if (raw != null) {
                raw.forEach((k, v) -> {
                    try {
                        data.put(UUID.fromString(k), v == null ? Set.of() : Set.copyOf(v));
                    } catch (IllegalArgumentException | NullPointerException ignored) {
                        // skip malformed entry
                    }
                });
            }
        } catch (IOException | RuntimeException e) {
            logger.error("Could not read {}; every player will see the /seasons hint again", file, e);
        }
    }

    /** The seasons this player was told about; empty if they never got the hint. */
    public Optional<Set<String>> get(UUID player) {
        return Optional.ofNullable(data.get(player));
    }

    /** Remembers these seasons as seen (added to what was seen before). */
    public void markSeen(UUID player, Collection<String> seasons) {
        Set<String> old = data.get(player);
        Set<String> merged = new TreeSet<>(seasons);
        if (old != null) {
            merged.addAll(old);
        }
        if (!merged.equals(old)) {
            data.put(player, Set.copyOf(merged));
            writer.execute(this::save);
        }
    }

    public void save() {
        synchronized (writeLock) {
            Map<String, List<String>> snapshot = new TreeMap<>();
            data.forEach((k, v) -> snapshot.put(k.toString(), List.copyOf(new TreeSet<>(v))));
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
