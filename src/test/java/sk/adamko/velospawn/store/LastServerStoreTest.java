package sk.adamko.velospawn.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LastServerStoreTest {

    @Test
    void survivesRestart(@TempDir Path dir) {
        Path file = dir.resolve("last-servers.json");
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        LastServerStore store = new LastServerStore(file, NOPLogger.NOP_LOGGER, Runnable::run);
        store.put(a, "s2");
        store.put(b, "s3");
        store.put(a, "s3");

        LastServerStore reloaded = new LastServerStore(file, NOPLogger.NOP_LOGGER, Runnable::run);
        reloaded.load();
        assertEquals(Optional.of("s3"), reloaded.get(a));
        assertEquals(Optional.of("s3"), reloaded.get(b));
        assertEquals(Optional.empty(), reloaded.get(UUID.randomUUID()));
    }
}
