package sk.adamko.velospawn.config;

import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Loads config.yml, writing the bundled default on first start. */
public final class ConfigLoader {

    private static final String RESOURCE = "/config.yml";

    private ConfigLoader() {
    }

    public static PluginConfig load(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        Path file = dataDirectory.resolve("config.yml");
        if (Files.notExists(file)) {
            try (InputStream in = Objects.requireNonNull(
                    ConfigLoader.class.getResourceAsStream(RESOURCE), "bundled config.yml missing")) {
                Files.copy(in, file);
            }
        }

        ConfigurationNode root = YamlConfigurationLoader.builder()
                .path(file)
                .nodeStyle(NodeStyle.BLOCK)
                .build()
                .load();
        ConfigurationNode defaults = loadDefaults();

        // Fill in keys added in newer versions, but never re-add seasons the user removed.
        for (String section : List.of("panel", "routing", "startup", "chat", "map-mods", "messages")) {
            root.node(section).mergeFrom(defaults.node(section));
        }
        return parse(root);
    }

    private static PluginConfig.DiscordConfig parseDiscord(ConfigurationNode chat) {
        ConfigurationNode d = chat.node("discord");
        String webhook = d.node("webhook").getString("");
        if (webhook.isBlank()) {
            webhook = chat.node("discord-webhook").getString(""); // older configs had chat.discord-webhook
        }
        return new PluginConfig.DiscordConfig(
                webhook.trim(),
                d.node("username").getString(""),
                d.node("avatar").getString(""),
                d.node("chat").getString(""),
                d.node("join").getString(""),
                d.node("switch").getString(""),
                d.node("leave").getString(""));
    }

    static ConfigurationNode loadDefaults() throws ConfigurateException {
        return YamlConfigurationLoader.builder()
                .source(() -> new BufferedReader(new InputStreamReader(
                        Objects.requireNonNull(ConfigLoader.class.getResourceAsStream(RESOURCE)),
                        StandardCharsets.UTF_8)))
                .build()
                .load();
    }

    static PluginConfig parse(ConfigurationNode root) throws SerializationException {
        ConfigurationNode servers = root.node("servers");
        Map<String, PluginConfig.SeasonConfig> seasons = new LinkedHashMap<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> e : servers.node("seasons").childrenMap().entrySet()) {
            String name = String.valueOf(e.getKey());
            ConfigurationNode n = e.getValue();
            seasons.put(name, new PluginConfig.SeasonConfig(
                    name,
                    n.node("panel-id").getString(""),
                    n.node("display-name").getString(name),
                    n.node("idle-shutdown-minutes").getInt(15),
                    n.node("join-delay-seconds").getInt(5),
                    n.node("map-id").virtual() || n.node("map-id").isNull() ? null : n.node("map-id").getInt()));
        }

        ConfigurationNode chat = root.node("chat");
        PluginConfig.ChatConfig chatConfig = new PluginConfig.ChatConfig(
                chat.node("enabled").getBoolean(false),
                chat.node("format").getString(""),
                chat.node("join-format").getString(""),
                chat.node("leave-format").getString(""),
                new LinkedHashSet<>(chat.node("exclude-servers").getList(String.class, List.of())),
                parseDiscord(chat));

        ConfigurationNode maps = root.node("map-mods");
        PluginConfig.MapModsConfig mapMods = new PluginConfig.MapModsConfig(
                maps.node("enabled").getBoolean(true),
                Math.max(1, maps.node("xaero-repeat-on-switch").getInt(3)),
                Math.max(0, maps.node("xaero-resend-seconds").getInt(5)));

        Map<String, String> messages = new LinkedHashMap<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> e : root.node("messages").childrenMap().entrySet()) {
            messages.put(String.valueOf(e.getKey()), e.getValue().getString(""));
        }

        String panelUrl = root.node("panel", "url").getString("");
        while (panelUrl.endsWith("/")) {
            panelUrl = panelUrl.substring(0, panelUrl.length() - 1);
        }

        return new PluginConfig(
                panelUrl,
                root.node("panel", "api-key").getString(""),
                servers.node("auth").getString("limbo"),
                servers.node("waiting").getString("waiting"),
                servers.node("newest").getString(""),
                java.util.Collections.unmodifiableMap(seasons),
                root.node("routing", "remember-last-server").getBoolean(true),
                root.node("routing", "prestart-on-connect").getBoolean(true),
                Math.max(10, root.node("startup", "timeout-seconds").getInt(180)),
                Math.max(1, root.node("startup", "poll-interval-seconds").getInt(2)),
                Math.max(100, root.node("startup", "ping-timeout-ms").getInt(1000)),
                chatConfig,
                java.util.Collections.unmodifiableMap(messages),
                mapMods);
    }
}
