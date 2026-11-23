package sk.adamko.velospawn.menu;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import sk.adamko.velospawn.Messages;
import sk.adamko.velospawn.config.PluginConfig;
import sk.adamko.velospawn.store.SeenSeasonsStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Tells players about /seasons once per login, but only when there is something to tell: on their very
 * first visit ({@code seasons-hint-first}), or when a season was added since they were last told
 * ({@code seasons-hint-new}, with the new seasons' names). Sent a moment after they reach a season server
 * (never in the auth or waiting limbo), so it isn't buried under the backend's own join messages.
 */
public final class SeasonsHint {

    static final long DELAY_SECONDS = 2;

    private final Object plugin;
    private final ProxyServer proxy;
    private final Supplier<PluginConfig> config;
    private final Messages messages;
    private final SeenSeasonsStore seen;
    /** Players already checked this login. */
    private final Set<UUID> checked = ConcurrentHashMap.newKeySet();

    public SeasonsHint(Object plugin, ProxyServer proxy, Supplier<PluginConfig> config, Messages messages,
                       SeenSeasonsStore seen) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.config = config;
        this.messages = messages;
        this.seen = seen;
    }

    @Subscribe
    public void onPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        String server = player.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse(null);
        if (server == null || !config.get().isSeason(server)) {
            return; // auth or waiting limbo: wait until they reach an actual season
        }
        if (!checked.add(player.getUniqueId())) {
            return;
        }
        proxy.getScheduler().buildTask(plugin, () -> {
            if (!player.isActive()) {
                return;
            }
            boolean onSeason = player.getCurrentServer()
                    .map(c -> config.get().isSeason(c.getServerInfo().getName())).orElse(false);
            if (onSeason) {
                tell(player);
            } else {
                checked.remove(player.getUniqueId()); // sent back to a limbo meanwhile: try at the next season
            }
        }).delay(DELAY_SECONDS, TimeUnit.SECONDS).schedule();
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        checked.remove(event.getPlayer().getUniqueId());
    }

    void tell(Player player) {
        PluginConfig cfg = config.get();
        List<String> current = cfg.seasonNames();
        if (current.isEmpty()) {
            return;
        }
        var before = seen.get(player.getUniqueId());
        if (before.isEmpty()) {
            messages.send(player, "seasons-hint-first");
        } else {
            List<Component> added = new ArrayList<>();
            for (String season : current) {
                if (!before.get().contains(season)) {
                    added.add(Component.text(cfg.season(season).displayName()));
                }
            }
            if (!added.isEmpty()) {
                messages.send(player, "seasons-hint-new",
                        "seasons", Component.join(JoinConfiguration.commas(true), added));
            }
        }
        seen.markSeen(player.getUniqueId(), current);
    }
}
