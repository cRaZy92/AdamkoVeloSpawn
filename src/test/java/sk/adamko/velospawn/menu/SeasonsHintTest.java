package sk.adamko.velospawn.menu;

import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import org.junit.jupiter.api.Test;
import sk.adamko.velospawn.config.PluginConfig;
import sk.adamko.velospawn.store.SeenSeasonsStore;
import sk.adamko.velospawn.testkit.TestNetwork;
import sk.adamko.velospawn.testkit.TestNetwork.FakePlayer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeasonsHintTest {

    private final TestNetwork net = new TestNetwork();
    private final SeenSeasonsStore store =
            new SeenSeasonsStore(net.dataDir.resolve("seasons-seen.json"), net.logger, Runnable::run);
    private final SeasonsHint hint = new SeasonsHint(net.plugin, net.proxy, net::config, net.messages, store);

    private FakePlayer join(FakePlayer p, String server) {
        p.active = true;
        p.server = server;
        hint.onPostConnect(new ServerPostConnectEvent(p.mock, null));
        return p;
    }

    private void leave(FakePlayer p) {
        hint.onDisconnect(new DisconnectEvent(p.mock, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));
        p.active = false;
        p.server = null;
    }

    private static List<String> hints(FakePlayer p) {
        return p.chat().stream().filter(l -> l.contains("/seasons")).toList();
    }

    @Test
    void firstVisitGetsTheTipOnceOnASeasonServer() {
        FakePlayer p = join(net.player("Alex"), "limbo");
        net.scheduler.advanceSeconds(10);
        assertEquals(List.of(), hints(p), "nothing while still in the auth limbo");

        join(p, "waiting");
        net.scheduler.advanceSeconds(10);
        assertEquals(List.of(), hints(p), "nothing in the waiting room");

        join(p, "s3");
        assertEquals(List.of(), hints(p), "sent after a short delay");
        net.scheduler.advanceSeconds(SeasonsHint.DELAY_SECONDS);
        assertEquals(1, hints(p).size());
        assertTrue(hints(p).get(0).startsWith("Tip:"), hints(p).get(0));

        join(p, "s2"); // a server switch in the same login
        net.scheduler.advanceSeconds(10);
        assertEquals(1, hints(p).size());

        leave(p);
        join(p, "s3"); // next login: already told
        net.scheduler.advanceSeconds(10);
        assertEquals(1, hints(p).size());
        assertEquals(Optional.of(Set.of("s1", "s2", "s3")), store.get(p.id));
    }

    @Test
    void newSeasonIsAnnouncedToPlayersWhoKnowTheOldOnes() {
        FakePlayer p = net.player("Alex");
        store.markSeen(p.id, List.of("s1", "s2"));

        join(p, "s3");
        net.scheduler.advanceSeconds(SeasonsHint.DELAY_SECONDS);

        assertEquals(List.of("New: Season 3 · 2026! Type /seasons to browse all seasons."), hints(p));
        assertEquals(Optional.of(Set.of("s1", "s2", "s3")), store.get(p.id));
    }

    @Test
    void sentBackToWaitingBeforeTheDelayWaitsForTheNextSeason() {
        FakePlayer p = join(net.player("Alex"), "s3");
        join(p, "waiting"); // e.g. s3 went down right away
        net.scheduler.advanceSeconds(SeasonsHint.DELAY_SECONDS);
        assertEquals(List.of(), hints(p));

        join(p, "s2");
        net.scheduler.advanceSeconds(SeasonsHint.DELAY_SECONDS);
        assertEquals(1, hints(p).size());
    }

    @Test
    void removedSeasonSaysNothing() {
        FakePlayer p = net.player("Alex");
        store.markSeen(p.id, List.of("s0", "s1", "s2", "s3"));
        join(p, "s3");
        net.scheduler.advanceSeconds(SeasonsHint.DELAY_SECONDS);
        assertEquals(List.of(), hints(p));
    }

    @Test
    void leavingBeforeTheDelayDefersTheTipToNextLogin() {
        FakePlayer p = join(net.player("Alex"), "s3");
        leave(p);
        net.scheduler.advanceSeconds(SeasonsHint.DELAY_SECONDS);
        assertEquals(List.of(), hints(p));
        assertEquals(Optional.empty(), store.get(p.id));
    }

    @Test
    void emptyTemplateStaysSilentButStillRemembers() {
        net.config(c -> {
            Map<String, String> m = new LinkedHashMap<>(c.messages());
            m.put("seasons-hint-first", "");
            return new PluginConfig(c.panelUrl(), c.apiKey(), c.authServer(), c.waitingServer(), c.newest(),
                    c.seasons(), c.rememberLastServer(), c.prestartOnConnect(), c.startupTimeoutSeconds(),
                    c.pollIntervalSeconds(), c.pingTimeoutMs(), c.chat(), Map.copyOf(m), c.mapMods());
        });
        FakePlayer p = join(net.player("Alex"), "s3");
        net.scheduler.advanceSeconds(SeasonsHint.DELAY_SECONDS);
        assertEquals(List.of(), hints(p));
        assertTrue(store.get(p.id).isPresent());
    }

    @Test
    void storeSurvivesRestart() {
        FakePlayer p = net.player("Alex");
        store.markSeen(p.id, List.of("s1", "s2"));
        SeenSeasonsStore reloaded =
                new SeenSeasonsStore(net.dataDir.resolve("seasons-seen.json"), net.logger, Runnable::run);
        reloaded.load();
        assertEquals(Optional.of(Set.of("s1", "s2")), reloaded.get(p.id));
    }
}
