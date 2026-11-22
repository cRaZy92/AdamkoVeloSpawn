package sk.adamko.velospawn.mapmod;

import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import org.junit.jupiter.api.Test;
import sk.adamko.velospawn.config.PluginConfig;
import sk.adamko.velospawn.testkit.TestNetwork;
import sk.adamko.velospawn.testkit.TestNetwork.FakePlayer;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapIdServiceTest {

    private static final String MINIMAP = "xaerominimap:main";
    private static final String WORLDMAP = "xaeroworldmap:main";
    private static final String WORLD_ID = "worldinfo:world_id";

    private final TestNetwork net = new TestNetwork();

    private int id(String server) {
        return net.config().mapId(server);
    }

    private FakePlayer onS3() {
        net.running("s3");
        return net.login("Bob");
    }

    /** Client → proxy world_id request while on the player's current server. */
    private PluginMessageEvent request(FakePlayer p, byte... data) {
        PluginMessageEvent e = new PluginMessageEvent(p.mock, p.connectionNow(), MapIdService.WORLD_ID, data);
        net.mapIds.onPluginMessage(e);
        return e;
    }

    /** Backend → client message on a channel. */
    private PluginMessageEvent fromBackend(FakePlayer p, com.velocitypowered.api.proxy.messages.ChannelIdentifier ch,
                                           byte... data) {
        PluginMessageEvent e = new PluginMessageEvent(p.connectionNow(), p.mock, ch, data);
        net.mapIds.onPluginMessage(e);
        return e;
    }

    private void disable() {
        net.config(c -> new PluginConfig(c.panelUrl(), c.apiKey(), c.authServer(), c.waitingServer(), c.newest(),
                c.seasons(), c.rememberLastServer(), c.prestartOnConnect(), c.startupTimeoutSeconds(),
                c.pollIntervalSeconds(), c.pingTimeoutMs(), c.chat(), c.messages(),
                new PluginConfig.MapModsConfig(false, 3, 5)));
    }

    // ------------------------------------------------------------------ Xaero push

    @Test
    void xaeroIdPushedOnBothChannelsRightAfterSwitchAndRepeated() {
        FakePlayer p = onS3();
        byte[] s3 = MapIdCodec.xaero(id("s3"));
        assertArrayEquals(s3, p.sent(MINIMAP).get(p.sent(MINIMAP).size() - 1));
        assertArrayEquals(s3, p.sent(WORLDMAP).get(p.sent(WORLDMAP).size() - 1));

        int before = p.sent(MINIMAP).size();
        net.scheduler.advanceSeconds(2);
        assertEquals(before + 2, p.sent(MINIMAP).size(), "3 sends in total, 1 s apart");
        net.scheduler.advanceSeconds(5);
        assertEquals(before + 2, p.sent(MINIMAP).size(), "no periodic re-send until started");
    }

    @Test
    void eachSeasonGetsItsOwnId() {
        net.running("s3");
        net.running("s2");
        net.running("s1");
        FakePlayer p = net.login("Bob");
        net.connect(p, "s2");
        assertArrayEquals(MapIdCodec.xaero(id("s2")), p.sent(MINIMAP).get(p.sent(MINIMAP).size() - 1));
        net.connect(p, "s1");
        assertArrayEquals(MapIdCodec.xaero(id("s1")), p.sent(MINIMAP).get(p.sent(MINIMAP).size() - 1));
        assertEquals(3, List.of(id("s1"), id("s2"), id("s3")).stream().distinct().count());
    }

    @Test
    void pendingRepeatsForTheOldServerAreDroppedAfterSwitching() {
        net.running("s3");
        net.running("s2");
        FakePlayer p = net.login("Bob");
        net.connect(p, "s2");
        p.pluginMessages.clear();
        net.scheduler.advanceSeconds(3);
        byte[] s3 = MapIdCodec.xaero(id("s3"));
        assertTrue(p.sent(MINIMAP).stream().noneMatch(b -> java.util.Arrays.equals(b, s3)),
                "never sends s3's id while on s2");
        assertEquals(2, p.sent(MINIMAP).size(), "s2's own two repeats");
    }

    @Test
    void periodicResendRestoresIdAfterDeathOrPortal() {
        FakePlayer p = onS3();
        net.mapIds.start();
        net.scheduler.advanceSeconds(3);
        p.pluginMessages.clear();
        net.scheduler.advanceSeconds(5);
        assertEquals(List.of(MapIdCodec.xaero(id("s3"))).size(), p.sent(MINIMAP).size());
        assertArrayEquals(MapIdCodec.xaero(id("s3")), p.sent(MINIMAP).get(0));
        assertArrayEquals(MapIdCodec.xaero(id("s3")), p.sent(WORLDMAP).get(0));
    }

    @Test
    void waitingRoomAndLimboGetTheirOwnIdsSoTheVoidNeverLandsInASeasonMap() {
        net.offline("s3");
        FakePlayer p = net.login("Bob"); // ends in waiting
        assertEquals("waiting", p.server);
        assertArrayEquals(MapIdCodec.xaero(id("waiting")), p.sent(MINIMAP).get(p.sent(MINIMAP).size() - 1));
        assertFalse(id("waiting") == id("s3"));
    }

    // ------------------------------------------------------------------ JourneyMap / VoxelMap

    @Test
    void worldIdRequestAnsweredByProxyAndNotForwarded() {
        FakePlayer p = onS3();
        PluginMessageEvent e = request(p, (byte) 0, (byte) 42, (byte) 0);
        assertFalse(e.getResult().isAllowed(), "handled on the proxy");
        assertArrayEquals(MapIdCodec.response(new MapIdCodec.Request(1, true), id("s3")),
                p.sent(WORLD_ID).get(0));
    }

    @Test
    void worldIdAnswerFollowsTheCurrentServer() {
        net.running("s3");
        net.running("s2");
        FakePlayer p = net.login("Bob");
        net.connect(p, "s2");
        request(p, (byte) 0, (byte) 42, (byte) 0);
        assertEquals(id("s2"), MapIdCodec.parsePrefixed(p.sent(WORLD_ID).get(0)).id());
    }

    @Test
    void oldVoxelMapRequestShapesAreMirrored() {
        FakePlayer p = onS3();
        request(p, (byte) 0, (byte) 0, (byte) 0, (byte) 42);
        assertEquals(new MapIdCodec.PrefixedId(0, true, id("s3")), MapIdCodec.parsePrefixed(p.sent(WORLD_ID).get(0)));
        p.protocol = ProtocolVersion.MINECRAFT_1_16_3;
        request(p, (byte) 0, (byte) 42, (byte) 0);
        assertEquals(new MapIdCodec.PrefixedId(1, false, id("s3")), MapIdCodec.parsePrefixed(p.sent(WORLD_ID).get(1)));
    }

    @Test
    void malformedRequestIsForwardedUntouched() {
        FakePlayer p = onS3();
        PluginMessageEvent e = request(p, (byte) 9, (byte) 9);
        assertTrue(e.getResult().isAllowed());
        assertTrue(p.sent(WORLD_ID).isEmpty());
    }

    // ------------------------------------------------------------------ backend packets

    @Test
    void backendXaeroIdIsRewrittenToTheSeasonId() {
        FakePlayer p = onS3();
        p.pluginMessages.clear();
        PluginMessageEvent e = fromBackend(p, MapIdService.XAERO_WORLDMAP, MapIdCodec.xaero(777));
        assertFalse(e.getResult().isAllowed());
        assertArrayEquals(MapIdCodec.xaero(id("s3")), p.sent(WORLDMAP).get(0));
    }

    @Test
    void backendWorldIdResponseIsRewrittenKeepingItsShape() {
        FakePlayer p = onS3();
        fromBackend(p, MapIdService.WORLD_ID, (byte) 0, (byte) 1, (byte) 49);
        assertEquals(new MapIdCodec.PrefixedId(1, false, id("s3")), MapIdCodec.parsePrefixed(p.sent(WORLD_ID).get(0)));
    }

    @Test
    void unrelatedChannelsAreIgnored() {
        FakePlayer p = onS3();
        PluginMessageEvent e = new PluginMessageEvent(p.mock, p.connectionNow(),
                com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier.from("minecraft:brand"), new byte[]{1});
        net.mapIds.onPluginMessage(e);
        assertTrue(e.getResult().isAllowed());
    }

    // ------------------------------------------------------------------ config

    @Test
    void perSeasonOverrideIsUsed() {
        net.config(c -> {
            var seasons = new LinkedHashMap<>(c.seasons());
            var s3 = c.season("s3");
            seasons.put("s3", new PluginConfig.SeasonConfig("s3", s3.panelId(), s3.displayName(),
                    s3.idleShutdownMinutes(), s3.joinDelaySeconds(), 4242));
            return new PluginConfig(c.panelUrl(), c.apiKey(), c.authServer(), c.waitingServer(), c.newest(),
                    seasons, c.rememberLastServer(), c.prestartOnConnect(), c.startupTimeoutSeconds(),
                    c.pollIntervalSeconds(), c.pingTimeoutMs(), c.chat(), c.messages(), c.mapMods());
        });
        FakePlayer p = onS3();
        assertArrayEquals(MapIdCodec.xaero(4242), p.sent(MINIMAP).get(p.sent(MINIMAP).size() - 1));
    }

    @Test
    void disabledSendsAndAnswersNothing() {
        disable();
        FakePlayer p = onS3();
        net.mapIds.start();
        PluginMessageEvent e = request(p, (byte) 0, (byte) 42, (byte) 0);
        net.scheduler.advanceSeconds(30);
        assertTrue(p.pluginMessages.isEmpty());
        assertTrue(e.getResult().isAllowed(), "request goes to the backend as before");
    }
}
