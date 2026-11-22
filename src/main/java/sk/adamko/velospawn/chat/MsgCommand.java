package sk.adamko.velospawn.chat;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import sk.adamko.velospawn.Messages;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Cross-server /msg (reply=false) and /r (reply=true). */
final class MsgCommand implements SimpleCommand {

    private final ChatService chat;
    private final ProxyServer proxy;
    private final Messages messages;
    private final boolean reply;

    MsgCommand(ChatService chat, ProxyServer proxy, Messages messages, boolean reply) {
        this.chat = chat;
        this.proxy = proxy;
        this.messages = messages;
        this.reply = reply;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();
        Player target;
        String text;

        if (reply) {
            if (args.length == 0) {
                messages.send(src, "reply-usage");
                return;
            }
            if (!(src instanceof Player sender)) {
                src.sendPlainMessage("Only players can reply.");
                return;
            }
            Optional<Player> partner = chat.replyTarget(sender.getUniqueId()).flatMap(proxy::getPlayer);
            if (partner.isEmpty()) {
                messages.send(src, "msg-no-reply");
                return;
            }
            target = partner.get();
            text = String.join(" ", args);
        } else {
            if (args.length < 2) {
                messages.send(src, "msg-usage");
                return;
            }
            Optional<Player> found = proxy.getPlayer(args[0]);
            if (found.isEmpty()) {
                messages.send(src, "msg-player-not-found", "target", args[0]);
                return;
            }
            target = found.get();
            text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        }

        String senderName = src instanceof Player p ? p.getUsername() : "Console";
        messages.send(src, "msg-sent", "target", target.getUsername(), "message", text);
        messages.send(target, "msg-received", "sender", senderName, "message", text);
        if (src instanceof Player p) {
            chat.recordConversation(p.getUniqueId(), target.getUniqueId());
        }
        chat.logPrivate(senderName, target.getUsername(), text);
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        String[] args = invocation.arguments();
        if (reply || args.length > 1) {
            return CompletableFuture.completedFuture(List.of());
        }
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return CompletableFuture.completedFuture(proxy.getAllPlayers().stream()
                .map(Player::getUsername)
                .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList());
    }
}
