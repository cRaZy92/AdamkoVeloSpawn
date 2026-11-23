package sk.adamko.velospawn.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import sk.adamko.velospawn.AdamkoVeloSpawn;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * /play [season] — for everyone. Goes to the given season (or the player's usual one);
 * the router starts it and holds the player if it is off.
 */
public final class PlayCommand implements SimpleCommand {

    private final AdamkoVeloSpawn plugin;

    public PlayCommand(AdamkoVeloSpawn plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendPlainMessage("Only players can use /play.");
            return;
        }
        String[] args = invocation.arguments();
        String season = args.length > 0 ? args[0].toLowerCase(Locale.ROOT)
                : plugin.router().destinationFor(player.getUniqueId());
        plugin.router().play(player, season);
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        String[] args = invocation.arguments();
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (args.length > 1) {
            return CompletableFuture.completedFuture(List.of());
        }
        return CompletableFuture.completedFuture(plugin.config().seasonNames().stream()
                .filter(s -> s.startsWith(prefix)).toList());
    }
}
