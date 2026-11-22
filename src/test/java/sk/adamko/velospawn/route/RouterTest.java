package sk.adamko.velospawn.route;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder.Status;
import org.junit.jupiter.api.Test;
import sk.adamko.velospawn.pelican.PelicanClient.State;
import sk.adamko.velospawn.testkit.TestNetwork;
import sk.adamko.velospawn.testkit.TestNetwork.FakePlayer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end routing flows; the plan's checklist items are named in the test names. */
class RouterTest {

    private final TestNetwork net = new TestNetwork();

    // ------------------------------------------------------------------ login

    @Test
    void checklist_joinWithS3Off_waitsInWaitingThenMovedNoErrors() {
        net.offline("s3");
        FakePlayer p = net.login("Bob");
        assertEquals(1, net.startCalls("s3"), "pre-started at PostLogin");
        assertEquals("waiting", p.server);
        assertTrue(net.queue.isQueued(p.id));

        net.running("s3");
        net.scheduler.advanceSeconds(2 + 5);
        assertEquals(List.of("limbo", "waiting", "s3"), p.visits());
        assertEquals(List.of("Season 3 is ready, moving you…"), p.chat());
        assertEquals(1, net.startCalls("s3"));
    }

    @Test
    void checklist_joinWithS3Running_straightThrough() {
        net.running("s3");
        FakePlayer p = net.login("Bob");
        assertEquals(List.of("limbo", "s3"), p.visits(), "never enters the waiting room");
        assertTrue(p.chat().isEmpty());
        assertTrue(p.actionBars.isEmpty());
        assertEquals(0, net.startCalls("s3"));
    }

    @Test
    void checklist_joinWhileS3Stopping_waitsRestartsMoves() {
        net.panelState("s3", State.STOPPING);
        net.ping("s3", true);
        FakePlayer p = net.login("Bob");
        assertEquals("waiting", p.server);
        assertEquals(0, net.startCalls("s3"));

        net.offline("s3");
        net.scheduler.advanceSeconds(2);
        assertEquals(1, net.startCalls("s3"));
        net.running("s3");
        net.scheduler.advanceSeconds(2 + 5);
        assertEquals("s3", p.server);
    }

    @Test
    void checklist_twoPlayersSameSeason_oneStartBothMoved() {
        net.offline("s3");
        FakePlayer a = net.login("Ann");
        FakePlayer b = net.login("Bob");
        assertEquals(1, net.startCalls("s3"));
        net.running("s3");
        net.scheduler.advanceSeconds(2 + 5);
        assertEquals("s3", a.server);
        assertEquals("s3", b.server);
    }

    @Test
    void checklist_leaveDuringWaiting_droppedServerStillStartsThenIdlesOut() {
        net.offline("s3");
        net.seasons.startSweeper();
        FakePlayer p = net.login("Bob");
        net.disconnect(p);
        assertFalse(net.queue.isQueued(p.id));
        net.running("s3");
        net.scheduler.advanceSeconds(2 + 5);
        assertEquals(0, net.stopCalls("s3"));
        net.scheduler.advanceMinutes(17);
        assertEquals(1, net.stopCalls("s3"));
    }

    @Test
    void checklist_lastServer_rejoinGoesBackToS2() {
        net.running("s3");
        net.running("s2");
        FakePlayer p = net.login("Bob");
        assertEquals("s3", p.server, "first time: newest");
        net.connect(p, "s2");
        net.disconnect(p);

        FakePlayer again = net.player("Bob2");
        // Same account: reuse the UUID by asking the router directly.
        assertEquals("s2", net.router.destinationFor(p.id));
        assertEquals("s3", net.router.destinationFor(again.id));
    }

    @Test
    void lastSeasonNoLongerConfiguredFallsBackToNewest() {
        net.lastServers.put(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"), "s1");
        assertEquals("s3", net.router.destinationFor(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")));
    }

    @Test
    void rememberLastServerOffAlwaysUsesNewest() {
        net.config(c -> new sk.adamko.velospawn.config.PluginConfig(c.panelUrl(), c.apiKey(), c.authServer(),
                c.waitingServer(), c.newest(), c.seasons(), false, c.prestartOnConnect(), c.startupTimeoutSeconds(),
                c.pollIntervalSeconds(), c.pingTimeoutMs(), c.chat(), c.messages()));
        java.util.UUID id = java.util.UUID.randomUUID();
        net.lastServers.put(id, "s2");
        assertEquals("s3", net.router.destinationFor(id));
    }

    @Test
    void loginRoutesToRememberedSeasonAndStartsIt() {
        net.offline("s2");
        net.running("s3");
        FakePlayer p = net.player("Bob");
        net.lastServers.put(p.id, "s2");
        p.server = "limbo";
        net.router.onPostLogin(new com.velocitypowered.api.event.connection.PostLoginEvent(p.mock));
        net.connect(p, "waiting");
        assertEquals(1, net.startCalls("s2"));
        assertEquals("waiting", p.server);
        net.running("s2");
        net.scheduler.advanceSeconds(2 + 15);
        assertEquals("s2", p.server);
    }

    @Test
    void prestartOffStartsOnlyWhenVeloAuthMovesThePlayer() {
        net.prestart(false);
        net.offline("s3");
        FakePlayer p = net.player("Bob");
        p.server = "limbo";
        net.router.onPostLogin(new com.velocitypowered.api.event.connection.PostLoginEvent(p.mock));
        assertEquals(0, net.startCalls("s3"));
        net.connect(p, "waiting");
        assertEquals(1, net.startCalls("s3"));
    }

    @Test
    void unauthenticatedConnectDeniedByVeloAuthIsLeftAlone() {
        net.offline("s3");
        FakePlayer p = net.player("Bob");
        p.server = "limbo";
        p.authDenied = true;
        assertEquals(Status.CONNECTION_CANCELLED, net.connect(p, "waiting").getStatus());
        assertEquals(0, net.startCalls("s3"));
        assertFalse(net.queue.isQueued(p.id));
        assertEquals("limbo", p.server);
    }

    // ------------------------------------------------------------------ /server between seasons

    @Test
    void checklist_serverS2FromS3WhileOff_staysOnS3ThenMoved() {
        net.running("s3");
        net.offline("s2");
        FakePlayer p = net.login("Bob");
        assertEquals("s3", p.server);

        assertEquals(Status.CONNECTION_CANCELLED, net.connect(p, "s2").getStatus());
        assertEquals("s3", p.server, "stays on s3 while s2 starts");
        assertEquals("Starting Season 2… 0s", p.lastActionBar());
        assertEquals(1, net.startCalls("s2"));

        net.running("s2");
        net.scheduler.advanceSeconds(2 + 15);
        assertEquals(List.of("limbo", "s3", "s2"), p.visits(), "no trip through waiting");
    }

    @Test
    void connectToRunningSeasonIsAllowedUntouched() {
        net.running("s3");
        net.running("s2");
        FakePlayer p = net.login("Bob");
        assertEquals(Status.SUCCESS, net.connect(p, "s2").getStatus());
        assertEquals("s2", p.server);
        assertEquals(0, net.startCalls("s2"));
    }

    @Test
    void connectToOffSeasonFromLimboIsRedirectedToWaiting() {
        net.offline("s2");
        FakePlayer p = net.player("Bob");
        p.server = "limbo";
        net.connect(p, "s2"); // e.g. a forced host straight to s2
        assertEquals("waiting", p.server);
        assertTrue(net.queue.isQueued(p.id));
    }

    @Test
    void manualServerWaitingFromSeasonIsNotRerouted() {
        net.running("s3");
        FakePlayer p = net.login("Bob");
        net.connect(p, "waiting");
        assertEquals("waiting", p.server);
        assertFalse(net.queue.isQueued(p.id));
    }

    // ------------------------------------------------------------------ crashes and kicks

    @Test
    void seasonCrashLandsPlayerInWaitingWithMessageAndNoRestart() {
        net.running("s3");
        FakePlayer p = net.login("Bob");
        net.offline("s3"); // crash
        net.kick(p);
        assertEquals("waiting", p.server, "not bounced back");
        assertEquals(0, net.startCalls("s3"), "nothing restarted automatically");
        assertFalse(net.queue.isQueued(p.id));
        assertTrue(p.chat().get(p.chat().size() - 1).startsWith("Season 3 went down."));
    }

    @Test
    void kickFromRunningSeasonDoesNotLoopBack() {
        net.running("s3");
        FakePlayer p = net.login("Bob");
        net.kick(p); // e.g. AFK kick or ban; s3 still up
        assertEquals("waiting", p.server);
        assertTrue(p.chat().get(p.chat().size() - 1).startsWith("You were sent to the waiting room from Season 3."));
    }

    @Test
    void playAfterCrashRestartsAndMoves() {
        net.running("s3");
        FakePlayer p = net.login("Bob");
        net.offline("s3");
        net.kick(p);
        // /play s3 from the waiting room: queue, start, move.
        assertFalse(net.seasons.isReadyNow("s3").join());
        net.queue.enqueue(p.mock, "s3", "waiting");
        net.running("s3");
        net.scheduler.advanceSeconds(2 + 5);
        assertEquals("s3", p.server);
    }

    // ------------------------------------------------------------------ idle bookkeeping

    @Test
    void checklist_idleShutdownAfterLastPlayerLeaves_cancelledByJoin() {
        net.running("s3");
        FakePlayer p = net.login("Bob");
        net.disconnect(p);
        net.scheduler.advanceSeconds(2); // player counts settle
        assertTrue(net.seasons.idleRemainingSeconds("s3").isPresent());

        net.scheduler.advanceMinutes(10);
        FakePlayer q = net.login("Ann");
        assertEquals("s3", q.server);
        assertTrue(net.seasons.idleRemainingSeconds("s3").isEmpty(), "join cancels the timer");
        net.scheduler.advanceMinutes(30);
        assertEquals(0, net.stopCalls("s3"));

        net.disconnect(q);
        net.scheduler.advanceSeconds(2);
        net.scheduler.advanceMinutes(15);
        assertEquals(1, net.stopCalls("s3"));
    }

    @Test
    void switchingSeasonsArmsIdleOnTheOneLeft() {
        net.running("s3");
        net.running("s2");
        FakePlayer p = net.login("Bob");
        net.connect(p, "s2");
        net.scheduler.advanceSeconds(2);
        assertTrue(net.seasons.idleRemainingSeconds("s3").isPresent());
        assertTrue(net.seasons.idleRemainingSeconds("s2").isEmpty());
    }

    @Test
    void checklist_proxyRestart_lastServerDataPersists() {
        net.running("s2");
        net.running("s3");
        FakePlayer p = net.login("Bob");
        net.connect(p, "s2");
        var reloaded = new sk.adamko.velospawn.store.LastServerStore(
                net.dataDir.resolve("last-servers.json"), net.logger, Runnable::run);
        reloaded.load();
        assertEquals(java.util.Optional.of("s2"), reloaded.get(p.id));
    }

    @Test
    void checklist_panelUnreachable_clearMessageNoStartLoop() {
        net.offline("s3");
        net.panel.reachable = false;
        FakePlayer p = net.login("Bob");
        assertEquals("waiting", p.server);
        assertTrue(p.chat().get(0).startsWith("The panel is unreachable"));
        net.scheduler.advanceMinutes(10);
        assertTrue(net.panel.calls.isEmpty());
        assertEquals(1, p.chat().size());
    }
}
