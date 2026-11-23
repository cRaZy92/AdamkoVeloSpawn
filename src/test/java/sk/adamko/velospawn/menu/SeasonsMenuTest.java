package sk.adamko.velospawn.menu;

import com.google.gson.JsonObject;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.junit.jupiter.api.Test;
import sk.adamko.velospawn.menu.SeasonsMenu.Entry;
import sk.adamko.velospawn.store.SeasonStatsStore.SeasonStats;
import sk.adamko.velospawn.testkit.TestNetwork;
import sk.adamko.velospawn.testkit.TestNetwork.FakePlayer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeasonsMenuTest {

    private final TestNetwork net = new TestNetwork();
    private final SeasonsMenu menu = new SeasonsMenu(net::config, net.seasons, net.stats, net.messages);

    private static List<String> lore(Entry e) {
        return e.lore().stream().map(TestNetwork::plain).toList();
    }

    private static Entry entry(List<Entry> entries, String season) {
        return entries.stream().filter(e -> e.season().equals(season)).findFirst().orElseThrow();
    }

    private List<Entry> entries(String current) throws Exception {
        return menu.entries(current).get(3, TimeUnit.SECONDS);
    }

    private void s2Stats() {
        net.stats.put("s2", new SeasonStats(41, 166_320_000L, 512, "Adamko", 36_000_000L, 28_896_000L, 1000));
    }

    @Test
    void entryWithStatsShowsAllNumbers() throws Exception {
        s2Stats();
        net.offline("s2");
        assertEquals(List.of("○ Offline", "", "41 players", "1,204 in-game days", "2,310 hours played together",
                "Most played: Adamko (500 h)", "512 deaths", "Click to visit"), lore(entry(entries("waiting"), "s2")));
    }

    @Test
    void seasonWithoutStatsSaysNoStatsYet() throws Exception {
        net.offline("s1");
        assertEquals(List.of("○ Offline", "", "No stats yet", "Click to visit"), lore(entry(entries("waiting"), "s1")));
    }

    @Test
    void onlineSeasonShowsPlayerCountAndCurrentSaysYouAreHere() throws Exception {
        net.running("s3");
        FakePlayer bob = net.login("Bob");
        assertEquals("s3", bob.server);
        Entry s3 = entry(entries("s3"), "s3");
        List<String> lore = lore(s3);
        assertEquals("● Online · 1 playing", lore.get(0));
        assertEquals("You are here", lore.get(lore.size() - 1));
        assertTrue(s3.current());
        assertFalse(entry(entries("s3"), "s2").current());
    }

    @Test
    void startingSeasonSaysStarting() throws Exception {
        net.offline("s2");
        net.seasons.ensureReady("s2");
        assertEquals("● Starting…", lore(entry(entries("waiting"), "s2")).get(0));
    }

    @Test
    void orderAndIconsFollowConfig() throws Exception {
        List<Entry> all = entries("waiting");
        assertEquals(List.of("s3", "s2", "s1"), all.stream().map(Entry::season).toList());
        assertEquals(List.of("GRASS_BLOCK", "SAND", "OAK_SAPLING"), all.stream().map(Entry::icon).toList());
        assertEquals("Season 2 · 2024–2026", TestNetwork.plain(all.get(1).name()));
    }

    @Test
    void hangingPanelShowsUnknownAfterTimeout() throws Exception {
        net.running("s2");
        net.panel.hangs = true;
        SeasonsMenu quick = new SeasonsMenu(net::config, net.seasons, net.stats, net.messages, Duration.ofMillis(200));
        List<Entry> all = quick.entries("waiting").get(3, TimeUnit.SECONDS);
        assertEquals("? Unknown", lore(entry(all, "s2")).get(0));
    }

    @Test
    void payloadCarriesJsonComponents() throws Exception {
        s2Stats();
        JsonObject p = menu.payload(entries("waiting"));
        assertEquals("menu", p.get("type").getAsString());
        assertEquals(1, p.get("v").getAsInt());
        assertEquals("Seasons", TestNetwork.plain(GsonComponentSerializer.gson().deserializeFromTree(p.get("title"))));
        JsonObject s2 = p.getAsJsonArray("items").get(1).getAsJsonObject();
        assertEquals("s2", s2.get("season").getAsString());
        assertEquals("SAND", s2.get("icon").getAsString());
        assertFalse(s2.get("current").getAsBoolean());
        assertEquals(8, s2.getAsJsonArray("lore").size());
        assertEquals(2, s2.get("count").getAsInt(), "the season number, shown as the item count");
        assertEquals("41 players",
                TestNetwork.plain(GsonComponentSerializer.gson().deserializeFromTree(s2.getAsJsonArray("lore").get(2))));
    }

    @Test
    void numbersCanBeTurnedOff() throws Exception {
        net.config(c -> new sk.adamko.velospawn.config.PluginConfig(c.panelUrl(), c.apiKey(), c.authServer(),
                c.waitingServer(), c.newest(), c.seasons(), c.rememberLastServer(), c.prestartOnConnect(),
                c.startupTimeoutSeconds(), c.pollIntervalSeconds(), c.pingTimeoutMs(), c.chat(), c.messages(),
                c.mapMods(), new sk.adamko.velospawn.config.PluginConfig.SeasonsMenuConfig(false)));
        assertEquals(List.of(1, 1, 1), entries("waiting").stream().map(Entry::count).toList());
    }

    @Test
    void numbersAreShownByDefault() throws Exception {
        assertEquals(List.of(3, 2, 1), entries("waiting").stream().map(Entry::count).toList());
    }

    @Test
    void seasonNumberComesFromTheServerName() {
        assertEquals(3, SeasonsMenu.seasonNumber("s3"));
        assertEquals(12, SeasonsMenu.seasonNumber("season12"));
        assertEquals(1, SeasonsMenu.seasonNumber("skyblock"), "no digits: a single item");
        assertEquals(99, SeasonsMenu.seasonNumber("s150"), "an item shows at most 99");
        assertEquals(1, SeasonsMenu.seasonNumber("s0"));
    }

    @Test
    void chatListHasHeaderAndOneLinePerSeason() throws Exception {
        net.offline("s2");
        String text = TestNetwork.plain(menu.chatList(entries("waiting")));
        List<String> lines = List.of(text.split("\n"));
        assertEquals("── Seasons ──", lines.get(0));
        assertEquals(4, lines.size(), text);
        assertTrue(lines.get(2).contains("Season 2 · 2024–2026") && lines.get(2).contains("○ Offline"), lines.get(2));
    }
}
