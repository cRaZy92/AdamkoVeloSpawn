package sk.adamko.velospawn.chat;

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

import java.net.http.HttpClient;
import java.util.Map;
import java.util.Optional;
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
    private final DiscordRelay discord;

    private final Map<UUID, UUID> replyTo = new ConcurrentHashMap<>();
    /** Last non-excluded server each player was announced on; absent until their network join. */
    private final Map<UUID, String> announced = new ConcurrentHashMap<>();
    private CommandMeta msgMeta;
    private CommandMeta replyMeta;

    public ChatService(Object plugin, ProxyServer proxy, Logger logger, Supplier<PluginConfig> config,
                       Messages messages, HttpClient http) {
        this(plugin, proxy, logger, config, messages,
                new DiscordRelay(() -> config.get().chat().discord(), logger, http));
    }

    public ChatService(Object plugin, ProxyServer proxy, Logger logger, Supplier<PluginConfig> config,
                       Messages messages, DiscordRelay discord) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.config = config;
        this.messages = messages;
        this.discord = discord;
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
        discord.chat(player.getUsername(), server.get(), display(server.get()), event.getMessage());
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        if (!enabled()) {
            return;
        }
        Player player = event.getPlayer();
        String server = event.getServer().getServerInfo().getName();
        if (excluded(server)) {
            return; // e.g. a trip through the waiting room: announced once they land on a season
        }
        String previous = announced.put(player.getUniqueId(), server);
        if (previous != null) {
            if (!previous.equals(server)) {
                discord.switchServer(player.getUsername(), previous, display(previous), server, display(server));
            }
            return;
        }
        Component line = Messages.render(config.get().chat().joinFormat(),
                "player", player.getUsername(), "prefix", prefix(player),
                "server", server, "display", display(server));
        broadcastToOthers(line, server);
        discord.join(player.getUsername(), server, display(server));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        replyTo.remove(id);
        replyTo.values().removeIf(id::equals);
        String server = announced.remove(id);
        if (server == null || !enabled()) {
            return;
        }
        Component line = Messages.render(config.get().chat().leaveFormat(),
                "player", player.getUsername(), "prefix", prefix(player),
                "server", server, "display", display(server));
        broadcastToOthers(line, server);
        discord.leave(player.getUsername(), server, display(server));
    }

    // ------------------------------------------------------------------ season-server events

    /** Channel the AdamkoVeloSpawn-Companion plugin on the season servers sends events on. */
    public static final com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier BACKEND_EVENTS =
            com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier.from("adamkovelospawn:events");

    public void registerChannel() {
        proxy.getChannelRegistrar().register(BACKEND_EVENTS);
    }

    /**
     * AFK, deaths and advancements from the companion plugin, mirrored to Discord. Only a season
     * server can send these: the same channel from a client is dropped, and nothing on it is forwarded.
     */
    @Subscribe
    public void onPluginMessage(com.velocitypowered.api.event.connection.PluginMessageEvent event) {
        if (!event.getIdentifier().equals(BACKEND_EVENTS)) {
            return;
        }
        event.setResult(com.velocitypowered.api.event.connection.PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof com.velocitypowered.api.proxy.ServerConnection conn) || !enabled()) {
            return;
        }
        String server = conn.getServerInfo().getName();
        if (excluded(server)) {
            return;
        }
        com.google.gson.JsonObject o;
        try {
            o = com.google.gson.JsonParser.parseString(
                    new String(event.getData(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            logger.debug("Ignoring malformed companion message from {}: {}", server, e.toString());
            return;
        }
        String player = conn.getPlayer().getUsername(); // trust the connection, not the payload
        String type = o.has("type") ? o.get("type").getAsString() : "";
        String text = o.has("text") ? o.get("text").getAsString() : "";
        switch (type) {
            case "afk" -> discord.afk(player, server, display(server), o.has("afk") && o.get("afk").getAsBoolean());
            case "death" -> {
                if (!text.isBlank()) {
                    discord.death(player, server, display(server), text);
                }
            }
            case "advancement" -> {
                if (!text.isBlank()) {
                    discord.advancement(player, server, display(server), text);
                }
            }
            default -> logger.debug("Unknown companion event '{}' from {}", type, server);
        }
    }

    // ------------------------------------------------------------------ Discord -> Minecraft

    /** A message from the Discord channel: shown to everyone on a non-excluded server. */
    public void fromDiscord(DiscordBot.Chat message) {
        if (!enabled()) {
            return;
        }
        String template = config.get().chat().discord().toMinecraft();
        if (template == null || template.isBlank()) {
            return;
        }
        Component name = message.color() == null
                ? Component.text(message.name())
                : Component.text(message.name(), net.kyori.adventure.text.format.TextColor.color(message.color()));
        Component line = Messages.render(template, "name", name, "message", message.text());
        for (Player p : proxy.getAllPlayers()) {
            Optional<String> s = serverOf(p);
            if (s.isPresent() && !excluded(s.get())) {
                p.sendMessage(line);
            }
        }
        logger.info("[discord] {}: {}", message.name(), message.text());
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
}
