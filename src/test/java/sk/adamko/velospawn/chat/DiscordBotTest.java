package sk.adamko.velospawn.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordBotTest {

    @Test
    void onlyPeopleAreRelayedSoOurOwnWebhookPostsNeverEchoBack() {
        assertTrue(DiscordBot.accepts(false, false, false));
        assertFalse(DiscordBot.accepts(false, false, true), "webhook (our Minecraft → Discord posts)");
        assertFalse(DiscordBot.accepts(true, false, false), "other bots");
        assertFalse(DiscordBot.accepts(false, true, false), "Discord system messages");
    }

    @Test
    void textIsOneTidyLine() {
        assertEquals("hello there", DiscordBot.text("  hello\n\n  there ", 0, 0));
        assertEquals("look [attachment]", DiscordBot.text("look", 1, 0));
        assertEquals("[3 attachments]", DiscordBot.text("", 3, 0));
        assertEquals("[sticker]", DiscordBot.text("", 0, 1));
        assertEquals("", DiscordBot.text("   ", 0, 0), "nothing to show");
        String longText = DiscordBot.text("x".repeat(1000), 0, 0);
        assertEquals(DiscordBot.MAX_LENGTH, longText.length());
        assertTrue(longText.endsWith("…"));
    }
}
