package sk.adamko.velospawn;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessagesTest {

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void placeholdersBecomeNamespacedTags() {
        assertEquals("<yellow>Starting <avs_display>… <avs_elapsed>s",
                Messages.toTags("<yellow>Starting {display}… {elapsed}s"));
        assertEquals("<avs_server_display>", Messages.toTags("{server-display}"));
    }

    @Test
    void rendersValues() {
        Component c = Messages.render("<yellow>Starting {display}… {elapsed}s", "display", "Season 3 · 2026", "elapsed", 12);
        assertEquals("Starting Season 3 · 2026… 12s", plain(c));
    }

    @Test
    void playerTextCannotInjectTags() {
        Component c = Messages.render("{player}: {message}", "player", "Bob", "message", "<red>hi <click:run_command:/op>");
        assertEquals("Bob: <red>hi <click:run_command:/op>", plain(c));
    }

    @Test
    void componentValuesAreInsertedAsIs() {
        Component c = Messages.render("{prefix}{player}", "prefix", Component.text("[VIP] "), "player", "Ann");
        assertEquals("[VIP] Ann", plain(c));
    }

    @Test
    void emptyTemplateRendersNothing() {
        assertEquals(Component.empty(), Messages.render(""));
    }

    @Test
    void escapedTagsInConfigStayLiteral() {
        assertEquals("Usage: /msg <player> <message>",
                plain(Messages.render("<red>Usage: /msg \\<player> \\<message>")));
    }
}
