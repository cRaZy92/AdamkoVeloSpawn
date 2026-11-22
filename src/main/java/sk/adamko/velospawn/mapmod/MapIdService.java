package sk.adamko.velospawn.mapmod;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.slf4j.Logger;
import sk.adamko.velospawn.config.PluginConfig;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Gives every server its own world ID for map mods, like MapModCompanion's proxy module, but
 * without needing anything on the backends.
 *
 * <ul>
 *   <li>JourneyMap / VoxelMap ask on {@code worldinfo:world_id}; the proxy answers for the server the
 *       player is on and never forwards the request.</li>
 *   <li>Xaero's mods expect the server to push the ID on join and after every respawn. The proxy can't
 *       see respawns, so it pushes right after each server switch (a few times, 1 s apart) and then
 *       re-sends periodically, which restores the ID within seconds after a death or portal.</li>
 *   <li>If a backend plugin sends its own IDs on these channels, they are rewritten to the proxy's ID.</li>
 * </ul>
 * Clients without these mods ignore the packets.
 */
public final class MapIdService {

    public static final MinecraftChannelIdentifier WORLD_ID = MinecraftChannelIdentifier.from("worldinfo:world_id");
    public static final MinecraftChannelIdentifier XAERO_MINIMAP = MinecraftChannelIdentifier.from("xaerominimap:main");
    public static final MinecraftChannelIdentifier XAERO_WORLDMAP = MinecraftChannelIdentifier.from("xaeroworldmap:main");
    private static final List<MinecraftChannelIdentifier> XAERO = List.of(XAERO_MINIMAP, XAERO_WORLDMAP);

    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final Supplier<PluginConfig> config;
    private volatile ScheduledTask resendTask;
    private volatile int scheduledResendSeconds = -1;

    public MapIdService(Object plugin, ProxyServer proxy, Logger logger, Supplier<PluginConfig> config) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.config = config;
    }

    private boolean enabled() {
        return config.get().mapMods().enabled();
    }

    /** Registers the channels (so their messages reach this plugin) and (re)starts the periodic re-send. */
    public synchronized void start() {
        proxy.getChannelRegistrar().register(WORLD_ID, XAERO_MINIMAP, XAERO_WORLDMAP);
        int seconds = enabled() ? config.get().mapMods().xaeroResendSeconds() : 0;
        if (seconds == scheduledResendSeconds) {
            return;
        }
        stop();
        scheduledResendSeconds = seconds;
        if (seconds > 0) {
            resendTask = proxy.getScheduler().buildTask(plugin, this::resendAll)
                    .delay(seconds, TimeUnit.SECONDS)
                    .repeat(seconds, TimeUnit.SECONDS)
                    .schedule();
        }
    }

    public synchronized void stop() {
        ScheduledTask t = resendTask;
        if (t != null) {
            t.cancel();
        }
        resendTask = null;
        scheduledResendSeconds = -1;
    }

    /** Logged at startup so the IDs can be looked up (e.g. to rename an old map folder). */
    public String describeIds() {
        PluginConfig cfg = config.get();
        StringBuilder sb = new StringBuilder();
        for (String s : cfg.seasonNames()) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(s).append('=').append(cfg.mapId(s));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ Xaero push

    @Subscribe
    public void onPostConnect(ServerPostConnectEvent event) {
        if (!enabled()) {
            return;
        }
        Player player = event.getPlayer();
        Optional<String> server = serverOf(player);
        if (server.isEmpty()) {
            return;
        }
        int repeats = config.get().mapMods().xaeroRepeatOnSwitch();
        sendXaero(player, server.get());
        for (int i = 1; i < repeats; i++) {
            proxy.getScheduler().buildTask(plugin, () -> {
                // Only if they're still where we sent it for.
                if (player.isActive() && serverOf(player).equals(server)) {
                    sendXaero(player, server.get());
                }
            }).delay(i, TimeUnit.SECONDS).schedule();
        }
    }

    private void resendAll() {
        if (!enabled()) {
            return;
        }
        for (Player p : proxy.getAllPlayers()) {
            serverOf(p).ifPresent(s -> sendXaero(p, s));
        }
    }

    private void sendXaero(Player player, String server) {
        byte[] payload = MapIdCodec.xaero(config.get().mapId(server));
        for (ChannelIdentifier channel : XAERO) {
            player.sendPluginMessage(channel, payload);
        }
    }

    // ------------------------------------------------------------------ requests and rewrites

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!enabled()) {
            return;
        }
        ChannelIdentifier channel = event.getIdentifier();
        boolean worldId = channel.equals(WORLD_ID);
        boolean xaero = XAERO.contains(channel);
        if (!worldId && !xaero) {
            return;
        }

        if (event.getSource() instanceof Player player && event.getTarget() instanceof ServerConnection conn) {
            if (!worldId) {
                return; // clients don't send on Xaero channels in a way we care about
            }
            answerRequest(event, player, conn.getServerInfo().getName());
        } else if (event.getSource() instanceof ServerConnection conn && event.getTarget() instanceof Player player) {
            rewriteFromBackend(event, player, conn.getServerInfo().getName(), worldId);
        }
    }

    private void answerRequest(PluginMessageEvent event, Player player, String server) {
        MapIdCodec.Request request;
        try {
            request = MapIdCodec.parseRequest(event.getData(), player.getProtocolVersion().getProtocol());
        } catch (IllegalArgumentException e) {
            logger.debug("Ignoring malformed world_id request from {}: {}", player.getUsername(), e.getMessage());
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        int id = config.get().mapId(server);
        player.sendPluginMessage(WORLD_ID, MapIdCodec.response(request, id));
        logger.debug("Answered world_id request from {} on {} with {}", player.getUsername(), server, id);
    }

    private void rewriteFromBackend(PluginMessageEvent event, Player player, String server, boolean worldId) {
        int id = config.get().mapId(server);
        byte[] rewritten;
        try {
            if (worldId) {
                MapIdCodec.PrefixedId original = MapIdCodec.parsePrefixed(event.getData());
                rewritten = MapIdCodec.prefixed(new MapIdCodec.PrefixedId(original.padding(), original.magic(), id));
            } else {
                MapIdCodec.parseXaero(event.getData());
                rewritten = MapIdCodec.xaero(id);
            }
        } catch (IllegalArgumentException e) {
            return; // not an id packet we understand: leave it alone
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        player.sendPluginMessage(event.getIdentifier(), rewritten);
    }

    private static Optional<String> serverOf(Player player) {
        return player.getCurrentServer().map(c -> c.getServerInfo().getName());
    }
}
