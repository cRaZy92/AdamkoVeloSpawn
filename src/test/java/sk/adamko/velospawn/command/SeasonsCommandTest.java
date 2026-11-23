package sk.adamko.velospawn.command;

import com.google.gson.JsonParser;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import org.junit.jupiter.api.Test;
import sk.adamko.velospawn.companion.CompanionChannel;
import sk.adamko.velospawn.testkit.TestNetwork;
import sk.adamko.velospawn.testkit.TestNetwork.FakePlayer;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeasonsCommandTest {

    private final TestNetwork net = new TestNetwork();
    private final SeasonsCommand command = new SeasonsCommand(net.menu, net.companion);

    private void fromServer(FakePlayer p, String json) {
        net.companion.onPluginMessage(new PluginMessageEvent(p.connectionNow(), p.mock, CompanionChannel.EVENTS,
                json.getBytes(StandardCharsets.UTF_8)));
    }

    private List<String> menusSent(FakePlayer p, String server) {
        String prefix = server + "|adamkovelospawn:events|";
        return p.toBackend.stream().filter(s -> s.startsWith(prefix)).map(s -> s.substring(prefix.length()))
                .filter(json -> "menu".equals(JsonParser.parseString(json).getAsJsonObject().get("type").getAsString()))
                .toList();
    }

    static final String STATS = "{\"v\":1,\"type\":\"stats\",\"players\":1,\"playtimeTicks\":72000,"
            + "\"deaths\":0,\"topName\":\"Bob\",\"topTicks\":72000,\"gameTime\":24000,\"collectedAt\":1}";

    @Test
    void onASeasonWithCompanionTheMenuGoesToTheServer() {
        net.running("s3");
        FakePlayer bob = net.login("Bob");
        fromServer(bob, STATS);
        command.open(bob.mock);
        assertEquals(1, menusSent(bob, "s3").size(), bob.toBackend.toString());
        assertTrue(bob.chat().isEmpty(), bob.chat().toString());
    }

    @Test
    void anOldCompanionWithoutTheMenuGetsTheChatList() {
        net.running("s3");
        FakePlayer bob = net.login("Bob");
        // An older companion only sends world/afk/death/advancement, never stats, and can't draw the chest.
        fromServer(bob, "{\"v\":1,\"type\":\"world\",\"world\":\"world\",\"env\":\"NORMAL\"}");
        command.open(bob.mock);
        assertTrue(menusSent(bob, "s3").isEmpty(), bob.toBackend.toString());
        assertTrue(String.join("\n", bob.chat()).contains("── Seasons ──"), bob.chat().toString());
    }

    @Test
    void inTheWaitingRoomTheChatListIsShown() {
        net.offline("s3");
        FakePlayer bob = net.login("Bob");
        assertEquals("waiting", bob.server);
        command.open(bob.mock);
        String all = String.join("\n", bob.chat());
        assertTrue(all.contains("── Seasons ──"), all);
        assertTrue(all.contains("Season 2 · 2024–2026"), all);
        assertTrue(menusSent(bob, "waiting").isEmpty());
    }

    @Test
    void onASeasonWithoutCompanionTheChatListIsShown() {
        net.running("s3");
        FakePlayer bob = net.login("Bob");
        command.open(bob.mock);
        assertTrue(String.join("\n", bob.chat()).contains("── Seasons ──"));
        assertTrue(menusSent(bob, "s3").isEmpty());
    }

    @Test
    void openingTheMenuStartsNothing() {
        net.offline("s3");
        FakePlayer bob = net.login("Bob");
        long before = net.panel.calls.size();
        command.open(bob.mock);
        assertEquals(before, net.panel.calls.size());
    }

    @Test
    void menuClickRunsPlay() {
        net.running("s3");
        net.offline("s2");
        FakePlayer bob = net.login("Bob");
        fromServer(bob, "{\"v\":1,\"type\":\"menu-click\",\"season\":\"s2\"}");
        assertEquals(1, net.startCalls("s2"));
    }

    @Test
    void menuClickForUnknownSeasonDoesNothing() {
        net.running("s3");
        FakePlayer bob = net.login("Bob");
        long calls = net.panel.calls.size();
        fromServer(bob, "{\"v\":1,\"type\":\"menu-click\",\"season\":\"waiting\"}");
        fromServer(bob, "{\"v\":1,\"type\":\"menu-click\",\"season\":\"s9\"}");
        assertEquals(calls, net.panel.calls.size());
        assertEquals("s3", bob.server);
        assertTrue(bob.chat().isEmpty(), bob.chat().toString());
    }

    @Test
    void menuClickOnCurrentSeasonSaysAlreadyThere() {
        net.running("s3");
        FakePlayer bob = net.login("Bob");
        fromServer(bob, "{\"v\":1,\"type\":\"menu-click\",\"season\":\"s3\"}");
        assertTrue(bob.chat().contains("You are already on Season 3 · 2026."), bob.chat().toString());
    }
}
