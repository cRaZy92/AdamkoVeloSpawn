package sk.adamko.velospawn.season;

import org.junit.jupiter.api.Test;
import sk.adamko.velospawn.pelican.PelicanClient.State;
import sk.adamko.velospawn.season.SeasonManager.Outcome;
import sk.adamko.velospawn.testkit.TestNetwork;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeasonManagerTest {

    private final TestNetwork net = new TestNetwork();
    private final SeasonManager seasons = net.seasons;

    private boolean readyNow(String season) {
        return seasons.isReadyNow(season).join();
    }

    // ------------------------------------------------------------------ starting

    @Test
    void alreadyRunningIsReadyImmediatelyWithoutStartCall() {
        net.running("s3");
        CompletableFuture<Outcome> f = seasons.ensureReady("s3");
        assertEquals(Outcome.READY, f.getNow(null));
        assertTrue(net.panel.calls.isEmpty());
        assertTrue(readyNow("s3"));
    }

    @Test
    void offlineSeasonGetsOneStartAndIsReadyOnlyAfterJoinDelay() {
        net.offline("s3");
        CompletableFuture<Outcome> f = seasons.ensureReady("s3");
        assertEquals(1, net.startCalls("s3"));
        assertFalse(f.isDone());

        net.scheduler.advanceSeconds(10); // still booting: panel "starting", no ping
        assertFalse(f.isDone());
        assertFalse(readyNow("s3"));

        net.running("s3");
        net.scheduler.advanceSeconds(2); // poll sees it up → s3 join-delay-seconds = 5
        assertFalse(f.isDone(), "must wait the settle delay");
        assertFalse(readyNow("s3"), "nobody joins during the settle delay");

        net.scheduler.advanceSeconds(5);
        assertEquals(Outcome.READY, f.getNow(null));
        assertTrue(readyNow("s3"));
        assertEquals(1, net.startCalls("s3"));
    }

    @Test
    void concurrentCallersShareOneStart() {
        net.offline("s2");
        CompletableFuture<Outcome> a = seasons.ensureReady("s2");
        CompletableFuture<Outcome> b = seasons.ensureReady("s2");
        assertSame(a, b);
        net.running("s2");
        net.scheduler.advanceSeconds(2 + 15);
        assertEquals(Outcome.READY, a.getNow(null));
        assertEquals(1, net.startCalls("s2"));
    }

    @Test
    void stoppingSeasonIsStartedOnceItReachesOffline() {
        net.panelState("s3", State.STOPPING);
        net.ping("s3", true); // still answering pings while saving worlds
        CompletableFuture<Outcome> f = seasons.ensureReady("s3");
        assertEquals(0, net.startCalls("s3"), "no start while stopping");
        assertFalse(readyNow("s3"));

        net.scheduler.advanceSeconds(6);
        assertEquals(0, net.startCalls("s3"));

        net.offline("s3");
        net.scheduler.advanceSeconds(2);
        assertEquals(1, net.startCalls("s3"));

        net.running("s3");
        net.scheduler.advanceSeconds(2 + 5);
        assertEquals(Outcome.READY, f.getNow(null));
        assertEquals(1, net.startCalls("s3"));
    }

    @Test
    void startTimesOutWithoutRetryingTheStartCall() {
        net.offline("s3");
        CompletableFuture<Outcome> f = seasons.ensureReady("s3");
        net.scheduler.advanceSeconds(179);
        assertFalse(f.isDone());
        net.scheduler.advanceSeconds(5);
        assertEquals(Outcome.TIMEOUT, f.getNow(null));
        assertEquals(1, net.startCalls("s3"));
        assertFalse(seasons.isStarting("s3"));
    }

    @Test
    void panelUnreachableFailsFastWithNoPowerCalls() {
        net.offline("s3");
        net.panel.reachable = false;
        CompletableFuture<Outcome> f = seasons.ensureReady("s3");
        assertEquals(Outcome.PANEL_UNREACHABLE, f.getNow(null));
        assertTrue(net.panel.calls.isEmpty());
        net.scheduler.advanceMinutes(5);
        assertTrue(net.panel.calls.isEmpty(), "never loops start calls");
    }

    @Test
    void failedStartCallIsReportedAndNotRepeated() {
        net.offline("s3");
        net.panel.powerFails = true;
        CompletableFuture<Outcome> f = seasons.ensureReady("s3");
        assertEquals(Outcome.PANEL_UNREACHABLE, f.getNow(null));
        net.scheduler.advanceMinutes(5);
        assertEquals(1, net.startCalls("s3"));
    }

    @Test
    void crashDuringStartupFailsInsteadOfWaitingForTimeout() {
        net.offline("s3");
        CompletableFuture<Outcome> f = seasons.ensureReady("s3");
        net.scheduler.advanceSeconds(2); // sees "starting"
        net.offline("s3");               // crashed back to offline
        net.scheduler.advanceSeconds(2);
        assertEquals(Outcome.FAILED, f.getNow(null));
        assertEquals(1, net.startCalls("s3"));
    }

    @Test
    void newAttemptAfterFailureStartsAgain() {
        net.offline("s3");
        net.panel.reachable = false;
        assertEquals(Outcome.PANEL_UNREACHABLE, seasons.ensureReady("s3").getNow(null));
        net.panel.reachable = true;
        CompletableFuture<Outcome> f = seasons.ensureReady("s3");
        assertEquals(1, net.startCalls("s3"));
        net.running("s3");
        net.scheduler.advanceSeconds(2 + 5);
        assertEquals(Outcome.READY, f.getNow(null));
    }

    // ------------------------------------------------------------------ live readiness

    @Test
    void readinessNeedsPingAndRunningState() {
        net.panelState("s3", State.RUNNING);
        net.ping("s3", false);
        assertFalse(readyNow("s3"), "panel says running but server doesn't answer");

        net.panelState("s3", State.STARTING);
        net.ping("s3", true);
        assertFalse(readyNow("s3"));

        net.running("s3");
        assertTrue(readyNow("s3"));
    }

    @Test
    void pingFailureSkipsThePanelCall() {
        net.offline("s3");
        int before = net.panel.stateCalls;
        assertFalse(readyNow("s3"));
        assertEquals(before, net.panel.stateCalls);
    }

    @Test
    void panelDownButServerAnsweringIsTreatedAsReady() {
        net.running("s3");
        net.panel.reachable = false;
        assertTrue(readyNow("s3"));
    }

    @Test
    void unknownSeasonIsNeverReady() {
        assertFalse(readyNow("s9"));
        assertEquals(Outcome.FAILED, seasons.ensureReady("s9").getNow(null));
    }

    @Test
    void justStoppedSeasonIsNotReadyEvenIfPanelStillSaysRunning() {
        net.running("s3");
        seasons.stop("s3").join();
        assertEquals(1, net.stopCalls("s3"));
        net.panelState("s3", State.RUNNING); // panel lags behind the stop signal
        assertFalse(readyNow("s3"));

        CompletableFuture<Outcome> f = seasons.ensureReady("s3");
        assertFalse(f.isDone(), "must not join a server that is going down");
        net.panelState("s3", State.STOPPING);
        net.scheduler.advanceSeconds(2);
        net.offline("s3");
        net.scheduler.advanceSeconds(2);
        assertEquals(1, net.startCalls("s3"));
        net.running("s3");
        net.scheduler.advanceSeconds(2 + 5);
        assertEquals(Outcome.READY, f.getNow(null));
    }

    @Test
    void stopGraceExpiresIfThePanelNeverReactedToTheStop() {
        net.running("s3");
        seasons.stop("s3").join();
        net.panelState("s3", State.RUNNING);
        net.scheduler.advanceSeconds(61);
        assertTrue(readyNow("s3"));
    }

    @Test
    void stopCancelsAnInProgressStart() {
        net.offline("s3");
        CompletableFuture<Outcome> f = seasons.ensureReady("s3");
        seasons.stop("s3").join();
        assertEquals(Outcome.FAILED, f.getNow(null));
        assertEquals(1, net.stopCalls("s3"));
    }

    // ------------------------------------------------------------------ idle shutdown

    @Test
    void emptySeasonIsStoppedAfterIdleMinutes() {
        net.running("s3");
        seasons.evaluateIdle("s3");
        assertTrue(seasons.idleRemainingSeconds("s3").isPresent());
        net.scheduler.advanceMinutes(14);
        assertEquals(0, net.stopCalls("s3"));
        net.scheduler.advanceMinutes(1);
        assertEquals(1, net.stopCalls("s3"));
    }

    @Test
    void joinCancelsIdleShutdown() {
        net.running("s3");
        seasons.evaluateIdle("s3");
        net.scheduler.advanceMinutes(10);
        TestNetwork.FakePlayer p = net.player("Bob");
        p.server = "s3";
        seasons.evaluateIdle("s3");
        assertTrue(seasons.idleRemainingSeconds("s3").isEmpty());
        net.scheduler.advanceMinutes(30);
        assertEquals(0, net.stopCalls("s3"));
    }

    @Test
    void idleTimerRestartsFromZeroAfterPlayerLeaves() {
        net.running("s3");
        seasons.evaluateIdle("s3");
        net.scheduler.advanceMinutes(10);
        seasons.cancelIdle("s3");           // someone joined
        seasons.evaluateIdle("s3");         // and left again
        net.scheduler.advanceMinutes(14);
        assertEquals(0, net.stopCalls("s3"));
        net.scheduler.advanceMinutes(1);
        assertEquals(1, net.stopCalls("s3"));
    }

    @Test
    void s1UsesItsOwnTenMinuteIdleTimerAndPanelId() {
        net.running("s1");
        seasons.evaluateIdle("s1");
        net.scheduler.advanceMinutes(9);
        assertEquals(0, net.stopCalls("s1"));
        net.scheduler.advanceMinutes(1);
        assertEquals(1, net.stopCalls("s1"));
        assertEquals(java.util.List.of("stop:test0001"), net.panel.calls);
    }

    @Test
    void eachSeasonStartsIndependently() {
        net.offline("s1");
        net.offline("s2");
        net.offline("s3");
        seasons.ensureReady("s1");
        seasons.ensureReady("s2");
        assertEquals(1, net.startCalls("s1"));
        assertEquals(1, net.startCalls("s2"));
        assertEquals(0, net.startCalls("s3"));
    }

    @Test
    void queuedPlayersBlockIdleShutdown() {
        net.running("s3");
        seasons.setWaiterCheck(s -> true);
        seasons.evaluateIdle("s3");
        assertTrue(seasons.idleRemainingSeconds("s3").isEmpty());
    }

    @Test
    void idleFireDoesNothingIfSeasonAlreadyDown() {
        net.running("s3");
        seasons.evaluateIdle("s3");
        net.offline("s3");
        net.scheduler.advanceMinutes(16);
        assertEquals(0, net.stopCalls("s3"));
    }

    @Test
    void idleShutdownDisabledWithZeroMinutes() {
        net.config(c -> {
            var seasonsCfg = new java.util.LinkedHashMap<>(c.seasons());
            var s3 = c.season("s3");
            seasonsCfg.put("s3", new sk.adamko.velospawn.config.PluginConfig.SeasonConfig(
                    "s3", s3.panelId(), s3.displayName(), 0, s3.joinDelaySeconds()));
            return new sk.adamko.velospawn.config.PluginConfig(c.panelUrl(), c.apiKey(), c.authServer(),
                    c.waitingServer(), c.newest(), seasonsCfg, c.rememberLastServer(), c.prestartOnConnect(),
                    c.startupTimeoutSeconds(), c.pollIntervalSeconds(), c.pingTimeoutMs(), c.chat(), c.messages(), c.mapMods());
        });
        net.running("s3");
        seasons.evaluateIdle("s3");
        net.scheduler.advanceMinutes(60);
        assertEquals(0, net.stopCalls("s3"));
    }

    @Test
    void sweeperArmsIdleForRunningEmptySeasonsAfterProxyRestart() {
        net.running("s3");
        net.offline("s2");
        seasons.startSweeper();
        net.scheduler.advanceSeconds(5);
        assertTrue(seasons.idleRemainingSeconds("s3").isPresent());
        assertTrue(seasons.idleRemainingSeconds("s2").isEmpty());
        net.scheduler.advanceMinutes(15);
        assertEquals(1, net.stopCalls("s3"));
        assertEquals(0, net.stopCalls("s2"));
    }

    @Test
    void prestartedSeasonNobodyJoinedIdlesOut() {
        net.offline("s3");
        seasons.startSweeper();
        seasons.ensureReady("s3");
        net.running("s3");
        net.scheduler.advanceSeconds(2 + 5);
        net.scheduler.advanceMinutes(17); // ≤1 min sweep delay + 15 min idle
        assertEquals(1, net.stopCalls("s3"));
    }
}
