package sk.adamko.velospawn;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import sk.adamko.velospawn.chat.ChatService;
import sk.adamko.velospawn.chat.DiscordBot;
import sk.adamko.velospawn.command.AvsCommand;
import sk.adamko.velospawn.command.PlayCommand;
import sk.adamko.velospawn.command.SeasonsCommand;
import sk.adamko.velospawn.companion.CompanionChannel;
import sk.adamko.velospawn.config.ConfigLoader;
import sk.adamko.velospawn.config.PluginConfig;
import sk.adamko.velospawn.mapmod.MapIdService;
import sk.adamko.velospawn.menu.SeasonsMenu;
import sk.adamko.velospawn.pelican.PelicanClient;
import sk.adamko.velospawn.route.Router;
import sk.adamko.velospawn.route.WaitQueue;
import sk.adamko.velospawn.season.SeasonManager;
import sk.adamko.velospawn.store.LastServerStore;
import sk.adamko.velospawn.store.SeasonStatsStore;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Plugin(
        id = "adamkovelospawn",
        name = "AdamkoVeloSpawn",
        version = BuildConstants.VERSION,
        description = "Routing, on-demand seasons, waiting room, idle shutdown and network chat.",
        authors = {"Adam Šromovský"},
        dependencies = {@Dependency(id = "luckperms", optional = true)})
public final class AdamkoVeloSpawn {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private final HttpClient http = PelicanClient.newHttpClient();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AdamkoVeloSpawn-io");
        t.setDaemon(true);
        return t;
    });

    private volatile PluginConfig config;
    private volatile PelicanClient pelican;
    private Messages messages;
    private SeasonManager seasons;
    private WaitQueue queue;
    private LastServerStore lastServers;
    private Router router;
    private ChatService chat;
    private DiscordBot discordBot;
    private MapIdService mapIds;
    private SeasonStatsStore seasonStats;
    private CompanionChannel companion;

    @Inject
    public AdamkoVeloSpawn(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            config = ConfigLoader.load(dataDirectory);
        } catch (IOException | RuntimeException e) {
            logger.error("Could not load config.yml; AdamkoVeloSpawn is NOT active", e);
            return;
        }
        pelican = new PelicanClient(http, config.panelUrl(), config.apiKey());
        validate(config);

        messages = new Messages(this::config);
        seasons = new SeasonManager(this, proxy, logger, this::config, this::pelican);
        queue = new WaitQueue(this, proxy, logger, this::config, seasons, messages);
        seasons.setWaiterCheck(queue::hasWaiters);
        lastServers = new LastServerStore(dataDirectory.resolve("last-servers.json"), logger, ioExecutor);
        lastServers.load();
        seasonStats = new SeasonStatsStore(dataDirectory.resolve("season-stats.json"), logger, ioExecutor);
        seasonStats.load();
        router = new Router(this, proxy, logger, this::config, seasons, queue, lastServers, messages);
        chat = new ChatService(this, proxy, logger, this::config, messages, http);

        proxy.getEventManager().register(this, router);
        proxy.getEventManager().register(this, chat);
        companion = new CompanionChannel(proxy, logger, this::config, chat, seasonStats);
        proxy.getEventManager().register(this, companion);
        companion.registerChannel();
        mapIds = new MapIdService(this, proxy, logger, this::config);
        proxy.getEventManager().register(this, mapIds);
        mapIds.start();

        var commands = proxy.getCommandManager();
        commands.register(commands.metaBuilder("avs").aliases("adamkovelospawn").plugin(this).build(),
                new AvsCommand(this));
        commands.register(commands.metaBuilder("play").plugin(this).build(), new PlayCommand(this));
        SeasonsMenu seasonsMenu = new SeasonsMenu(this::config, seasons, seasonStats, messages);
        commands.register(commands.metaBuilder("seasons").plugin(this).build(),
                new SeasonsCommand(seasonsMenu, companion));
        companion.setMenuClickHandler((player, season) ->
                SeasonsCommand.onMenuClick(router, this::config, player, season));
        chat.registerCommands();
        discordBot = new DiscordBot(logger, () -> this.config.chat().discord(), () -> this.config.chat().enabled(),
                chat::fromDiscord);
        discordBot.apply();

        queue.startTicker();
        seasons.startSweeper(); // re-arms idle shutdown for seasons that kept running over a proxy restart

        logger.info("AdamkoVeloSpawn {} ready: seasons {}, newest {}, waiting room '{}'",
                BuildConstants.VERSION, config.seasonNames(), config.newest(), config.waitingServer());
        if (config.mapMods().enabled()) {
            logger.info("Map-mod world IDs: {}", mapIds.describeIds());
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (seasons != null) {
            seasons.shutdown();
        }
        if (queue != null) {
            queue.stopTicker();
        }
        if (mapIds != null) {
            mapIds.stop();
        }
        if (discordBot != null) {
            discordBot.shutdown();
        }
        if (lastServers != null) {
            lastServers.save();
        }
        if (seasonStats != null) {
            seasonStats.save();
        }
        ioExecutor.shutdown();
    }

    /** Reloads config.yml; on failure the old config stays active. */
    public void reload() throws IOException {
        PluginConfig fresh = ConfigLoader.load(dataDirectory);
        validate(fresh);
        config = fresh;
        pelican = new PelicanClient(http, fresh.panelUrl(), fresh.apiKey());
        seasons.retainConfigured();
        chat.registerCommands();
        mapIds.start();
        discordBot.apply();
        logger.info("Config reloaded: seasons {}, newest {}", fresh.seasonNames(), fresh.newest());
    }

    private void validate(PluginConfig cfg) {
        if (cfg.seasons().isEmpty()) {
            logger.error("No seasons configured under servers.seasons");
        }
        if (!cfg.isSeason(cfg.newest())) {
            logger.error("servers.newest '{}' is not one of the configured seasons {}", cfg.newest(), cfg.seasonNames());
        }
        for (String name : cfg.seasonNames()) {
            if (proxy.getServer(name).isEmpty()) {
                logger.error("Season '{}' is not registered in velocity.toml [servers]", name);
            }
            if (cfg.season(name).panelId().isBlank()) {
                logger.error("Season '{}' has no panel-id", name);
            }
        }
        for (String name : List.of(cfg.waitingServer(), cfg.authServer())) {
            if (proxy.getServer(name).isEmpty()) {
                logger.error("Server '{}' is not registered in velocity.toml [servers]", name);
            }
        }
        List<String> tryOrder = proxy.getConfiguration().getAttemptConnectionOrder();
        if (!tryOrder.equals(List.of(cfg.waitingServer()))) {
            logger.warn("velocity.toml try = {} — recommended: try = [\"{}\"] so logins and kick fallbacks "
                    + "always land in the waiting room", tryOrder, cfg.waitingServer());
        }
        if (cfg.panelUrl().isBlank() || cfg.panelUrl().contains(".x") || cfg.apiKey().endsWith("...")) {
            logger.warn("panel.url / panel.api-key look like the placeholders; seasons cannot be started");
        }
    }

    public PluginConfig config() {
        return config;
    }

    PelicanClient pelican() {
        return pelican;
    }

    public ProxyServer proxy() {
        return proxy;
    }

    public Logger logger() {
        return logger;
    }

    public Messages messages() {
        return messages;
    }

    public SeasonManager seasons() {
        return seasons;
    }

    public WaitQueue queue() {
        return queue;
    }

    public Router router() {
        return router;
    }
}
