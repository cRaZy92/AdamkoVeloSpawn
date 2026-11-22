package sk.adamko.velospawn.mapmod;

import org.junit.jupiter.api.Test;
import sk.adamko.velospawn.mapmod.MapIdCodec.PrefixedId;
import sk.adamko.velospawn.mapmod.MapIdCodec.Request;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Byte vectors taken from MapModCompanion's own tests, so the formats match what the mods expect. */
class MapIdCodecTest {

    @Test
    void xaeroLevelMapProperties() {
        assertArrayEquals(new byte[]{0, 0, 0, 0, 1}, MapIdCodec.xaero(1));
        assertArrayEquals(new byte[]{0, 0, 0, 5, 57}, MapIdCodec.xaero(1337));
        assertEquals(-123456789, MapIdCodec.parseXaero(MapIdCodec.xaero(-123456789)));
        assertThrows(IllegalArgumentException.class, () -> MapIdCodec.parseXaero(new byte[]{1, 0, 0, 0, 1}));
    }

    @Test
    void requestShapes() {
        // JourneyMap 1.16.5+, VoxelMap 1.19.2+ (and everything on 26.x)
        assertEquals(new Request(1, true), MapIdCodec.parseRequest(new byte[]{0, 42, 0}, null));
        // VoxelMap Forge 1.13.2 – 1.16.3
        assertEquals(new Request(1, false), MapIdCodec.parseRequest(new byte[]{0, 42, 0}, MapIdCodec.MINECRAFT_1_16_3));
        // VoxelMap LiteLoader 1.8.9 – 1.12.2, Fabric 1.14.4 – 1.19.x
        assertEquals(new Request(0, true), MapIdCodec.parseRequest(new byte[]{0, 0, 0, 42}, null));
        // VoxelMap Forge 1.12.2
        assertEquals(new Request(1, false), MapIdCodec.parseRequest(new byte[]{0, 0}, null));
        assertThrows(IllegalArgumentException.class, () -> MapIdCodec.parseRequest(new byte[]{0}, null));
        assertThrows(IllegalArgumentException.class, () -> MapIdCodec.parseRequest(new byte[]{0, 7}, null));
    }

    @Test
    void responsesMirrorTheRequest() {
        assertArrayEquals(new byte[]{0, 42, 1, 49}, MapIdCodec.response(new Request(1, true), 1));
        assertArrayEquals(new byte[]{42, 1, 49}, MapIdCodec.response(new Request(0, true), 1));
        assertArrayEquals(new byte[]{0, 1, 49}, MapIdCodec.response(new Request(1, false), 1));
        assertArrayEquals(new byte[]{0, 42, 2, 45, 55}, MapIdCodec.response(new Request(1, true), -7));
    }

    @Test
    void parsesResponsesForRewriting() {
        assertEquals(new PrefixedId(1, false, 1), MapIdCodec.parsePrefixed(new byte[]{0, 1, 49}));
        assertEquals(new PrefixedId(1, true, 1), MapIdCodec.parsePrefixed(new byte[]{0, 42, 1, 49}));
        assertEquals(new PrefixedId(0, true, 1), MapIdCodec.parsePrefixed(new byte[]{42, 1, 49}));
        assertThrows(IllegalArgumentException.class, () -> MapIdCodec.parsePrefixed(new byte[]{0, 0, 0}));
        assertThrows(IllegalArgumentException.class, () -> MapIdCodec.parsePrefixed(new byte[]{0, 42, 5, 49}));
    }

    @Test
    void derivedIdsAreStableAndDistinct() {
        List<String> servers = List.of("s1", "s2", "s3", "s4", "limbo", "waiting");
        Set<Integer> ids = servers.stream().map(MapIdCodec::deriveId).collect(Collectors.toSet());
        assertEquals(servers.size(), ids.size());
        // String.hashCode is specified by the JLS, so this never changes between runs or Java versions.
        assertEquals("adamkovelospawn:s3".hashCode(), MapIdCodec.deriveId("s3"));
    }
}
