package sk.adamko.velospawn.menu;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import sk.adamko.velospawn.Messages;
import sk.adamko.velospawn.config.PluginConfig;
import sk.adamko.velospawn.season.SeasonManager;
import sk.adamko.velospawn.store.SeasonStatsStore;
import sk.adamko.velospawn.store.SeasonStatsStore.SeasonStats;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The /seasons menu: every configured season with its live state and history. Everything the player
 * reads is rendered here from the {@code messages} config, so the companion only puts it on items.
 */
public final class SeasonsMenu {

    public enum LiveState { ONLINE, STARTING, OFFLINE, UNKNOWN }

    /** One season in the menu; {@code icon} is a Minecraft item id, {@code lore} the tooltip lines. */
    public record Entry(String season, String icon, Component name, List<Component> lore, boolean current) {
    }

    static final long TICKS_PER_DAY = 24_000;
    static final long TICKS_PER_HOUR = 20 * 3600;
    private static final Duration LIVE_TIMEOUT = Duration.ofSeconds(2);

    private final Supplier<PluginConfig> config;
    private final SeasonManager seasons;
    private final SeasonStatsStore stats;
    private final Messages messages;
    private final Duration liveTimeout;

    public SeasonsMenu(Supplier<PluginConfig> config, SeasonManager seasons, SeasonStatsStore stats, Messages messages) {
        this(config, seasons, stats, messages, LIVE_TIMEOUT);
    }

    SeasonsMenu(Supplier<PluginConfig> config, SeasonManager seasons, SeasonStatsStore stats, Messages messages,
                Duration liveTimeout) {
        this.config = config;
        this.seasons = seasons;
        this.stats = stats;
        this.messages = messages;
        this.liveTimeout = liveTimeout;
    }

    /** The menu for a player standing on {@code currentServer}, in config order. Never starts anything. */
    public CompletableFuture<List<Entry>> entries(String currentServer) {
        PluginConfig cfg = config.get();
        List<CompletableFuture<Entry>> futures = new ArrayList<>();
        for (String season : cfg.seasonNames()) {
            PluginConfig.SeasonConfig sc = cfg.season(season);
            futures.add(liveState(season).thenApply(state -> entry(sc, state, season.equals(currentServer))));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(v -> futures.stream().map(CompletableFuture::join).toList());
    }

    private CompletableFuture<LiveState> liveState(String season) {
        if (seasons.isStarting(season)) {
            return CompletableFuture.completedFuture(LiveState.STARTING);
        }
        CompletableFuture<Boolean> ready;
        try {
            ready = seasons.isReadyNow(season);
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(LiveState.UNKNOWN);
        }
        return ready
                .thenApply(up -> up ? LiveState.ONLINE
                        : seasons.isStarting(season) ? LiveState.STARTING : LiveState.OFFLINE)
                .completeOnTimeout(LiveState.UNKNOWN, liveTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(t -> LiveState.UNKNOWN);
    }

    private Entry entry(PluginConfig.SeasonConfig sc, LiveState state, boolean current) {
        Component stateLine = switch (state) {
            case ONLINE -> messages.get("seasons-state-online", "count", seasons.playerCount(sc.name()));
            case STARTING -> messages.get("seasons-state-starting");
            case OFFLINE -> messages.get("seasons-state-offline");
            case UNKNOWN -> messages.get("seasons-state-unknown");
        };
        Optional<SeasonStats> s = stats.get(sc.name());
        List<Component> lore = new ArrayList<>();
        if (s.isPresent()) {
            SeasonStats st = s.get();
            lore.addAll(lines("seasons-lore",
                    "state", stateLine,
                    "players", number(st.players()),
                    "days", number(st.gameTime() / TICKS_PER_DAY),
                    "hours", number(st.playtimeTicks() / TICKS_PER_HOUR),
                    "top", st.topName() == null ? "—" : st.topName(),
                    "top_hours", number(st.topTicks() / TICKS_PER_HOUR),
                    "deaths", number(st.deaths())));
        } else {
            lore.addAll(lines("seasons-lore-no-stats", "state", stateLine));
        }
        lore.add(messages.get(current ? "seasons-here" : "seasons-click"));
        Component name = Messages.render("<white>{display}", "display", sc.displayName());
        return new Entry(sc.name(), sc.icon(), name, List.copyOf(lore), current);
    }

    /** A multi-line template: split at newlines first, so each tooltip line is its own component. */
    private List<Component> lines(String key, Object... keyValues) {
        List<Component> out = new ArrayList<>();
        for (String line : config.get().message(key).split("\n", -1)) {
            out.add(Messages.render(line, keyValues));
        }
        return out;
    }

    private static String number(long n) {
        return String.format(Locale.US, "%,d", n);
    }

    /** The message the companion draws the chest from. */
    public JsonObject payload(List<Entry> entries) {
        GsonComponentSerializer gson = GsonComponentSerializer.gson();
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("type", "menu");
        o.add("title", gson.serializeToTree(messages.get("seasons-title")));
        JsonArray items = new JsonArray();
        for (Entry e : entries) {
            JsonObject item = new JsonObject();
            item.addProperty("season", e.season());
            item.addProperty("icon", e.icon());
            item.add("name", gson.serializeToTree(e.name()));
            JsonArray lore = new JsonArray();
            e.lore().forEach(line -> lore.add(gson.serializeToTree(line)));
            item.add("lore", lore);
            item.addProperty("current", e.current());
            items.add(item);
        }
        o.add("items", items);
        return o;
    }

    /** Where no chest can be drawn: one chat line per season, tooltip on hover, click to visit. */
    public Component chatList(List<Entry> entries) {
        List<Component> lines = new ArrayList<>();
        lines.add(messages.get("seasons-chat-header"));
        for (Entry e : entries) {
            Component hover = Component.join(JoinConfiguration.newlines(),
                    prepend(e.name(), e.lore()));
            Component line = messages.get("seasons-chat-line", "name", e.name(), "state", e.lore().get(0))
                    .hoverEvent(HoverEvent.showText(hover));
            if (!e.current()) {
                line = line.clickEvent(ClickEvent.runCommand("/play " + e.season()));
            }
            lines.add(line);
        }
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    private static List<Component> prepend(Component first, List<Component> rest) {
        List<Component> all = new ArrayList<>(rest.size() + 1);
        all.add(first);
        all.addAll(rest);
        return all;
    }
}
