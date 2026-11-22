package sk.adamko.velospawn.route;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import sk.adamko.velospawn.Messages;
import sk.adamko.velospawn.config.PluginConfig;
import sk.adamko.velospawn.season.SeasonManager;
import sk.adamko.velospawn.season.SeasonManager.Outcome;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Players waiting for a season to come up. Each player waits for at most one season;
 * they are held either in the waiting room or on the season they are already playing on.
 */
public final class WaitQueue {

    private static final int MOVE_ATTEMPTS = 3;
    private static final long MOVE_RETRY_SECONDS = 2;

    /** {@code hold} is the server the player stays on while waiting. */
    public record Waiter(UUID player, String season, String hold, long sinceMillis) {
    }

    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final Supplier<PluginConfig> config;
    private final SeasonManager seasons;
    private final Messages messages;
    private final Map<UUID, Waiter> waiters = new ConcurrentHashMap<>();
    private volatile ScheduledTask ticker;

    public WaitQueue(Object plugin, ProxyServer proxy, Logger logger, Supplier<PluginConfig> config,
                     SeasonManager seasons, Messages messages) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.config = config;
        this.seasons = seasons;
        this.messages = messages;
    }

    public void startTicker() {
        stopTicker();
        ticker = proxy.getScheduler().buildTask(plugin, this::tick)
                .repeat(1, TimeUnit.SECONDS)
                .schedule();
    }

    public void stopTicker() {
        ScheduledTask t = ticker;
        if (t != null) {
            t.cancel();
        }
    }

    public void enqueue(Player player, String season, String hold) {
        Waiter w = new Waiter(player.getUniqueId(), season, hold, System.currentTimeMillis());
        Waiter previous = waiters.put(w.player(), w);
        if (previous == null || !previous.season().equals(season)) {
            logger.info("{} is waiting for {} (held on {})", player.getUsername(), season, hold);
        }
        seasons.cancelIdle(season);
        sendActionBar(player, w);
        seasons.ensureReady(season).whenComplete((outcome, t) -> {
            if (t != null) {
                logger.error("Starting {} failed unexpectedly", season, t);
            }
            onOutcome(w, t == null ? outcome : Outcome.FAILED);
        });
    }

    public boolean isQueued(UUID player) {
        return waiters.containsKey(player);
    }

    public Optional<Waiter> get(UUID player) {
        return Optional.ofNullable(waiters.get(player));
    }

    public boolean hasWaiters(String season) {
        return waiters.values().stream().anyMatch(w -> w.season().equals(season));
    }

    public long count(String season) {
        return waiters.values().stream().filter(w -> w.season().equals(season)).count();
    }

    /** Drop a player (disconnect, or they went somewhere else). The season keeps starting. */
    public void remove(UUID player) {
        Waiter w = waiters.remove(player);
        if (w != null) {
            proxy.getPlayer(player).ifPresent(p -> p.sendActionBar(Component.empty()));
            logger.info("{} left the queue for {}", proxy.getPlayer(player).map(Player::getUsername)
                    .orElse(player.toString()), w.season());
        }
    }

    /** Called when a player lands on a server: leaving the hold server drops them from the queue. */
    public void onServerConnected(Player player, String server) {
        Waiter w = waiters.get(player.getUniqueId());
        if (w != null && !server.equals(w.hold())) {
            remove(player.getUniqueId());
        }
    }

    private void onOutcome(Waiter w, Outcome outcome) {
        if (!waiters.remove(w.player(), w)) {
            return; // left the queue or re-queued for something else
        }
        Optional<Player> maybe = proxy.getPlayer(w.player());
        if (maybe.isEmpty()) {
            return;
        }
        Player player = maybe.get();
        player.sendActionBar(Component.empty());
        String display = display(w.season());
        switch (outcome) {
            case READY -> {
                messages.send(player, "ready", "display", display, "season", w.season());
                move(player, w.season(), 1);
            }
            case PANEL_UNREACHABLE ->
                    messages.send(player, "panel-unreachable", "display", display, "season", w.season());
            case TIMEOUT, FAILED ->
                    messages.send(player, "start-failed", "display", display, "season", w.season());
        }
    }

    private void move(Player player, String season, int attempt) {
        if (!player.isActive()) {
            return;
        }
        Optional<RegisteredServer> server = proxy.getServer(season);
        if (server.isEmpty()) {
            logger.error("Season {} is not registered in velocity.toml", season);
            return;
        }
        player.createConnectionRequest(server.get()).connect().whenComplete((result, t) -> {
            if (t == null && isOnSeason(player, season)) {
                return;
            }
            if (t == null && result.getStatus() == ConnectionRequestBuilder.Status.ALREADY_CONNECTED) {
                return;
            }
            String why = t != null ? t.toString() : result.getStatus()
                    + result.getReasonComponent().map(c -> " " + net.kyori.adventure.text.serializer.plain
                    .PlainTextComponentSerializer.plainText().serialize(c)).orElse("");
            if (attempt < MOVE_ATTEMPTS && player.isActive()) {
                logger.warn("Moving {} to {} failed (attempt {}/{}): {}; retrying",
                        player.getUsername(), season, attempt, MOVE_ATTEMPTS, why);
                proxy.getScheduler().buildTask(plugin, () -> move(player, season, attempt + 1))
                        .delay(MOVE_RETRY_SECONDS, TimeUnit.SECONDS)
                        .schedule();
            } else if (player.isActive()) {
                logger.error("Giving up moving {} to {}: {}", player.getUsername(), season, why);
                messages.send(player, "move-failed", "display", display(season), "season", season);
            }
        });
    }

    private static boolean isOnSeason(Player player, String season) {
        return player.getCurrentServer()
                .map(c -> c.getServerInfo().getName().equals(season))
                .orElse(false);
    }

    private void tick() {
        for (Waiter w : waiters.values()) {
            proxy.getPlayer(w.player()).ifPresentOrElse(
                    p -> sendActionBar(p, w),
                    () -> waiters.remove(w.player(), w));
        }
    }

    private void sendActionBar(Player p, Waiter w) {
        long elapsed = seasons.startElapsedSeconds(w.season())
                .orElse((System.currentTimeMillis() - w.sinceMillis()) / 1000);
        p.sendActionBar(messages.get("starting-actionbar",
                "display", display(w.season()), "season", w.season(), "elapsed", elapsed));
    }

    private String display(String season) {
        PluginConfig.SeasonConfig sc = config.get().season(season);
        return sc == null ? season : sc.displayName();
    }
}
