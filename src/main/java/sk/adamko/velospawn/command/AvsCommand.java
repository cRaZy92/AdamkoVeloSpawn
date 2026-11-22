package sk.adamko.velospawn.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.minimessage.MiniMessage;
import sk.adamko.velospawn.AdamkoVeloSpawn;
import sk.adamko.velospawn.config.PluginConfig;
import sk.adamko.velospawn.pelican.PelicanClient;
import sk.adamko.velospawn.season.SeasonManager;
import sk.adamko.velospawn.season.SeasonManager.LiveStatus;
import sk.adamko.velospawn.route.WaitQueue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** /avs status|start|stop|reload — admin only. */
public final class AvsCommand implements SimpleCommand {

    public static final String PERMISSION = "adamkovelospawn.admin";
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final List<String> SUBS = List.of("status", "start", "stop", "reload");

    private final AdamkoVeloSpawn plugin;

    public AvsCommand(AdamkoVeloSpawn plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(PERMISSION);
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src = invocation.source();
        String[] args = invocation.arguments();
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(src);
            case "start" -> withSeason(src, args, "start", this::start);
            case "stop" -> withSeason(src, args, "stop", this::stop);
            case "reload" -> reload(src);
            default -> send(src, "<red>Usage: /avs status | start \\<season> | stop \\<season> | reload");
        }
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        String[] args = invocation.arguments();
        List<String> out = new ArrayList<>();
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            SUBS.stream().filter(s -> s.startsWith(prefix)).forEach(out::add);
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("start") || args[0].equalsIgnoreCase("stop"))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            plugin.config().seasonNames().stream().filter(s -> s.startsWith(prefix)).forEach(out::add);
        }
        return CompletableFuture.completedFuture(out);
    }

    private interface SeasonAction {
        void run(CommandSource src, String season);
    }

    private void withSeason(CommandSource src, String[] args, String verb, SeasonAction action) {
        if (args.length < 2) {
            send(src, "<red>Usage: /avs " + verb + " \\<season>");
            return;
        }
        String season = args[1];
        if (!plugin.config().isSeason(season)) {
            send(src, "<red>Unknown season <white>" + escape(season) + "</white>. Seasons: "
                    + String.join(", ", plugin.config().seasonNames()));
            return;
        }
        action.run(src, season);
    }

    private void status(CommandSource src) {
        PluginConfig cfg = plugin.config();
        SeasonManager seasons = plugin.seasons();
        WaitQueue queue = plugin.queue();
        List<CompletableFuture<String>> lines = new ArrayList<>();
        for (String season : cfg.seasonNames()) {
            PluginConfig.SeasonConfig sc = cfg.season(season);
            lines.add(seasons.liveStatus(season).handle((st, t) -> {
                StringBuilder sb = new StringBuilder();
                sb.append("<white>").append(escape(season)).append("</white> <gray>(")
                        .append(escape(sc.displayName())).append(")</gray> ");
                sb.append(t != null ? "<red>check failed" : describe(st));
                sb.append(" <gray>· ").append(seasons.playerCount(season)).append(" online");
                long q = queue.count(season);
                if (q > 0) {
                    sb.append(" · ").append(q).append(" queued");
                }
                seasons.startElapsedSeconds(season).ifPresent(s -> sb.append(" · starting ").append(s).append("s"));
                seasons.idleRemainingSeconds(season).ifPresent(s ->
                        sb.append(" · idle stop in ").append(s / 60).append("m").append(s % 60).append("s"));
                if (season.equals(cfg.newest())) {
                    sb.append(" · <aqua>newest</aqua>");
                }
                return sb.toString();
            }));
        }
        CompletableFuture.allOf(lines.toArray(CompletableFuture[]::new)).thenRun(() -> {
            send(src, "<gold>AdamkoVeloSpawn</gold> <gray>— waiting room: <white>"
                    + escape(cfg.waitingServer()) + "</white>, auth: <white>" + escape(cfg.authServer()));
            lines.forEach(l -> send(src, l.join()));
        });
    }

    private static String describe(LiveStatus st) {
        String ping = st.pingOk() ? "<green>answers ping</green>" : "<red>no ping</red>";
        String panel = st.panelError() != null
                ? "<red>panel unreachable</red>"
                : colour(st.panelState()) + st.panelState().name().toLowerCase(Locale.ROOT);
        return panel + " <gray>/</gray> " + ping;
    }

    private static String colour(PelicanClient.State s) {
        return switch (s) {
            case RUNNING -> "<green>";
            case STARTING, STOPPING -> "<yellow>";
            case OFFLINE -> "<gray>";
            case UNKNOWN -> "<red>";
        };
    }

    private void start(CommandSource src, String season) {
        send(src, "<yellow>Starting " + escape(season) + "…");
        plugin.seasons().ensureReady(season).thenAccept(outcome -> send(src, switch (outcome) {
            case READY -> "<green>" + escape(season) + " is ready.";
            case TIMEOUT -> "<red>" + escape(season) + " didn't come up in time (see console).";
            case PANEL_UNREACHABLE -> "<red>Panel unreachable; " + escape(season) + " not started (see console).";
            case FAILED -> "<red>Starting " + escape(season) + " failed (see console).";
        }));
    }

    private void stop(CommandSource src, String season) {
        plugin.seasons().stop(season).whenComplete((v, t) -> {
            if (t != null) {
                send(src, "<red>Stop failed: " + escape(PelicanClient.unwrap(t).getMessage()));
            } else {
                send(src, "<green>Stop signal sent to " + escape(season) + ".");
            }
        });
    }

    private void reload(CommandSource src) {
        try {
            plugin.reload();
            send(src, "<green>AdamkoVeloSpawn reloaded. Seasons: "
                    + escape(String.join(", ", plugin.config().seasonNames())));
        } catch (Exception e) {
            plugin.logger().error("Reload failed", e);
            send(src, "<red>Reload failed, keeping the old config: " + escape(String.valueOf(e.getMessage())));
        }
    }

    private static void send(CommandSource src, String miniMessage) {
        src.sendMessage(MM.deserialize(miniMessage));
    }

    private static String escape(String s) {
        return MM.escapeTags(s == null ? "" : s);
    }
}
