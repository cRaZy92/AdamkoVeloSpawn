package sk.adamko.velospawn.chat;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
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
        assertEquals(List.of("[s3] Ann: hi <red>all"), bob.chat());
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
        assertEquals(List.of("[+] Ann joined s3"), bob.chat());

        net.connect(ann, "s2"); // switching seasons is not a new network join
        assertEquals(1, bob.chat().size());

        net.connect(ann, "s3");
        net.disconnect(ann);
        assertEquals("[-] Ann left the network", bob.chat().get(1));
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
                "[s3] - Ann | ➡️ Ann has joined!",
                "[s3] - Ann | hi \\*all\\*",
                "[s2] - Ann | 🔀 Ann moved to Season 2 · 2024–2026",
                "[s2] - Ann | ⬅️ Ann has left!"), net.discord());
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
                "[s3] - Ann | ➡️ Ann has joined!",
                "[s1] - Ann | 🔀 Ann moved to Season 1 · 2023"), net.discord());
    }

    @Test
    void discordEventCanBeTurnedOff() {
        net.enableChat();
        net.config(c -> {
            var d = c.chat().discord();
            var chat = new sk.adamko.velospawn.config.PluginConfig.ChatConfig(true, c.chat().format(),
                    c.chat().joinFormat(), c.chat().leaveFormat(), c.chat().excludeServers(),
                    new sk.adamko.velospawn.config.PluginConfig.DiscordConfig(d.webhook(), d.username(), d.avatar(),
                            d.chat(), d.join(), "", d.leave()));
            return new sk.adamko.velospawn.config.PluginConfig(c.panelUrl(), c.apiKey(), c.authServer(),
                    c.waitingServer(), c.newest(), c.seasons(), c.rememberLastServer(), c.prestartOnConnect(),
                    c.startupTimeoutSeconds(), c.pollIntervalSeconds(), c.pingTimeoutMs(), chat, c.messages(),
                    c.mapMods());
        });
        net.running("s3");
        net.running("s2");
        FakePlayer ann = net.login("Ann");
        net.connect(ann, "s2");
        assertEquals(List.of("[s3] - Ann | ➡️ Ann has joined!"), net.discord());
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
