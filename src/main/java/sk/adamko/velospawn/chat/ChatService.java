package sk.adamko.velospawn.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;
import sk.adamko.velospawn.Messages;
import sk.adamko.velospawn.config.PluginConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Milestone 4: network chat relay, cross-server /msg + /r, network join/leave lines,
 * optional Discord webhook.
 *
 * <p>Chat is <em>forwarded</em>, never cancelled: modern clients sign chat and Velocity
 * disconnects players when a plugin denies a signed message. Players on the sender's own
 * server see the backend's normal line; everyone on other servers sees the network format.
 */
public final class ChatService {

    private static final short PRIORITY = -100;

    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final Supplier<PluginConfig> config;
    private final Messages messages;
    private final HttpClient http;

    private final Map<UUID, UUID> replyTo = new ConcurrentHashMap<>();
    private final Set<UUID> announced = ConcurrentHashMap.newKeySet();
    private CommandMeta msgMeta;
    private CommandMeta replyMeta;

    public ChatService(Object plugin, ProxyServer proxy, Logger logger, Supplier<PluginConfig> config,
                       Messages messages, HttpClient http) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.config = config;
        this.messages = messages;
        this.http = http;
    }

    private boolean enabled() {
        return config.get().chat().enabled();
    }

    /** Registers /msg and /r when chat is enabled, unregisters them when it is not. Safe to call on reload. */
    public synchronized void registerCommands() {
        CommandManager commands = proxy.getCommandManager();
        if (enabled() && msgMeta == null) {
            msgMeta = commands.metaBuilder("msg").aliases("tell", "w", "whisper", "m").plugin(plugin).build();
            replyMeta = commands.metaBuilder("r").aliases("reply").plugin(plugin).build();
            commands.register(msgMeta, new MsgCommand(this, proxy, messages, false));
            commands.register(replyMeta, new MsgCommand(this, proxy, messages, true));
        } else if (!enabled() && msgMeta != null) {
            commands.unregister(msgMeta);
            commands.unregister(replyMeta);
            msgMeta = null;
            replyMeta = null;
        }
    }

    // ------------------------------------------------------------------ chat relay

    @Subscribe(priority = PRIORITY)
    public void onChat(PlayerChatEvent event) {
        if (!enabled() || !event.getResult().isAllowed()) {
            return;
        }
        Player player = event.getPlayer();
        Optional<String> server = serverOf(player);
        if (server.isEmpty() || excluded(server.get())) {
            return;
        }
        PluginConfig cfg = config.get();
        Component line = Messages.render(cfg.chat().format(),
                "server", server.get(),
                "display", display(server.get()),
                "prefix", prefix(player),
                "player", player.getUsername(),
                "message", event.getMessage());
        broadcastToOthers(line, server.get());
        logger.info("[chat] [{}] {}: {}", server.get(), player.getUsername(), event.getMessage());
        webhook(player.getUsername(), "**[" + server.get() + "]** " + event.getMessage());
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        if (!enabled()) {
            return;
        }
        Player player = event.getPlayer();
        String server = event.getServer().getServerInfo().getName();
        if (excluded(server) || !announced.add(player.getUniqueId())) {
            return;
        }
        Component line = Messages.render(config.get().chat().joinFormat(),
                "player", player.getUsername(), "prefix", prefix(player),
                "server", server, "display", display(server));
        broadcastToOthers(line, server);
        webhook(null, "➕ **" + player.getUsername() + "** joined " + display(server));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        replyTo.remove(id);
        replyTo.values().removeIf(id::equals);
        if (!announced.remove(id) || !enabled()) {
            return;
        }
        String server = serverOf(player).orElse("");
        Component line = Messages.render(config.get().chat().leaveFormat(),
                "player", player.getUsername(), "prefix", prefix(player),
                "server", server, "display", display(server));
        broadcastToOthers(line, server);
        webhook(null, "➖ **" + player.getUsername() + "** left the network");
    }

    /** Everyone on a non-excluded server other than {@code sourceServer} (they already saw it locally). */
    private void broadcastToOthers(Component line, String sourceServer) {
        if (line.equals(Component.empty())) {
            return;
        }
        for (Player p : proxy.getAllPlayers()) {
            Optional<String> s = serverOf(p);
            if (s.isPresent() && !s.get().equals(sourceServer) && !excluded(s.get())) {
                p.sendMessage(line);
            }
        }
    }

    // ------------------------------------------------------------------ private messages

    void recordConversation(UUID a, UUID b) {
        replyTo.put(a, b);
        replyTo.put(b, a);
    }

    Optional<UUID> replyTarget(UUID player) {
        return Optional.ofNullable(replyTo.get(player));
    }

    void logPrivate(String from, String to, String message) {
        logger.info("[msg] {} -> {}: {}", from, to, message);
    }

    // ------------------------------------------------------------------ helpers

    private static Optional<String> serverOf(Player p) {
        return p.getCurrentServer().map(c -> c.getServerInfo().getName());
    }

    private boolean excluded(String server) {
        return config.get().chat().excludeServers().contains(server);
    }

    private String display(String server) {
        PluginConfig.SeasonConfig sc = config.get().season(server);
        return sc == null ? server : sc.displayName();
    }

    private Component prefix(Player player) {
        if (proxy.getPluginManager().getPlugin("luckperms").isEmpty()) {
            return Component.empty();
        }
        try {
            String raw = LuckPermsHook.prefix(player.getUniqueId());
            if (raw == null || raw.isEmpty()) {
                return Component.empty();
            }
            if (raw.indexOf('&') >= 0) {
                return LegacyComponentSerializer.legacyAmpersand().deserialize(raw);
            }
            if (raw.indexOf('§') >= 0) {
                return LegacyComponentSerializer.legacySection().deserialize(raw);
            }
            return MiniMessage.miniMessage().deserialize(raw);
        } catch (LinkageError | RuntimeException e) {
            logger.debug("LuckPerms prefix lookup failed", e);
            return Component.empty();
        }
    }

    private void webhook(String username, String content) {
        String url = config.get().chat().discordWebhook();
        if (url == null || url.isBlank()) {
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("content", content);
        if (username != null) {
            body.addProperty("username", username);
        }
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        body.add("allowed_mentions", mentions);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).whenComplete((resp, t) -> {
                if (t != null) {
                    logger.warn("Discord webhook failed: {}", t.toString());
                } else if (resp.statusCode() >= 300) {
                    logger.warn("Discord webhook returned HTTP {}", resp.statusCode());
                }
            });
        } catch (RuntimeException e) {
            logger.warn("Discord webhook misconfigured: {}", e.toString());
        }
    }
}
