package sk.adamko.velospawn.companion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;
import sk.adamko.velospawn.chat.ChatService;
import sk.adamko.velospawn.config.PluginConfig;
import sk.adamko.velospawn.store.SeasonStatsStore;
import sk.adamko.velospawn.store.SeasonStatsStore.SeasonStats;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * The plugin-message channel to the AdamkoVeloSpawn-Companion plugin on the season servers. Always on,
 * whatever {@code chat.enabled} says: chat events go to {@link ChatService} (which applies its own
 * switch), season stats to the {@link SeasonStatsStore}, menu clicks to the /seasons menu.
 *
 * <p>Paper only sends plugin messages on channels the player's connection registered, and a vanilla
 * client never registers ours, so the proxy registers it on the player's behalf after each switch.
 */
public final class CompanionChannel {

    /** Channel the companion plugin talks on, both ways. */
    public static final MinecraftChannelIdentifier EVENTS = MinecraftChannelIdentifier.from("adamkovelospawn:events");
    private static final MinecraftChannelIdentifier REGISTER = MinecraftChannelIdentifier.from("minecraft:register");

    private final ProxyServer proxy;
    private final Logger logger;
    private final Supplier<PluginConfig> config;
    private final ChatService chat;
    private final SeasonStatsStore stats;
    /** Servers the companion has been heard from since the proxy started. */
    private final Set<String> companionServers = ConcurrentHashMap.newKeySet();
    /**
     * Servers whose companion can draw the /seasons chest. Only companions with the menu send stats (and
     * they send them on every arrival), so an older companion still gets the chat list instead of nothing.
     */
    private final Set<String> menuServers = ConcurrentHashMap.newKeySet();
    private volatile BiConsumer<Player, String> menuClickHandler = (player, season) -> { };

    public CompanionChannel(ProxyServer proxy, Logger logger, Supplier<PluginConfig> config, ChatService chat,
                            SeasonStatsStore stats) {
        this.proxy = proxy;
        this.logger = logger;
        this.config = config;
        this.chat = chat;
        this.stats = stats;
        chat.setCompanionCheck(this::hasCompanion);
    }

    public void registerChannel() {
        proxy.getChannelRegistrar().register(EVENTS);
    }

    public boolean hasCompanion(String server) {
        return companionServers.contains(server);
    }

    /** True once this server's companion has sent stats, i.e. it is new enough to draw the /seasons menu. */
    public boolean canDrawMenu(String server) {
        return menuServers.contains(server);
    }

    public void setMenuClickHandler(BiConsumer<Player, String> handler) {
        this.menuClickHandler = handler;
    }

    public void send(ServerConnection conn, JsonObject message) {
        conn.sendPluginMessage(EVENTS, message.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Subscribe
    public void onPostConnect(ServerPostConnectEvent event) {
        event.getPlayer().getCurrentServer().ifPresent(conn -> {
            if (config.get().isSeason(conn.getServerInfo().getName())) {
                conn.sendPluginMessage(REGISTER, EVENTS.getId().getBytes(StandardCharsets.UTF_8));
            }
        });
    }

    /** Only a season server can send these: the same channel from a client is dropped, never forwarded. */
    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(EVENTS)) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection conn)) {
            return;
        }
        String server = conn.getServerInfo().getName();
        JsonObject o;
        try {
            o = JsonParser.parseString(new String(event.getData(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            logger.debug("Ignoring malformed companion message from {}: {}", server, e.toString());
            return;
        }
        companionServers.add(server);
        String type = o.has("type") && o.get("type").isJsonPrimitive() ? o.get("type").getAsString() : "";
        switch (type) {
            case "stats" -> onStats(server, o);
            case "menu-click" -> {
                if (o.has("season") && o.get("season").isJsonPrimitive()) {
                    menuClickHandler.accept(conn.getPlayer(), o.get("season").getAsString());
                }
            }
            default -> chat.onCompanionEvent(conn, o);
        }
    }

    private void onStats(String server, JsonObject o) {
        if (!config.get().isSeason(server)) {
            return;
        }
        menuServers.add(server);
        try {
            JsonElement top = o.get("topName");
            SeasonStats s = new SeasonStats(
                    o.get("players").getAsInt(),
                    o.get("playtimeTicks").getAsLong(),
                    o.get("deaths").getAsLong(),
                    top == null || top.isJsonNull() ? null : top.getAsString(),
                    o.get("topTicks").getAsLong(),
                    o.get("gameTime").getAsLong(),
                    o.get("collectedAt").getAsLong());
            if (stats.put(server, s)) {
                logger.debug("Season stats from {}: {}", server, s);
            }
        } catch (RuntimeException e) {
            logger.debug("Ignoring malformed stats from {}: {}", server, e.toString());
        }
    }
}
