package sk.adamko.velospawn.route;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent.ServerResult;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;
import sk.adamko.velospawn.Messages;
import sk.adamko.velospawn.config.PluginConfig;
import sk.adamko.velospawn.season.SeasonManager;
import sk.adamko.velospawn.store.LastServerStore;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Decides where every player goes. Runs after VeloAuth (which uses the highest priority and
 * denies connects for unauthenticated players), so an event that is still allowed here
 * belongs to an authenticated player.
 */
public final class Router {

    /** Below VeloAuth's Short.MAX_VALUE so its auth decision is already made. */
    private static final short PRIORITY = -100;

    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final Supplier<PluginConfig> config;
    private final SeasonManager seasons;
    private final WaitQueue queue;
    private final LastServerStore lastServers;
    private final Messages messages;

    /** Players kicked off a season into the waiting room, by season. Not auto-routed (avoids kick loops). */
    private final Map<UUID, String> parked = new ConcurrentHashMap<>();

    public Router(Object plugin, ProxyServer proxy, Logger logger, Supplier<PluginConfig> config,
                  SeasonManager seasons, WaitQueue queue, LastServerStore lastServers, Messages messages) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.config = config;
        this.seasons = seasons;
        this.queue = queue;
        this.lastServers = lastServers;
        this.messages = messages;
    }

    /** Last season if remembered and still configured, else the newest season. */
    public String destinationFor(UUID player) {
        PluginConfig cfg = config.get();
        if (cfg.rememberLastServer()) {
            Optional<String> last = lastServers.get(player);
            if (last.isPresent() && cfg.isSeason(last.get())) {
                return last.get();
            }
        }
        if (cfg.isSeason(cfg.newest())) {
            return cfg.newest();
        }
        return cfg.seasonNames().isEmpty() ? null : cfg.seasonNames().get(0);
    }

    // ------------------------------------------------------------------ events

    @Subscribe(priority = 0)
    public void onPostLogin(PostLoginEvent event) {
        // The player is in the auth limbo typing /login: warm up their destination meanwhile.
        if (!config.get().prestartOnConnect()) {
            return;
        }
        String dest = destinationFor(event.getPlayer().getUniqueId());
        if (dest != null) {
            seasons.ensureReady(dest);
        }
    }

    @Subscribe(priority = PRIORITY)
    public EventTask onPreConnect(ServerPreConnectEvent event) {
        if (!event.getResult().isAllowed()) {
            return null; // VeloAuth (or someone else) denied it: not ours to touch
        }
        PluginConfig cfg = config.get();
        Player player = event.getPlayer();
        RegisteredServer target = event.getResult().getServer().orElse(event.getOriginalServer());
        String targetName = target.getServerInfo().getName();
        String current = player.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse(null);

        if (targetName.equals(current)) {
            return null;
        }
        if (targetName.equals(cfg.waitingServer())) {
            return routeFromWaiting(event, player, current);
        }
        if (cfg.isSeason(targetName)) {
            return connectToSeason(event, player, targetName, current);
        }
        return null;
    }

    /** Someone is being sent to the waiting room: usually VeloAuth after /login, or a kick fallback. */
    private EventTask routeFromWaiting(ServerPreConnectEvent event, Player player, String current) {
        PluginConfig cfg = config.get();
        UUID id = player.getUniqueId();
        if (parked.containsKey(id) || queue.isQueued(id)) {
            return null; // kick fallback or already waiting for a season: the waiting room is right
        }
        if (cfg.isSeason(current)) {
            return null; // deliberate /server waiting from a season
        }
        String dest = destinationFor(id);
        if (dest == null) {
            return null;
        }
        Optional<RegisteredServer> destServer = proxy.getServer(dest);
        if (destServer.isEmpty()) {
            logger.error("Season {} is not registered in velocity.toml", dest);
            return null;
        }
        return EventTask.resumeWhenComplete(guard(seasons.isReadyNow(dest).thenAccept(ready -> {
            if (ready) {
                event.setResult(ServerResult.allowed(destServer.get()));
            } else {
                queue.enqueue(player, dest, cfg.waitingServer());
            }
        })));
    }

    /** A connect to a season: allow if up, otherwise hold the player and start it. */
    private EventTask connectToSeason(ServerPreConnectEvent event, Player player, String season, String current) {
        PluginConfig cfg = config.get();
        return EventTask.resumeWhenComplete(guard(seasons.isReadyNow(season).thenAccept(ready -> {
            if (ready) {
                return;
            }
            if (cfg.isSeason(current) || cfg.waitingServer().equals(current)) {
                // Already playing, or already in the waiting room: stay where they are while the target
                // starts. (Redirecting to the waiting room they're in makes Velocity say "already connected".)
                event.setResult(ServerResult.denied());
                queue.enqueue(player, season, current);
                return;
            }
            Optional<RegisteredServer> waiting = proxy.getServer(cfg.waitingServer());
            if (waiting.isEmpty()) {
                logger.error("Waiting room '{}' is not registered in velocity.toml", cfg.waitingServer());
                return;
            }
            // A redirect, never a deny: VeloAuth sees a successful move.
            event.setResult(ServerResult.allowed(waiting.get()));
            queue.enqueue(player, season, cfg.waitingServer());
        })));
    }

    private CompletableFuture<Void> guard(CompletableFuture<Void> f) {
        return f.exceptionally(t -> {
            logger.error("Routing decision failed; leaving the connect as it was", t);
            return null;
        });
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        PluginConfig cfg = config.get();
        Player player = event.getPlayer();
        String server = event.getServer().getServerInfo().getName();

        queue.onServerConnected(player, server);

        if (cfg.isSeason(server)) {
            parked.remove(player.getUniqueId());
            seasons.cancelIdle(server);
            lastServers.put(player.getUniqueId(), server);
        } else if (server.equals(cfg.waitingServer())) {
            String from = parked.get(player.getUniqueId());
            if (from != null) {
                explainKick(player, from);
            }
        } else {
            parked.remove(player.getUniqueId());
        }

        event.getPreviousServer()
                .map(s -> s.getServerInfo().getName())
                .filter(cfg::isSeason)
                .ifPresent(this::evaluateIdleSoon);
    }

    @Subscribe
    public void onKicked(KickedFromServerEvent event) {
        String server = event.getServer().getServerInfo().getName();
        if (config.get().isSeason(server) && !event.kickedDuringServerConnect()) {
            parked.put(event.getPlayer().getUniqueId(), server);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        queue.remove(player.getUniqueId());
        parked.remove(player.getUniqueId());
        player.getCurrentServer()
                .map(ServerConnection::getServerInfo)
                .map(i -> i.getName())
                .filter(config.get()::isSeason)
                .ifPresent(this::evaluateIdleSoon);
    }

    /** Player counts update just after these events, so look again a moment later. */
    private void evaluateIdleSoon(String season) {
        proxy.getScheduler().buildTask(plugin, () -> seasons.evaluateIdle(season))
                .delay(2, TimeUnit.SECONDS)
                .schedule();
    }

    private void explainKick(Player player, String season) {
        PluginConfig.SeasonConfig sc = config.get().season(season);
        String display = sc == null ? season : sc.displayName();
        seasons.ping(season).thenAccept(up -> messages.send(player, 
                up ? "kicked-to-waiting" : "season-down", "display", display, "season", season));
    }
}
