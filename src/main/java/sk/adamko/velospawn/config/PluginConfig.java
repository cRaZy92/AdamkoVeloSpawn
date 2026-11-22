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
        Map<String, String> messages) {

    public record SeasonConfig(
            String name,
            String panelId,
            String displayName,
            int idleShutdownMinutes,
            int joinDelaySeconds) {
    }

    public record ChatConfig(
            boolean enabled,
            String format,
            String joinFormat,
            String leaveFormat,
            Set<String> excludeServers,
            String discordWebhook) {
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

    public String message(String key) {
        return messages.getOrDefault(key, key);
    }
}
