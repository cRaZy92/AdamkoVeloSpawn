package sk.adamko.velospawn.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import sk.adamko.velospawn.AdamkoVeloSpawn;
import sk.adamko.velospawn.config.PluginConfig;

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
        PluginConfig cfg = plugin.config();
        String[] args = invocation.arguments();
        String season = args.length > 0 ? args[0].toLowerCase(Locale.ROOT)
                : plugin.router().destinationFor(player.getUniqueId());
        if (season == null || !cfg.isSeason(season)) {
            plugin.messages().send(player, "unknown-season",
                    "season", season == null ? "" : season,
                    "seasons", String.join(", ", cfg.seasonNames()));
            return;
        }
        String current = player.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("");
        if (current.equals(season)) {
            plugin.messages().send(player, "already-there",
                    "display", cfg.season(season).displayName(), "season", season);
            return;
        }
        var server = plugin.proxy().getServer(season);
        if (server.isEmpty() || current.isEmpty()) {
            return;
        }
        // Stay where you are (season or waiting room) while it starts; move once ready.
        plugin.seasons().isReadyNow(season).thenAccept(ready -> {
            if (ready) {
                player.createConnectionRequest(server.get()).fireAndForget();
            } else {
                plugin.queue().enqueue(player, season, current);
            }
        });
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
