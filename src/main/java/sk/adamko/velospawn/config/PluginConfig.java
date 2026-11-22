package sk.adamko.velospawn.config;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable snapshot of config.yml. A reload swaps the whole snapshot. */
public record PluginConfig(
        String panelUrl,
        String apiKey,
        String authServer,
        String waitingServer,
        String newest,
        Map<String, SeasonConfig> seasons,
        boolean rememberLastServer,
        boolean prestartOnConnect,
        int startupTimeoutSeconds,
        int pollIntervalSeconds,
        int pingTimeoutMs,
        ChatConfig chat,
        Map<String, String> messages,
        MapModsConfig mapMods) {

    public record SeasonConfig(
            String name,
            String panelId,
            String displayName,
            int idleShutdownMinutes,
            int joinDelaySeconds,
            Integer mapId) {

        /** Seasons without a map id override. */
        public SeasonConfig(String name, String panelId, String displayName, int idleShutdownMinutes,
                            int joinDelaySeconds) {
            this(name, panelId, displayName, idleShutdownMinutes, joinDelaySeconds, null);
        }
    }

    /** World-ID packets for Xaero's Minimap/World Map, JourneyMap and VoxelMap. */
    public record MapModsConfig(
            boolean enabled,
            int xaeroRepeatOnSwitch,
            int xaeroResendSeconds) {
    }

    public record ChatConfig(
            boolean enabled,
            String format,
            String joinFormat,
            String leaveFormat,
            Set<String> excludeServers,
            DiscordConfig discord) {
    }

    /**
     * Discord webhook mirror. Text values use {@code {player}}, {@code {server}}, {@code {display}},
     * {@code {short}}, {@code {from}}, {@code {from_display}}, {@code {from_short}} and {@code {message}}; an empty value turns that event off.
     */
    public record DiscordConfig(
            String webhook,
            String username,
            String avatar,
            String chat,
            String join,
            String switchServer,
            String leave,
            String botToken,
            String channelId,
            String toMinecraft,
            String afk,
            String afkReturn,
            String death,
            String advancement,
            Map<String, String> worlds) {

        public static final DiscordConfig NONE =
                new DiscordConfig("", "", "", "", "", "", "", "", "", "", "", "", "", "", Map.of());

        /** Label for a world: by its exact name first, then by its kind (normal, nether, the_end). */
        public String worldLabel(String worldName, String environment) {
            if (worldName == null || worldName.isBlank()) {
                return "";
            }
            String byName = worlds.get(worldName);
            if (byName != null) {
                return byName;
            }
            String byEnv = environment == null ? null : worlds.get(environment.toLowerCase(java.util.Locale.ROOT));
            return byEnv != null ? byEnv : worldName;
        }

        public boolean enabled() {
            return webhook != null && !webhook.isBlank();
        }
    }

    public boolean isSeason(String serverName) {
        return serverName != null && seasons.containsKey(serverName);
    }

    public SeasonConfig season(String name) {
        return seasons.get(name);
    }

    public List<String> seasonNames() {
        return List.copyOf(seasons.keySet());
    }

    /** The map-mod world id for a server: the season's override, else derived from the server name. */
    public int mapId(String serverName) {
        SeasonConfig sc = seasons.get(serverName);
        if (sc != null && sc.mapId() != null) {
            return sc.mapId();
        }
        return sk.adamko.velospawn.mapmod.MapIdCodec.deriveId(serverName);
    }

    public String message(String key) {
        return messages.getOrDefault(key, key);
    }
}
