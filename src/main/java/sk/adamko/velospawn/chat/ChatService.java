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

import com.velocitypowered.api.scheduler.ScheduledTask;
import sk.adamko.velospawn.chat.DiscordRelay.Place;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
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

    /** How long a join/switch post waits for the companion to report the player's world. */
    static final int WORLD_WAIT_SECONDS = 3;

    private record WorldInfo(String server, String name, String environment) {
    }

    private record Pending(String server, Consumer<Place> post, ScheduledTask fallback) {
    }

    /** Each player's current world, as reported by the companion on their current server. */
    private final Map<UUID, WorldInfo> worlds = new ConcurrentHashMap<>();
    /** Whether the companion runs on a server; only there is a join post worth holding for the world. */
    private volatile Predicate<String> hasCompanion = server -> false;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

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
        discord.chat(player.getUsername(), place(player.getUniqueId(), server.get()), event.getMessage());
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        if (!enabled()) {
            return;
        }
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        String server = event.getServer().getServerInfo().getName();
        flushPending(id);
        worlds.remove(id); // a new server: the world is reported again by its companion
        if (excluded(server)) {
            return; // e.g. a trip through the waiting room: announced once they land on a season
        }
        String previous = announced.put(id, server);
        if (previous != null) {
            if (!previous.equals(server)) {
                Place from = Place.of(previous, display(previous));
                announceOnDiscord(id, server, at -> discord.switchServer(player.getUsername(), from, at));
            }
            return;
        }
        Component line = Messages.render(config.get().chat().joinFormat(),
                "player", player.getUsername(), "prefix", prefix(player),
                "server", server, "display", display(server));
        broadcastToOthers(line, server);
        announceOnDiscord(id, server, at -> discord.join(player.getUsername(), at));
    }

    /** Posts now, or (where the companion runs) once it has said which world the player is in. */
    private void announceOnDiscord(UUID id, String server, Consumer<Place> post) {
        if (!hasCompanion.test(server)) {
            post.accept(place(id, server));
            return;
        }
        ScheduledTask fallback = proxy.getScheduler().buildTask(plugin, () -> firePending(id, server))
                .delay(WORLD_WAIT_SECONDS, TimeUnit.SECONDS)
                .schedule();
        pending.put(id, new Pending(server, post, fallback));
    }

    private void firePending(UUID id, String server) {
        Pending p = pending.get(id);
        if (p != null && p.server().equals(server) && pending.remove(id, p)) {
            p.fallback().cancel();
            p.post().accept(place(id, server));
        }
    }

    private void flushPending(UUID id) {
        Pending p = pending.remove(id);
        if (p != null) {
            p.fallback().cancel();
            p.post().accept(place(id, p.server()));
        }
    }

    private Place place(UUID id, String server) {
        WorldInfo w = worlds.get(id);
        String label = w != null && w.server().equals(server)
                ? config.get().chat().discord().worldLabel(w.name(), w.environment())
                : "";
        return new Place(server, display(server), label);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        replyTo.remove(id);
        replyTo.values().removeIf(id::equals);
        flushPending(id); // a join that is still waiting for its world goes out before the leave
        String server = announced.remove(id);
        Place at = server == null ? null : place(id, server);
        worlds.remove(id);
        if (server == null || !enabled()) {
            return;
        }
        Component line = Messages.render(config.get().chat().leaveFormat(),
                "player", player.getUsername(), "prefix", prefix(player),
                "server", server, "display", display(server));
        broadcastToOthers(line, server);
        discord.leave(player.getUsername(), at);
    }

    // ------------------------------------------------------------------ season-server events

    /** Tells whether the companion runs on a server; set by the companion channel. */
    public void setCompanionCheck(Predicate<String> hasCompanion) {
        this.hasCompanion = hasCompanion;
    }

    /**
     * World changes, AFK, deaths and advancements from the companion plugin (already parsed and checked
     * to come from the server by the companion channel), mirrored to Discord.
     */
    public void onCompanionEvent(com.velocitypowered.api.proxy.ServerConnection conn, com.google.gson.JsonObject o) {
        String server = conn.getServerInfo().getName();
        if (!enabled() || excluded(server)) {
            return;
        }
        UUID id = conn.getPlayer().getUniqueId();
        String player = conn.getPlayer().getUsername(); // trust the connection, not the payload
        if (o.has("world") && o.get("world").isJsonPrimitive()) {
            String env = o.has("env") && o.get("env").isJsonPrimitive() ? o.get("env").getAsString() : null;
            worlds.put(id, new WorldInfo(server, o.get("world").getAsString(), env));
        }
        firePending(id, server); // a join/switch waiting for the world can go out now
        String type = o.has("type") ? o.get("type").getAsString() : "";
        String text = o.has("text") ? o.get("text").getAsString() : "";
        Place at = place(id, server);
        switch (type) {
            case "world" -> {
                // only updates the world shown in later posts
            }
            case "afk" -> discord.afk(player, at, o.has("afk") && o.get("afk").getAsBoolean());
            case "death" -> {
                if (!text.isBlank()) {
                    discord.death(player, at, text);
                }
            }
            case "advancement" -> {
                if (!text.isBlank()) {
                    discord.advancement(player, at, text);
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
