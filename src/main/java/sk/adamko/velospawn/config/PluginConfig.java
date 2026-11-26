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
        MapModsConfig mapMods,
        SeasonsMenuConfig seasonsMenu) {

    /** Without a {@code seasons-menu} section: the defaults. */
    public PluginConfig(String panelUrl, String apiKey, String authServer, String waitingServer, String newest,
                        Map<String, SeasonConfig> seasons, boolean rememberLastServer, boolean prestartOnConnect,
                        int startupTimeoutSeconds, int pollIntervalSeconds, int pingTimeoutMs, ChatConfig chat,
                        Map<String, String> messages, MapModsConfig mapMods) {
        this(panelUrl, apiKey, authServer, waitingServer, newest, seasons, rememberLastServer, prestartOnConnect,
                startupTimeoutSeconds, pollIntervalSeconds, pingTimeoutMs, chat, messages, mapMods,
                new SeasonsMenuConfig(true));
    }

    /** The /seasons menu; {@code showNumbers}: the season number as the item count. */
    public record SeasonsMenuConfig(boolean showNumbers) {
    }

    public record SeasonConfig(
            String name,
            String panelId,
            String displayName,
            int idleShutdownMinutes,
            int joinDelaySeconds,
            Integer mapId,
            String icon,
            String color) {

        /** Item material shown for seasons without an {@code icon:} in the /seasons menu. */
        public static final String DEFAULT_ICON = "PAPER";

        /** Seasons without a colour (their name takes the colour of the text around it). */
        public SeasonConfig(String name, String panelId, String displayName, int idleShutdownMinutes,
                            int joinDelaySeconds, Integer mapId, String icon) {
            this(name, panelId, displayName, idleShutdownMinutes, joinDelaySeconds, mapId, icon, "");
        }

        /** Seasons with the default /seasons icon. */
        public SeasonConfig(String name, String panelId, String displayName, int idleShutdownMinutes,
                            int joinDelaySeconds, Integer mapId) {
            this(name, panelId, displayName, idleShutdownMinutes, joinDelaySeconds, mapId, DEFAULT_ICON);
        }

        /** Seasons without a map id override and with the default icon. */
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

    /**
     * Network chat. The {@code *Format} lines are shown in game to players on the other seasons; an empty
     * one turns that line off.
     */
    public record ChatConfig(
            boolean enabled,
            String format,
            String joinFormat,
            String leaveFormat,
            Set<String> excludeServers,
            DiscordConfig discord,
            String switchFormat,
            String afkFormat,
            String afkReturnFormat,
            String deathFormat,
            String advancementFormat) {

        public ChatConfig(boolean enabled, String format, String joinFormat, String leaveFormat,
                          Set<String> excludeServers, DiscordConfig discord) {
            this(enabled, format, joinFormat, leaveFormat, excludeServers, discord, "", "", "", "", "");
        }

        public ChatConfig withEnabled(boolean on, DiscordConfig discord) {
            return new ChatConfig(on, format, joinFormat, leaveFormat, excludeServers, discord,
                    switchFormat, afkFormat, afkReturnFormat, deathFormat, advancementFormat);
        }
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
