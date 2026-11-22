package sk.adamko.velospawn.season;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.slf4j.Logger;
import sk.adamko.velospawn.config.PluginConfig;
import sk.adamko.velospawn.pelican.PelicanClient;
import sk.adamko.velospawn.pelican.PelicanClient.State;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Owns the lifecycle of every season: live readiness checks, one start per season at a time,
 * and idle shutdown. Never decides from cached state: every decision pings and asks the panel.
 */
public final class SeasonManager {

    public enum Outcome { READY, TIMEOUT, PANEL_UNREACHABLE, FAILED }

    /** Result of a live check. {@code panelError} is non-null when the panel couldn't be asked. */
    public record LiveStatus(boolean pingOk, State panelState, String panelError) {
        public boolean ready() {
            // A server that answers pings is joinable; if the panel is down we trust the ping.
            return pingOk && (panelState == State.RUNNING || panelError != null);
        }
    }

    private static final class Runtime {
        volatile StartAttempt start;
        volatile ScheduledTask idleTask;
        volatile long idleDeadlineMillis;
        /** Set when we send a stop; until the panel shows stopping/offline the server is not "ready". */
        volatile long stopSentMillis;
    }

    private static final long STOP_GRACE_MILLIS = 60_000;

    private static final class StartAttempt {
        final String season;
        final CompletableFuture<Outcome> future = new CompletableFuture<>();
        final long startedAtMillis = System.currentTimeMillis();
        final long deadlineMillis;
        volatile boolean startSent;
        volatile boolean sawBooting;
        volatile State lastState = State.UNKNOWN;
        volatile ScheduledTask pending;

        StartAttempt(String season, long deadlineMillis) {
            this.season = season;
            this.deadlineMillis = deadlineMillis;
        }
    }

    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final Supplier<PluginConfig> config;
    private final Supplier<PelicanClient> pelican;
    private final Map<String, Runtime> runtimes = new ConcurrentHashMap<>();
    private volatile Predicate<String> hasWaiters = s -> false;
    private volatile ScheduledTask sweepTask;

    public SeasonManager(Object plugin, ProxyServer proxy, Logger logger,
                         Supplier<PluginConfig> config, Supplier<PelicanClient> pelican) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.config = config;
        this.pelican = pelican;
    }

    public void setWaiterCheck(Predicate<String> hasWaiters) {
        this.hasWaiters = hasWaiters;
    }

    private Runtime rt(String season) {
        return runtimes.computeIfAbsent(season, k -> new Runtime());
    }

    // ------------------------------------------------------------------ live checks

    public CompletableFuture<Boolean> ping(String season) {
        Optional<RegisteredServer> server = proxy.getServer(season);
        if (server.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        int timeout = config.get().pingTimeoutMs();
        try {
            return server.get().ping()
                    .orTimeout(timeout, TimeUnit.MILLISECONDS)
                    .handle((ping, t) -> t == null && ping != null);
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(false);
        }
    }

    public CompletableFuture<LiveStatus> liveStatus(String season) {
        PluginConfig.SeasonConfig sc = config.get().season(season);
        if (sc == null) {
            return CompletableFuture.completedFuture(new LiveStatus(false, State.UNKNOWN, "not a configured season"));
        }
        CompletableFuture<Boolean> ping = ping(season);
        CompletableFuture<Object> panel = pelican.get().state(sc.panelId())
                .<Object>thenApply(s -> s)
                .exceptionally(t -> PelicanClient.unwrap(t).getMessage());
        return ping.thenCombine(panel, (ok, p) -> p instanceof State s
                ? new LiveStatus(ok, s, null)
                : new LiveStatus(ok, State.UNKNOWN, String.valueOf(p)));
    }

    /**
     * Fast decision for a connect: ping first (short timeout), then panel state.
     * False while a start is in progress, so players never join during the settle delay.
     */
    public CompletableFuture<Boolean> isReadyNow(String season) {
        if (!config.get().isSeason(season)) {
            return CompletableFuture.completedFuture(false);
        }
        if (rt(season).start != null) {
            return CompletableFuture.completedFuture(false);
        }
        return ping(season).thenCompose(ok -> {
            if (!ok) {
                return CompletableFuture.completedFuture(false);
            }
            String panelId = config.get().season(season).panelId();
            return pelican.get().state(panelId).handle((state, t) -> {
                if (t != null) {
                    logger.warn("Panel unreachable while checking {} (server answers ping, allowing): {}",
                            season, PelicanClient.unwrap(t).getMessage());
                    return readyConsideringStop(season,
                            new LiveStatus(true, State.UNKNOWN, PelicanClient.unwrap(t).getMessage()));
                }
                return readyConsideringStop(season, new LiveStatus(true, state, null));
            });
        });
    }

    /**
     * Readiness that also respects a stop we just sent: right after a stop signal the panel can
     * still say "running" and the server still answers pings, but it is about to go down.
     */
    private boolean readyConsideringStop(String season, LiveStatus st) {
        Runtime rt = rt(season);
        long sent = rt.stopSentMillis;
        if (sent != 0) {
            if (st.panelState() == State.STOPPING || st.panelState() == State.OFFLINE
                    || System.currentTimeMillis() - sent > STOP_GRACE_MILLIS) {
                rt.stopSentMillis = 0; // the stop has been observed (or it never happened)
            }
            if (st.panelState() != State.OFFLINE && rt.stopSentMillis != 0) {
                return false;
            }
            if (st.panelState() == State.STOPPING) {
                return false;
            }
        }
        return st.ready();
    }

    private void markStopSent(String season) {
        rt(season).stopSentMillis = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ starting

    public boolean isStarting(String season) {
        return rt(season).start != null;
    }

    public OptionalLong startElapsedSeconds(String season) {
        StartAttempt a = rt(season).start;
        return a == null ? OptionalLong.empty()
                : OptionalLong.of((System.currentTimeMillis() - a.startedAtMillis) / 1000);
    }

    /**
     * Makes sure the season is up. Concurrent callers share one attempt (one start call).
     * Completes READY immediately if the server is already running and answering.
     */
    public CompletableFuture<Outcome> ensureReady(String season) {
        PluginConfig cfg = config.get();
        if (!cfg.isSeason(season)) {
            return CompletableFuture.completedFuture(Outcome.FAILED);
        }
        Runtime rt = rt(season);
        StartAttempt attempt;
        synchronized (rt) {
            if (rt.start != null) {
                return rt.start.future;
            }
            attempt = new StartAttempt(season, System.currentTimeMillis() + cfg.startupTimeoutSeconds() * 1000L);
            rt.start = attempt;
        }
        cancelIdle(season);
        liveStatus(season).whenComplete((st, t) -> {
            if (t != null) {
                logger.error("Live check of {} failed", season, t);
                finish(attempt, Outcome.FAILED);
                return;
            }
            attempt.lastState = st.panelState();
            if (readyConsideringStop(season, st)) {
                finish(attempt, Outcome.READY); // already up: no settle delay needed
            } else if (st.panelError() != null) {
                logger.error("Cannot start {}: panel unreachable: {}", season, st.panelError());
                finish(attempt, Outcome.PANEL_UNREACHABLE);
            } else if (st.panelState() == State.OFFLINE) {
                sendStart(attempt);
            } else {
                // starting / stopping / running-but-not-answering: just watch it.
                logger.info("{} is {} on the panel; waiting for it to come up", season, st.panelState());
                schedulePoll(attempt);
            }
        });
        return attempt.future;
    }

    private void sendStart(StartAttempt a) {
        a.startSent = true;
        String panelId = config.get().season(a.season).panelId();
        logger.info("Starting {} via Pelican (panel id {})", a.season, panelId);
        pelican.get().power(panelId, "start").whenComplete((v, t) -> {
            if (t != null) {
                logger.error("Start call for {} failed: {}", a.season, PelicanClient.unwrap(t).getMessage());
                finish(a, Outcome.PANEL_UNREACHABLE);
            } else {
                schedulePoll(a);
            }
        });
    }

    private void schedulePoll(StartAttempt a) {
        if (a.future.isDone()) {
            return;
        }
        a.pending = proxy.getScheduler().buildTask(plugin, () -> poll(a))
                .delay(config.get().pollIntervalSeconds(), TimeUnit.SECONDS)
                .schedule();
    }

    private void poll(StartAttempt a) {
        if (a.future.isDone()) {
            return;
        }
        if (System.currentTimeMillis() > a.deadlineMillis) {
            logger.warn("Start of {} timed out after {}s; last panel state: {}",
                    a.season, config.get().startupTimeoutSeconds(), a.lastState);
            finish(a, Outcome.TIMEOUT);
            return;
        }
        liveStatus(a.season).whenComplete((st, t) -> {
            if (a.future.isDone()) {
                return;
            }
            if (t != null) {
                schedulePoll(a);
                return;
            }
            if (st.panelError() != null) {
                logger.warn("Panel check for {} failed while starting: {}", a.season, st.panelError());
            } else {
                a.lastState = st.panelState();
            }
            if (readyConsideringStop(a.season, st)) {
                PluginConfig.SeasonConfig sc = config.get().season(a.season);
                int delay = sc == null ? 0 : Math.max(0, sc.joinDelaySeconds());
                logger.info("{} answers pings; moving queued players in {}s", a.season, delay);
                a.pending = proxy.getScheduler().buildTask(plugin, () -> finish(a, Outcome.READY))
                        .delay(delay, TimeUnit.SECONDS)
                        .schedule();
                return;
            }
            State s = st.panelState();
            if (s == State.STARTING || s == State.RUNNING) {
                a.sawBooting = true;
            }
            if (s == State.OFFLINE) {
                if (!a.startSent) {
                    sendStart(a); // was stopping, now fully off: start it once
                    return;
                }
                if (a.sawBooting) {
                    logger.error("{} went back to offline while starting (crashed during startup?)", a.season);
                    finish(a, Outcome.FAILED);
                    return;
                }
            }
            schedulePoll(a);
        });
    }

    private void finish(StartAttempt a, Outcome outcome) {
        Runtime rt = rt(a.season);
        synchronized (rt) {
            if (rt.start == a) {
                rt.start = null; // cleared before completing so movers see the season as idle
            }
        }
        ScheduledTask p = a.pending;
        if (p != null) {
            p.cancel();
        }
        if (a.future.complete(outcome)) {
            long took = (System.currentTimeMillis() - a.startedAtMillis) / 1000;
            logger.info("{} start finished: {} ({}s)", a.season, outcome, took);
        }
    }

    // ------------------------------------------------------------------ stopping / idle

    public CompletableFuture<Void> stop(String season) {
        PluginConfig.SeasonConfig sc = config.get().season(season);
        if (sc == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("unknown season " + season));
        }
        cancelIdle(season);
        StartAttempt a = rt(season).start;
        if (a != null) {
            finish(a, Outcome.FAILED);
        }
        logger.info("Stopping {} via Pelican", season);
        markStopSent(season);
        return pelican.get().power(sc.panelId(), "stop");
    }

    public OptionalLong idleRemainingSeconds(String season) {
        Runtime rt = rt(season);
        if (rt.idleTask == null) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(Math.max(0, (rt.idleDeadlineMillis - System.currentTimeMillis()) / 1000));
    }

    public int playerCount(String season) {
        return proxy.getServer(season).map(s -> s.getPlayersConnected().size()).orElse(0);
    }

    public void cancelIdle(String season) {
        Runtime rt = rt(season);
        ScheduledTask t;
        synchronized (rt) {
            t = rt.idleTask;
            rt.idleTask = null;
        }
        if (t != null) {
            t.cancel();
            logger.debug("Idle shutdown of {} cancelled", season);
        }
    }

    private boolean idleEligible(String season) {
        PluginConfig.SeasonConfig sc = config.get().season(season);
        return sc != null && sc.idleShutdownMinutes() > 0
                && playerCount(season) == 0
                && rt(season).start == null
                && !hasWaiters.test(season);
    }

    /** Arms the idle timer if the season is empty, cancels it otherwise. */
    public void evaluateIdle(String season) {
        if (!config.get().isSeason(season)) {
            return;
        }
        if (!idleEligible(season)) {
            cancelIdle(season);
            return;
        }
        armIdle(season);
    }

    private void armIdle(String season) {
        int minutes = config.get().season(season).idleShutdownMinutes();
        Runtime rt = rt(season);
        synchronized (rt) {
            if (rt.idleTask != null) {
                return;
            }
            rt.idleDeadlineMillis = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes);
            rt.idleTask = proxy.getScheduler().buildTask(plugin, () -> fireIdle(season))
                    .delay(minutes, TimeUnit.MINUTES)
                    .schedule();
        }
        logger.info("{} is empty; idle shutdown in {} min", season, minutes);
    }

    private void fireIdle(String season) {
        Runtime rt = rt(season);
        synchronized (rt) {
            rt.idleTask = null;
        }
        if (!idleEligible(season)) {
            return;
        }
        liveStatus(season).thenAccept(st -> {
            if (!idleEligible(season)) {
                return;
            }
            boolean up = st.pingOk() || st.panelState() == State.RUNNING || st.panelState() == State.STARTING;
            if (!up) {
                return;
            }
            logger.info("Idle shutdown: {} has been empty for {} min, stopping",
                    season, config.get().season(season).idleShutdownMinutes());
            PluginConfig.SeasonConfig sc = config.get().season(season);
            markStopSent(season);
            pelican.get().power(sc.panelId(), "stop").whenComplete((v, t) -> {
                if (t != null) {
                    logger.error("Idle stop of {} failed: {}", season, PelicanClient.unwrap(t).getMessage());
                }
            });
        });
    }

    /**
     * Periodic safety net: arms idle shutdown for any running season that is empty
     * (covers proxy restarts and servers started by hand on the panel).
     */
    public void startSweeper() {
        stopSweeper();
        sweepTask = proxy.getScheduler().buildTask(plugin, this::sweep)
                .delay(5, TimeUnit.SECONDS)
                .repeat(1, TimeUnit.MINUTES)
                .schedule();
    }

    private void sweep() {
        for (String season : config.get().seasonNames()) {
            if (rt(season).idleTask != null || !idleEligible(season)) {
                continue;
            }
            ping(season).thenAccept(up -> {
                if (up) {
                    evaluateIdle(season);
                }
            });
        }
    }

    public void stopSweeper() {
        ScheduledTask t = sweepTask;
        if (t != null) {
            t.cancel();
        }
    }

    /** Drops runtime state for seasons removed by a reload. */
    public void retainConfigured() {
        runtimes.keySet().removeIf(name -> {
            if (config.get().isSeason(name)) {
                return false;
            }
            cancelIdle(name);
            return true;
        });
    }

    public void shutdown() {
        stopSweeper();
        for (String season : runtimes.keySet()) {
            cancelIdle(season);
            StartAttempt a = rt(season).start;
            if (a != null) {
                finish(a, Outcome.FAILED);
            }
        }
    }
}
