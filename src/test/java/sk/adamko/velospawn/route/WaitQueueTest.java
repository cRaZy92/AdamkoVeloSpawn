package sk.adamko.velospawn.route;

import org.junit.jupiter.api.Test;
import sk.adamko.velospawn.testkit.TestNetwork;
import sk.adamko.velospawn.testkit.TestNetwork.FakePlayer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaitQueueTest {

    private final TestNetwork net = new TestNetwork();
    private final WaitQueue queue = net.queue;

    private FakePlayer inWaiting(String name) {
        FakePlayer p = net.player(name);
        p.server = "waiting";
        return p;
    }

    @Test
    void readySeasonMovesQueuedPlayerWithOneChatLine() {
        net.offline("s3");
        FakePlayer p = inWaiting("Bob");
        queue.enqueue(p.mock, "s3", "waiting");
        assertEquals("Starting Season 3… 0s", p.lastActionBar());
        assertTrue(p.chat().isEmpty(), "no chat while waiting, only the action bar");

        net.running("s3");
        net.scheduler.advanceSeconds(2 + 5);
        assertEquals("s3", p.server);
        assertEquals(List.of("Season 3 is ready, moving you…"), p.chat());
        assertFalse(queue.isQueued(p.id));
    }

    @Test
    void actionBarCountsUpWhileStarting() {
        net.offline("s3");
        FakePlayer p = inWaiting("Bob");
        queue.startTicker();
        queue.enqueue(p.mock, "s3", "waiting");
        net.scheduler.advanceSeconds(12);
        assertEquals("Starting Season 3… 12s", p.lastActionBar());
    }

    @Test
    void severalPlayersOneStartAllMoved() {
        net.offline("s3");
        FakePlayer a = inWaiting("Ann");
        FakePlayer b = inWaiting("Bob");
        queue.enqueue(a.mock, "s3", "waiting");
        net.scheduler.advanceSeconds(3);
        queue.enqueue(b.mock, "s3", "waiting");
        assertEquals(1, net.startCalls("s3"));
        assertEquals(2, queue.count("s3"));
        net.running("s3");
        net.scheduler.advanceSeconds(2 + 5);
        assertEquals("s3", a.server);
        assertEquals("s3", b.server);
        assertEquals(1, net.startCalls("s3"));
    }

    @Test
    void failedMoveIsRetriedWithTwoSecondGaps() {
        net.offline("s3");
        FakePlayer p = inWaiting("Bob");
        queue.enqueue(p.mock, "s3", "waiting");
        net.running("s3");
        net.rejectNextConnects("s3", 2);
        net.scheduler.advanceSeconds(2 + 5);
        assertEquals("waiting", p.server);
        assertEquals(1, p.connectRequests);
        net.scheduler.advanceSeconds(2);
        assertEquals(2, p.connectRequests);
        net.scheduler.advanceSeconds(2);
        assertEquals(3, p.connectRequests);
        assertEquals("s3", p.server);
    }

    @Test
    void moveGivesUpAfterThreeAttempts() {
        net.offline("s3");
        FakePlayer p = inWaiting("Bob");
        queue.enqueue(p.mock, "s3", "waiting");
        net.running("s3");
        net.rejectNextConnects("s3", 100);
        net.scheduler.advanceSeconds(2 + 5 + 10);
        assertEquals(3, p.connectRequests);
        assertEquals("waiting", p.server);
        assertTrue(p.chat().get(p.chat().size() - 1).startsWith("Couldn't move you to Season 3"));
    }

    @Test
    void timeoutTellsPlayerAndLeavesThemInWaiting() {
        net.offline("s3");
        FakePlayer p = inWaiting("Bob");
        queue.enqueue(p.mock, "s3", "waiting");
        net.scheduler.advanceSeconds(185);
        assertEquals("waiting", p.server);
        assertEquals(List.of("Season 3 didn't start. Try again in a minute or ping Adam."), p.chat());
        assertFalse(queue.isQueued(p.id));
    }

    @Test
    void panelUnreachableMessage() {
        net.offline("s3");
        net.panel.reachable = false;
        FakePlayer p = inWaiting("Bob");
        queue.enqueue(p.mock, "s3", "waiting");
        assertEquals(1, p.chat().size());
        assertTrue(p.chat().get(0).startsWith("The panel is unreachable"));
        assertEquals("waiting", p.server);
    }

    @Test
    void droppedPlayerIsNotMovedButSeasonKeepsStarting() {
        net.offline("s3");
        FakePlayer p = inWaiting("Bob");
        queue.enqueue(p.mock, "s3", "waiting");
        queue.remove(p.id);
        assertTrue(net.seasons.isStarting("s3"), "server keeps starting");
        net.running("s3");
        net.scheduler.advanceSeconds(2 + 5);
        assertEquals("waiting", p.server);
        assertEquals(0, p.connectRequests);
    }

    @Test
    void leavingTheHoldServerDropsFromQueue() {
        net.offline("s3");
        FakePlayer p = inWaiting("Bob");
        queue.enqueue(p.mock, "s3", "waiting");
        queue.onServerConnected(p.mock, "waiting");
        assertTrue(queue.isQueued(p.id), "arriving at the hold server keeps them queued");
        queue.onServerConnected(p.mock, "s2");
        assertFalse(queue.isQueued(p.id));
    }

    @Test
    void requeueForAnotherSeasonReplacesTheFirst() {
        net.offline("s3");
        net.offline("s2");
        FakePlayer p = inWaiting("Bob");
        queue.enqueue(p.mock, "s3", "waiting");
        queue.enqueue(p.mock, "s2", "waiting");
        net.running("s3");
        net.running("s2");
        net.scheduler.advanceSeconds(2 + 15);
        assertEquals("s2", p.server);
        assertEquals(List.of("s2"), p.visits());
    }
}
