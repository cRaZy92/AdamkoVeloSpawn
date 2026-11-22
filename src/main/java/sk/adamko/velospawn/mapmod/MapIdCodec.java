package sk.adamko.velospawn.mapmod;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Wire formats of the map-mod world-ID packets. Ported from MapModCompanion
 * (https://github.com/turikhay/MapModCompanion, MIT License, © 2022 Artur Khusainov).
 *
 * <ul>
 *   <li>Xaero's Minimap / World Map ({@code xaerominimap:main}, {@code xaeroworldmap:main}):
 *       server push, {@code [0x00][int32 big-endian id]} ("LevelMapProperties").</li>
 *   <li>JourneyMap / VoxelMap ({@code worldinfo:world_id}): the client sends a request, the server
 *       answers {@code [0x00 × padding][42 if magic][length][id as UTF-8 decimal]} using the padding
 *       and magic byte the request used.</li>
 * </ul>
 */
public final class MapIdCodec {

    static final int MAGIC_MARKER = 42;
    /** Protocol number of Minecraft 1.16.3; older VoxelMap Forge builds answer without the magic byte. */
    static final int MINECRAFT_1_16_3 = 753;

    private MapIdCodec() {
    }

    /** Shape of a world_id request, which the response has to mirror. */
    public record Request(int padding, boolean magic) {
    }

    /** A decoded world_id response. */
    public record PrefixedId(int padding, boolean magic, int id) {
    }

    public static byte[] xaero(int id) {
        return ByteBuffer.allocate(5).put((byte) 0).putInt(id).array();
    }

    /** Reads the id from a Xaero LevelMapProperties packet. */
    public static int parseXaero(byte[] data) {
        if (data.length < 5 || data[0] != 0) {
            throw new IllegalArgumentException("not a LevelMapProperties packet");
        }
        return ByteBuffer.wrap(data, 1, 4).getInt();
    }

    /**
     * Parses a world_id request. {@code protocolVersion} may be null if unknown.
     * Mirrors MapModCompanion's PrefixedIdRequest.parse.
     */
    public static Request parseRequest(byte[] payload, Integer protocolVersion) {
        int padding = 0;
        while (padding < payload.length && payload[padding] == 0) {
            padding++;
        }
        if (padding == payload.length) {
            if (padding == 2) {
                return new Request(1, false); // VoxelMap Forge 1.12.2
            }
            throw new IllegalArgumentException("ambiguous zero-filled request");
        }
        if (payload[padding] != MAGIC_MARKER) {
            throw new IllegalArgumentException("first byte after zero padding is not the magic byte");
        }
        return switch (padding) {
            case 1 -> protocolVersion != null && protocolVersion <= MINECRAFT_1_16_3
                    ? new Request(1, false)
                    : new Request(1, true);
            case 3 -> new Request(0, true);
            default -> throw new IllegalArgumentException("unexpected prefix length " + padding);
        };
    }

    public static byte[] response(Request request, int id) {
        return prefixed(new PrefixedId(request.padding(), request.magic(), id));
    }

    public static byte[] prefixed(PrefixedId id) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < id.padding(); i++) {
            out.write(0);
        }
        if (id.magic()) {
            out.write(MAGIC_MARKER);
        }
        byte[] digits = Integer.toString(id.id()).getBytes(StandardCharsets.UTF_8);
        out.write(digits.length);
        out.writeBytes(digits);
        return out.toByteArray();
    }

    /** Parses a world_id response (e.g. one sent by a backend plugin) so it can be rewritten. */
    public static PrefixedId parsePrefixed(byte[] data) {
        int i = 0;
        while (i < data.length && data[i] == 0) {
            i++;
        }
        if (i == data.length) {
            throw new IllegalArgumentException("zero-filled prefixed id packet");
        }
        int padding = i;
        boolean magic = data[i] == MAGIC_MARKER;
        if (magic) {
            i++;
        }
        if (i >= data.length) {
            throw new IllegalArgumentException("truncated prefixed id packet");
        }
        int length = data[i++];
        if (length < 0 || i + length > data.length) {
            throw new IllegalArgumentException("incorrect length in prefixed id packet");
        }
        String digits = new String(data, i, length, StandardCharsets.UTF_8);
        try {
            return new PrefixedId(padding, magic, Integer.parseInt(digits));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("non-numeric id in prefixed id packet", e);
        }
    }

    /** Stable per-server id: server names never change, so maps stay put across restarts and versions. */
    public static int deriveId(String serverName) {
        return ("adamkovelospawn:" + serverName).hashCode();
    }
}
