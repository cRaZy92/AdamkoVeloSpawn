package sk.adamko.velospawn.companion;

import com.velocitypowered.api.event.connection.PluginMessageEvent;
import org.junit.jupiter.api.Test;
import sk.adamko.velospawn.testkit.TestNetwork;
import sk.adamko.velospawn.testkit.TestNetwork.FakePlayer;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionChannelTest {

    static final String STATS = "{\"v\":1,\"type\":\"stats\",\"players\":41,\"playtimeTicks\":166320000,"
            + "\"deaths\":512,\"topName\":\"Adamko\",\"topTicks\":36000000,\"gameTime\":28896000,\"collectedAt\":1000}";

    private final TestNetwork net = new TestNetwork();

    private PluginMessageEvent fromServer(FakePlayer p, String json) {
        var e = new PluginMessageEvent(p.connectionNow(), p.mock, CompanionChannel.EVENTS,
                json.getBytes(StandardCharsets.UTF_8));
        net.companion.onPluginMessage(e);
        return e;
    }

    private FakePlayer on(String name, String server) {
        FakePlayer p = net.player(name);
        p.server = server;
        return p;
    }

    @Test
    void statsAreStoredEvenWithChatOff() {
        assertFalse(net.config().chat().enabled());
        FakePlayer bob = on("Bob", "s2");
        var e = fromServer(bob, STATS);
        var stats = net.stats.get("s2").orElseThrow();
        assertEquals(41, stats.players());
        assertEquals(166_320_000L, stats.playtimeTicks());
        assertEquals("Adamko", stats.topName());
        assertEquals(28_896_000L, stats.gameTime());
        assertFalse(e.getResult().isAllowed(), "consumed by the proxy");
    }

    @Test
    void statsFromAWaitingRoomAreIgnored() {
        FakePlayer bob = on("Bob", "waiting");
        fromServer(bob, STATS);
        assertTrue(net.stats.get("waiting").isEmpty());
    }

    @Test
    void registersTheChannelOnSeasonsWithChatOff() {
        net.running("s3");
        FakePlayer bob = net.login("Bob");
        net.connect(bob, "s3");
        assertTrue(bob.toBackend.contains("s3|minecraft:register|adamkovelospawn:events"), bob.toBackend.toString());
    }

    @Test
    void channelIsNotRegisteredInTheWaitingRoom() {
        FakePlayer bob = net.login("Bob");
        net.connect(bob, "waiting");
        assertTrue(bob.toBackend.stream().noneMatch(s -> s.startsWith("waiting|minecraft:register")));
    }

    @Test
    void playerSourcedMessagesAreDroppedAndNotForwarded() {
        FakePlayer bob = on("Bob", "s2");
        var e = new PluginMessageEvent(bob.mock, bob.connectionNow(), CompanionChannel.EVENTS,
                STATS.getBytes(StandardCharsets.UTF_8));
        net.companion.onPluginMessage(e);
        assertFalse(e.getResult().isAllowed());
        assertTrue(net.stats.get("s2").isEmpty(), "a modded client can't fake stats");
    }

    @Test
    void companionIsKnownOnceHeardFrom() {
        FakePlayer bob = on("Bob", "s2");
        assertFalse(net.companion.hasCompanion("s2"));
        fromServer(bob, "{\"v\":1,\"type\":\"world\",\"world\":\"world\",\"env\":\"NORMAL\"}");
        assertTrue(net.companion.hasCompanion("s2"));
    }
}
