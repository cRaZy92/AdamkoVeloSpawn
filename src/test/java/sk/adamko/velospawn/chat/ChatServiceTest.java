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
