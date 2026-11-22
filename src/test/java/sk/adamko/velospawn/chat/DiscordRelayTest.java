package sk.adamko.velospawn.chat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;
import sk.adamko.velospawn.config.PluginConfig.DiscordConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordRelayTest {

    private static final DiscordConfig CFG = new DiscordConfig("https://discord.test/hook",
            "[{server}] - {player}", "https://mc-heads.net/avatar/{player}/128",
            "{message}", "➡️ {player} has joined!", "🔀 {player} moved to {display}", "⬅️ {player} has left!");

    @Test
    void escapesMarkdownButKeepsLinks() {
        assertEquals("cool\\_guy", DiscordRelay.escapeMarkdown("cool_guy"));
        assertEquals("\\*\\*bold\\*\\* \\~\\~x\\~\\~ \\`code\\` \\|\\|spoiler\\|\\|",
                DiscordRelay.escapeMarkdown("**bold** ~~x~~ `code` ||spoiler||"));
        assertEquals("\\[free nitro\\](https://evil.example)",
                DiscordRelay.escapeMarkdown("[free nitro](https://evil.example)"), "no masked links");
        assertEquals("\\# big > quote", DiscordRelay.escapeMarkdown("# big > quote"),
                "heading escaped; > mid-line is harmless");
        assertEquals("see https://example.com/a-b?x=1", DiscordRelay.escapeMarkdown("see https://example.com/a-b?x=1"));
        assertEquals("1 - 2", DiscordRelay.escapeMarkdown("1 - 2"));
    }

    @Test
    void fillsPlaceholdersAndLeavesUnknownOnes() {
        assertEquals("[s1] - Ann {nope}", DiscordRelay.fill("[{server}] - {player} {nope}",
                Map.of("server", "s1", "player", "Ann")));
        assertEquals("$1 \\ ok", DiscordRelay.fill("{message}", Map.of("message", "$1 \\ ok")));
    }

    @Test
    void payloadRespectsDiscordLimits() {
        JsonObject body = DiscordRelay.payload("x".repeat(2100), "[s1] - Discord" + "y".repeat(100), "");
        assertEquals(2000, body.get("content").getAsString().length());
        assertEquals(80, body.get("username").getAsString().length());
        assertFalse(body.get("username").getAsString().toLowerCase().contains("discord"),
                "Discord rejects webhook names containing 'discord'");
        assertFalse(body.has("avatar_url"));
    }

    @Test
    void postsInOrderEvenWhenTheFirstIsSlow() {
        List<String> sent = new ArrayList<>();
        CompletableFuture<DiscordRelay.Response> slow = new CompletableFuture<>();
        DiscordRelay relay = new DiscordRelay(() -> CFG, NOPLogger.NOP_LOGGER, (url, json) -> {
            sent.add(JsonParser.parseString(json).getAsJsonObject().get("content").getAsString());
            return sent.size() == 1 ? slow : CompletableFuture.completedFuture(new DiscordRelay.Response(204, 0));
        });
        relay.join("Ann", "s1", "Season 1");
        relay.chat("Ann", "s1", "Season 1", "first!");
        assertEquals(List.of("➡️ Ann has joined!"), sent, "second waits for the first");
        slow.complete(new DiscordRelay.Response(204, 0));
        assertEquals(List.of("➡️ Ann has joined!", "first!"), sent);
    }

    @Test
    void retriesOnceWhenRateLimited() throws Exception {
        List<String> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        DiscordRelay relay = new DiscordRelay(() -> CFG, NOPLogger.NOP_LOGGER, (url, json) -> {
            sent.add(json);
            return CompletableFuture.completedFuture(new DiscordRelay.Response(429, 0.05));
        });
        relay.join("Ann", "s1", "Season 1");
        long deadline = System.currentTimeMillis() + 3000;
        while (sent.size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        Thread.sleep(700); // a third attempt would have happened by now
        assertEquals(2, sent.size(), "one retry, then give up");
    }

    @Test
    void failuresDoNotBlockLaterPosts() {
        List<String> sent = new ArrayList<>();
        DiscordRelay relay = new DiscordRelay(() -> CFG, NOPLogger.NOP_LOGGER, (url, json) -> {
            sent.add(json);
            return sent.size() == 1
                    ? CompletableFuture.failedFuture(new java.io.IOException("down"))
                    : CompletableFuture.completedFuture(new DiscordRelay.Response(204, 0));
        });
        relay.join("Ann", "s1", "Season 1");
        relay.leave("Ann", "s1", "Season 1");
        assertEquals(2, sent.size());
        assertTrue(sent.get(1).contains("has left"));
    }
}
