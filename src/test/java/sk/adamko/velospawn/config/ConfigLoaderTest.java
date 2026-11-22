package sk.adamko.velospawn.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    @Test
    void writesAndParsesBundledDefault(@TempDir Path dir) throws Exception {
        PluginConfig cfg = ConfigLoader.load(dir);
        assertTrue(Files.exists(dir.resolve("config.yml")));
        assertEquals("waiting", cfg.waitingServer());
        assertEquals("limbo", cfg.authServer());
        assertEquals("s3", cfg.newest());
        assertEquals(List.of("s3", "s2", "s1"), cfg.seasonNames());
        assertEquals("test0003", cfg.season("s3").panelId());
        assertEquals(15, cfg.season("s2").joinDelaySeconds());
        assertEquals("test0001", cfg.season("s1").panelId());
        assertEquals(10, cfg.season("s1").idleShutdownMinutes());
        assertEquals("Season 1 · 2023", cfg.season("s1").displayName());
        assertEquals("Season 2 · 2024–2026", cfg.season("s2").displayName());
        assertEquals("Season 3 · 2026", cfg.season("s3").displayName());
        assertEquals(180, cfg.startupTimeoutSeconds());
        assertEquals(1000, cfg.pingTimeoutMs());
        assertFalse(cfg.chat().enabled());
        assertEquals(Set.of("limbo", "waiting"), cfg.chat().excludeServers());
        assertTrue(cfg.message("ready").contains("{display}"));
    }

    @Test
    void fillsMissingKeysButKeepsUserSeasons(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("config.yml"), """
                panel:
                  url: "http://panel.local/"
                  api-key: "k"
                servers:
                  newest: s4
                  seasons:
                    s4:
                      panel-id: "abc"
                messages:
                  ready: "<green>go"
                """);
        PluginConfig cfg = ConfigLoader.load(dir);
        assertEquals("http://panel.local", cfg.panelUrl());
        assertEquals(List.of("s4"), cfg.seasonNames()); // default s1/s2/s3 not re-added
        assertEquals("s4", cfg.season("s4").displayName());
        assertEquals("<green>go", cfg.message("ready")); // user value kept
        assertTrue(cfg.message("start-failed").contains("{display}")); // missing key filled
        assertEquals(2, cfg.pollIntervalSeconds());
    }
}
