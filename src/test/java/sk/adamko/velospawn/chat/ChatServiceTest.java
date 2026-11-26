package sk.adamko.velospawn.chat;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;
import sk.adamko.velospawn.testkit.TestNetwork;
import sk.adamko.velospawn.testkit.TestNetwork.FakePlayer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    private final TestNetwork net = new TestNetwork();

    private FakePlayer on(String name, String server) {
        FakePlayer p = net.player(name);
        p.server = server;
        return p;
    }

    private static SimpleCommand.Invocation invocation(CommandSource src, String... args) {
        SimpleCommand.Invocation inv = mock(SimpleCommand.Invocation.class);
        when(inv.source()).thenReturn(src);
        when(inv.arguments()).thenReturn(args);
        return inv;
    }

    @Test
    void disabledChatForwardsNothing() {
        FakePlayer ann = on("Ann", "s3");
        FakePlayer bob = on("Bob", "s2");
        net.chat(ann, "hi");
        assertTrue(bob.chat().isEmpty());
    }

    @Test
    void chatIsForwardedToOtherSeasonsOnly() {
        net.enableChat();
        FakePlayer ann = on("Ann", "s3");
        FakePlayer bob = on("Bob", "s2");
        FakePlayer cid = on("Cid", "s3");
        FakePlayer dan = on("Dan", "waiting");
        net.chat(ann, "hi <red>all");
        assertEquals(List.of("[S3] Ann: hi <red>all"), bob.chat());
        assertTrue(cid.chat().isEmpty(), "same server sees the backend line, not a duplicate");
        assertTrue(dan.chat().isEmpty(), "waiting room excluded");
        assertTrue(ann.chat().isEmpty());
    }

    @Test
    void chatFromExcludedServerIsNotForwarded() {
        net.enableChat();
        FakePlayer dan = on("Dan", "waiting");
        FakePlayer bob = on("Bob", "s2");
        net.chat(dan, "hello?");
        assertTrue(bob.chat().isEmpty());
    }

    @Test
    void networkJoinAndLeaveLines() {
        net.enableChat();
        net.running("s3");
        net.running("s2");
        FakePlayer bob = on("Bob", "s2");
        net.chat.onServerConnected(new com.velocitypowered.api.event.player.ServerConnectedEvent(
                bob.mock, net.server("s2"), null)); // Bob was announced already
        FakePlayer ann = net.login("Ann");
        assertEquals("s3", ann.server);
        assertEquals(List.of("Ann joined Season 3 · 2026"), bob.chat());

        net.connect(ann, "s2"); // switching seasons is not a new network join
        assertEquals(1, bob.chat().size(), "s2 sees its own join line, not a second network one");

        net.connect(ann, "s3");
        net.disconnect(ann);
        assertEquals(List.of("Ann joined Season 3 · 2026", "Ann moved to Season 3 · 2026", "Ann left Season 3 · 2026"),
                bob.chat());
    }

    @Test
    void discordLooksLikeEssentialsAndShowsSwitches() {
        net.enableChat();
        net.running("s3");
        net.running("s2");
        FakePlayer ann = net.login("Ann");          // limbo -> s3: one join, limbo not announced
        net.chat(ann, "hi *all*");
        net.connect(ann, "s2");                      // season switch
        net.connect(ann, "waiting");                 // excluded: not announced
        net.connect(ann, "s2");                      // back where she was: no duplicate switch
        net.disconnect(ann);
        assertEquals(List.of(
                "[Season 3] - Ann | ➡️ Ann has joined Season 3 · 2026!",
                "[Season 3] - Ann | hi \\*all\\*",
                "[Season 2] - Ann | 🔀 Ann moved to Season 2 · 2024–2026",
                "[Season 2] - Ann | ⬅️ Ann has left!"), net.discord());
        assertEquals("https://mc-heads.net/avatar/Ann/128",
                net.discordPosts.get(0).get("avatar_url").getAsString());
        assertEquals(0, net.discordPosts.get(0).getAsJsonObject("allowed_mentions")
                .getAsJsonArray("parse").size(), "no @everyone from Minecraft");
    }

    @Test
    void discordSwitchThroughWaitingRoomCountsAsOneMove() {
        net.enableChat();
        net.running("s3");
        net.offline("s1");
        FakePlayer ann = net.login("Ann");
        net.connect(ann, "s1");                      // s1 off: held on s3 while it starts
        net.running("s1");
        net.scheduler.advanceSeconds(2 + 5);
        assertEquals("s1", ann.server);
        assertEquals(List.of(
                "[Season 3] - Ann | ➡️ Ann has joined Season 3 · 2026!",
                "[Season 1] - Ann | 🔀 Ann moved to Season 1 · 2023"), net.discord());
    }

    @Test
    void discordEventCanBeTurnedOff() {
        net.enableChat();
        net.config(c -> {
            var d = c.chat().discord();
            var chat = new sk.adamko.velospawn.config.PluginConfig.ChatConfig(true, c.chat().format(),
                    c.chat().joinFormat(), c.chat().leaveFormat(), c.chat().excludeServers(),
                    new sk.adamko.velospawn.config.PluginConfig.DiscordConfig(d.webhook(), d.username(), d.avatar(),
                            d.chat(), d.join(), "", d.leave(), d.botToken(), d.channelId(), d.toMinecraft(),
                            d.afk(), d.afkReturn(), d.death(), d.advancement(), d.worlds()));
            return new sk.adamko.velospawn.config.PluginConfig(c.panelUrl(), c.apiKey(), c.authServer(),
                    c.waitingServer(), c.newest(), c.seasons(), c.rememberLastServer(), c.prestartOnConnect(),
                    c.startupTimeoutSeconds(), c.pollIntervalSeconds(), c.pingTimeoutMs(), chat, c.messages(),
                    c.mapMods());
        });
        net.running("s3");
        net.running("s2");
        FakePlayer ann = net.login("Ann");
        net.connect(ann, "s2");
        assertEquals(List.of("[Season 3] - Ann | ➡️ Ann has joined Season 3 · 2026!"), net.discord());
    }

    @Test
    void noWebhookPostsNothing() {
        net.enableChat();
        net.config(c -> {
            var chat = new sk.adamko.velospawn.config.PluginConfig.ChatConfig(true, c.chat().format(),
                    c.chat().joinFormat(), c.chat().leaveFormat(), c.chat().excludeServers(),
                    sk.adamko.velospawn.config.PluginConfig.DiscordConfig.NONE);
            return new sk.adamko.velospawn.config.PluginConfig(c.panelUrl(), c.apiKey(), c.authServer(),
                    c.waitingServer(), c.newest(), c.seasons(), c.rememberLastServer(), c.prestartOnConnect(),
                    c.startupTimeoutSeconds(), c.pollIntervalSeconds(), c.pingTimeoutMs(), chat, c.messages(),
                    c.mapMods());
        });
        net.running("s3");
        net.login("Ann");
        assertTrue(net.discordPosts.isEmpty());
    }

    @Test
    void discordMessagesReachEverySeasonButNotTheWaitingRooms() {
        net.enableChat();
        FakePlayer ann = on("Ann", "s3");
        FakePlayer bob = on("Bob", "s1");
        FakePlayer dan = on("Dan", "waiting");
        FakePlayer eve = on("Eve", "limbo");
        net.chat.fromDiscord(new DiscordBot.Chat("Brodykus", 0x55ff55, "hi <red>everyone"));
        assertEquals(List.of("[Discord] Brodykus: hi <red>everyone"), ann.chat(), "text stays literal");
        assertEquals(ann.chat(), bob.chat());
        assertTrue(dan.chat().isEmpty());
        assertTrue(eve.chat().isEmpty());
    }

    @Test
    void discordMessagesOffWhenChatIsOff() {
        FakePlayer ann = on("Ann", "s3");
        net.chat.fromDiscord(new DiscordBot.Chat("Brodykus", null, "hi"));
        assertTrue(ann.chat().isEmpty());
    }

    private com.velocitypowered.api.event.connection.PluginMessageEvent fromSeason(FakePlayer p, String json) {
        var e = new com.velocitypowered.api.event.connection.PluginMessageEvent(p.connectionNow(), p.mock,
                sk.adamko.velospawn.companion.CompanionChannel.EVENTS, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        net.companion.onPluginMessage(e);
        return e;
    }

    @Test
    void afkDeathsAndAdvancementsFromTheCompanionGoToDiscord() {
        net.enableChat();
        net.running("s1");
        FakePlayer bob = net.player("Bob");
        bob.server = "s1";
        var e = fromSeason(bob, "{\"v\":1,\"type\":\"afk\",\"player\":\"Bob\",\"afk\":true}");
        fromSeason(bob, "{\"v\":1,\"type\":\"afk\",\"player\":\"Bob\",\"afk\":false}");
        fromSeason(bob, "{\"v\":1,\"type\":\"death\",\"player\":\"Bob\",\"text\":\"Bob was blown up by Creeper\"}");
        fromSeason(bob, "{\"v\":1,\"type\":\"advancement\",\"player\":\"Bob\",\"text\":\"Diamonds!\"}");
        assertEquals(List.of(
                "[Season 1] - Bob | 🚶 Bob is now AFK!",
                "[Season 1] - Bob | ⌨️ Bob is no longer AFK!",
                "[Season 1] - Bob | 💀 Bob was blown up by Creeper",
                "[Season 1] - Bob | 🥇 Bob has completed the advancement **Diamonds!**!"), net.discord());
        assertTrue(!e.getResult().isAllowed(), "consumed by the proxy, never forwarded to the client");
    }

    @Test
    void afkDeathsAndAdvancementsAreShownOnTheOtherSeasons() {
        net.enableChat();
        net.running("s1");
        FakePlayer bob = on("Bob", "s1");
        FakePlayer cid = on("Cid", "s1");
        FakePlayer ann = on("Ann", "s3");
        FakePlayer dan = on("Dan", "waiting");
        fromSeason(bob, "{\"v\":1,\"type\":\"afk\",\"afk\":true}");
        fromSeason(bob, "{\"v\":1,\"type\":\"afk\",\"afk\":false}");
        fromSeason(bob, "{\"v\":1,\"type\":\"death\",\"text\":\"Bob was blown up by <red>Creeper\"}");
        fromSeason(bob, "{\"v\":1,\"type\":\"advancement\",\"text\":\"Diamonds!\"}");
        assertEquals(List.of(
                "[S1] Bob is now AFK",
                "[S1] Bob is no longer AFK",
                "[S1] Bob was blown up by <red>Creeper",
                "[S1] Bob has made the advancement [Diamonds!]"), ann.chat());
        assertTrue(cid.chat().isEmpty(), "Bob's own season already shows its events");
        assertTrue(dan.chat().isEmpty(), "waiting room excluded");
    }

    @Test
    void seasonSwitchIsShownEverywhereButTheDestination() {
        net.enableChat();
        net.running("s3");
        net.running("s2");
        FakePlayer bob = on("Bob", "s2");
        FakePlayer cid = on("Cid", "s3");
        for (FakePlayer p : List.of(bob, cid)) {
            net.chat.onServerConnected(new com.velocitypowered.api.event.player.ServerConnectedEvent(
                    p.mock, net.server(p.server), null));
        }
        int cidBefore = cid.chat().size();
        int bobBefore = bob.chat().size();
        FakePlayer ann = net.login("Ann");          // joins s3
        net.connect(ann, "s2");
        assertEquals(List.of("Ann moved to Season 2 · 2024–2026"), cid.chat().subList(cidBefore, cid.chat().size()), "s3 learns where she went");
        assertEquals(List.of("Ann joined Season 3 · 2026"), bob.chat().subList(bobBefore, bob.chat().size()), "s2 sees its own join line instead");
    }

    /** The colour of the first piece of text in a line that equals {@code text}, or null. */
    private static TextColor colorOf(Component line, String text) {
        if (line instanceof TextComponent t && t.content().equals(text)) {
            return t.color();
        }
        for (var child : line.children()) {
            var c = colorOf(child, text);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    @Test
    void seasonNamesTakeTheirConfiguredColour() {
        net.enableChat();
        net.running("s3");
        FakePlayer bob = on("Bob", "s2");
        FakePlayer cid = on("Cid", "s3");
        net.chat.onServerConnected(new com.velocitypowered.api.event.player.ServerConnectedEvent(
                bob.mock, net.server("s2"), null));
        net.login("Ann");
        net.chat(cid, "hi");
        assertEquals(TextColor.fromHexString("#ffaa00"),
                colorOf(bob.chatLines.get(0), "Season 3 · 2026"), "join line");
        assertEquals(TextColor.fromHexString("#ffaa00"),
                colorOf(bob.chatLines.get(1), "S3"), "chat prefix");
    }

    @Test
    void seasonColourAcceptsHexAndNames() {
        assertEquals(NamedTextColor.GOLD, ChatService.parseColor("Gold"));
        assertEquals(TextColor.color(0x55ffff), ChatService.parseColor(" #55FFFF "));
        assertEquals(null, ChatService.parseColor(""));
        assertEquals(null, ChatService.parseColor("purplish"));
    }

    @Test
    void companionEventsCanOnlyComeFromTheServerNotAPlayer() {
        net.enableChat();
        FakePlayer bob = on("Bob", "s1");
        var e = new com.velocitypowered.api.event.connection.PluginMessageEvent(bob.mock, bob.connectionNow(),
                sk.adamko.velospawn.companion.CompanionChannel.EVENTS,
                "{\"type\":\"death\",\"text\":\"Admin was banned\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        net.companion.onPluginMessage(e);
        assertTrue(net.discordPosts.isEmpty(), "a modded client can't post to Discord");
        assertTrue(!e.getResult().isAllowed(), "and it doesn't reach the season server either");
    }

    @Test
    void companionNameComesFromTheConnectionAndJunkIsIgnored() {
        net.enableChat();
        FakePlayer bob = on("Bob", "s2");
        fromSeason(bob, "not json");
        fromSeason(bob, "{\"type\":\"teleport\"}");
        fromSeason(bob, "{\"type\":\"death\",\"text\":\"\"}");
        fromSeason(bob, "{\"type\":\"afk\",\"player\":\"Notch\",\"afk\":true}");
        assertEquals(List.of("[Season 2] - Bob | 🚶 Bob is now AFK!"), net.discord());
    }

    private void world(FakePlayer p, String world, String env) {
        fromSeason(p, "{\"v\":1,\"type\":\"world\",\"world\":\"" + world + "\",\"env\":\"" + env + "\"}");
    }

    @Test
    void proxyRegistersTheCompanionChannelOnEverySeasonButNotTheWaitingRoom() {
        net.enableChat();
        net.running("s3");
        net.offline("s1");
        FakePlayer ann = net.login("Ann");
        assertTrue(ann.toBackend.contains("s3|minecraft:register|adamkovelospawn:events"));
        net.connect(ann, "waiting");
        assertTrue(ann.toBackend.stream().noneMatch(m -> m.startsWith("waiting|")));
    }

    @Test
    void discordNameShowsTheSeasonAndWorld() {
        net.enableChat();
        net.running("s2");
        FakePlayer ann = on("Ann", "s2");
        world(ann, "world_nether", "NETHER");
        net.chat(ann, "hot here");
        world(ann, "world", "NORMAL");
        fromSeason(ann, "{\"type\":\"death\",\"world\":\"world_the_end\",\"env\":\"THE_END\",\"text\":\"Ann fell out of the world\"}");
        assertEquals(List.of(
                "[Season 2 - Nether] - Ann | hot here",
                "[Season 2 - End] - Ann | 💀 Ann fell out of the world"), net.discord());
    }

    @Test
    void joinPostWaitsForTheWorldWhereTheCompanionRuns() {
        net.enableChat();
        net.running("s3");
        FakePlayer bob = on("Bob", "s3");
        world(bob, "world", "NORMAL"); // the proxy now knows s3 has the companion
        net.discordPosts.clear();

        FakePlayer ann = net.login("Ann");
        assertTrue(net.discord().isEmpty(), "held until the companion says where she is");
        world(ann, "world", "NORMAL");
        assertEquals(List.of("[Season 3 - World] - Ann | ➡️ Ann has joined Season 3 · 2026!"), net.discord());
        net.scheduler.advanceSeconds(10);
        assertEquals(1, net.discord().size(), "posted once, the fallback was cancelled");
    }

    @Test
    void joinPostGoesOutWithoutTheWorldIfTheCompanionStaysQuiet() {
        net.enableChat();
        net.running("s3");
        FakePlayer bob = on("Bob", "s3");
        world(bob, "world", "NORMAL");
        net.discordPosts.clear();
        net.login("Ann");
        net.scheduler.advanceSeconds(ChatService.WORLD_WAIT_SECONDS);
        assertEquals(List.of("[Season 3] - Ann | ➡️ Ann has joined Season 3 · 2026!"), net.discord());
    }

    @Test
    void quickLeaveStillPostsTheJoinFirst() {
        net.enableChat();
        net.running("s3");
        FakePlayer bob = on("Bob", "s3");
        world(bob, "world", "NORMAL");
        net.discordPosts.clear();
        FakePlayer ann = net.login("Ann");
        net.disconnect(ann);
        assertEquals(List.of(
                "[Season 3] - Ann | ➡️ Ann has joined Season 3 · 2026!",
                "[Season 3] - Ann | ⬅️ Ann has left!"), net.discord());
    }

    @Test
    void serversWithoutTheCompanionPostJoinsImmediately() {
        net.enableChat();
        net.running("s3");
        net.login("Ann");
        assertEquals(List.of("[Season 3] - Ann | ➡️ Ann has joined Season 3 · 2026!"), net.discord());
    }

    @Test
    void privateMessagesAndReplyAcrossServers() {
        net.enableChat();
        FakePlayer ann = on("Ann", "s3");
        FakePlayer bob = on("Bob", "s2");
        MsgCommand msg = new MsgCommand(net.chat, net.proxy, net.messages, false);
        MsgCommand reply = new MsgCommand(net.chat, net.proxy, net.messages, true);

        msg.execute(invocation(ann.mock, "bob", "hey", "there"));
        assertEquals(List.of("[me → Bob] hey there"), ann.chat());
        assertEquals(List.of("[Ann → me] hey there"), bob.chat());

        reply.execute(invocation(bob.mock, "yo"));
        assertEquals("[Bob → me] yo", ann.chat().get(1));
        assertEquals("[me → Ann] yo", bob.chat().get(1));
    }

    @Test
    void privateMessageErrors() {
        net.enableChat();
        FakePlayer ann = on("Ann", "s3");
        MsgCommand msg = new MsgCommand(net.chat, net.proxy, net.messages, false);
        MsgCommand reply = new MsgCommand(net.chat, net.proxy, net.messages, true);

        msg.execute(invocation(ann.mock, "Nobody", "hi"));
        msg.execute(invocation(ann.mock, "Bob"));
        reply.execute(invocation(ann.mock, "hi"));
        assertEquals(List.of(
                "Player Nobody is not online.",
                "Usage: /msg <player> <message>",
                "Nobody to reply to."), ann.chat());
    }

    @Test
    void replyTargetForgottenOnDisconnect() {
        net.enableChat();
        FakePlayer ann = on("Ann", "s3");
        FakePlayer bob = on("Bob", "s2");
        new MsgCommand(net.chat, net.proxy, net.messages, false).execute(invocation(ann.mock, "Bob", "hi"));
        net.disconnect(ann);
        new MsgCommand(net.chat, net.proxy, net.messages, true).execute(invocation(bob.mock, "still there?"));
        assertEquals("Nobody to reply to.", bob.chat().get(bob.chat().size() - 1));
    }
}
