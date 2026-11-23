package sk.adamko.velospawn.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import sk.adamko.velospawn.companion.CompanionChannel;
import sk.adamko.velospawn.config.PluginConfig;
import sk.adamko.velospawn.menu.SeasonsMenu;
import sk.adamko.velospawn.route.Router;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * /seasons — for everyone. On a season server running the companion it opens the chest menu there;
 * anywhere else (waiting room, auth limbo) it shows the same seasons as a clickable chat list.
 */
public final class SeasonsCommand implements SimpleCommand {

    private final SeasonsMenu menu;
    private final CompanionChannel companion;

    public SeasonsCommand(SeasonsMenu menu, CompanionChannel companion) {
        this.menu = menu;
        this.companion = companion;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendPlainMessage("Only players can use /seasons.");
            return;
        }
        open(player);
    }

    public void open(Player player) {
        Optional<ServerConnection> conn = player.getCurrentServer();
        String current = conn.map(c -> c.getServerInfo().getName()).orElse("");
        menu.entries(current).thenAccept(entries -> {
            Optional<ServerConnection> now = player.getCurrentServer();
            boolean sameServer = now.isPresent() && now.get().getServerInfo().getName().equals(current);
            if (sameServer && companion.canDrawMenu(current)) {
                companion.send(now.get(), menu.payload(entries));
            } else {
                player.sendMessage(menu.chatList(entries));
            }
        });
    }

    /** A click in the chest menu: the same as /play for that season; anything not a season is ignored. */
    public static void onMenuClick(Router router, Supplier<PluginConfig> config, Player player, String season) {
        if (config.get().isSeason(season)) {
            router.play(player, season);
        }
    }
}
