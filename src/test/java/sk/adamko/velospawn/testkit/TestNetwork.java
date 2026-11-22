package sk.adamko.velospawn.testkit;

import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.proxy.server.ServerPing;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;
import org.slf4j.helpers.NOPLogger;
import sk.adamko.velospawn.Messages;
import sk.adamko.velospawn.chat.ChatService;
import sk.adamko.velospawn.config.ConfigLoader;
import sk.adamko.velospawn.config.PluginConfig;
import sk.adamko.velospawn.mapmod.MapIdService;
import sk.adamko.velospawn.pelican.PelicanClient;
import sk.adamko.velospawn.pelican.PelicanClient.State;
import sk.adamko.velospawn.route.Router;
import sk.adamko.velospawn.route.WaitQueue;
import sk.adamko.velospawn.season.SeasonManager;
import sk.adamko.velospawn.store.LastServerStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A simulated Velocity network wired to the real plugin components. Connects go through
 * {@link Router#onPreConnect} and fire {@link ServerConnectedEvent} like Velocity does,
 * so moves made by the wait queue exercise the full routing path. Everything is synchronous;
 * time only passes via {@link #scheduler}.
 */
public final class TestNetwork {

    public static final String S3_PANEL = "test0003";
    public static final String S2_PANEL = "test0002";
    public static final String S1_PANEL = "test0001";

    public final Object plugin = new Object();
    public final Logger logger = NOPLogger.NOP_LOGGER;
    public final FakeScheduler scheduler = new FakeScheduler();
    public final FakePanel panel = new FakePanel();
    public final ProxyServer proxy = mock(ProxyServer.class);
    public final Path dataDir;

    private volatile PluginConfig config;
    public final Messages messages;
    public final SeasonManager seasons;
    public final WaitQueue queue;
    public final LastServerStore lastServers;
    public final Router router;
    public final ChatService chat;
    public final MapIdService mapIds;

    private final Map<String, RegisteredServer> servers = new LinkedHashMap<>();
    private final Map<String, Boolean> up = new HashMap<>();
    private final Map<String, Integer> rejectConnects = new HashMap<>();
    private final Map<UUID, FakePlayer> players = new LinkedHashMap<>();
    /** Every server a player actually landed on, in order, per player name. */
    public final Map<String, List<String>> visits = new HashMap<>();

    public TestNetwork() {
        try {
            dataDir = Files.createTempDirectory("avs-test");
            config = ConfigLoader.load(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (String name : List.of("limbo", "waiting", "s1", "s2", "s3")) {
            addServer(name);
        }
        up.put("limbo", true);
        up.put("waiting", true);

        when(proxy.getScheduler()).thenReturn(scheduler);
        when(proxy.getServer(anyString())).thenAnswer(i -> Optional.ofNullable(servers.get(i.<String>getArgument(0))));
        when(proxy.getPlayer(any(UUID.class))).thenAnswer(i -> Optional.ofNullable(players.get(i.<UUID>getArgument(0)))
                .filter(p -> p.active).map(p -> p.mock));
        when(proxy.getPlayer(anyString())).thenAnswer(i -> players.values().stream()
                .filter(p -> p.active && p.name.equalsIgnoreCase(i.getArgument(0))).findFirst().map(p -> p.mock));
        when(proxy.getAllPlayers()).thenAnswer(i -> players.values().stream()
                .filter(p -> p.active).map(p -> p.mock).toList());
        PluginManager pm = mock(PluginManager.class);
        when(pm.getPlugin(anyString())).thenReturn(Optional.empty());
        when(proxy.getPluginManager()).thenReturn(pm);

        messages = new Messages(this::config);
        seasons = new SeasonManager(plugin, proxy, logger, this::config, () -> panel, scheduler::now);
        queue = new WaitQueue(plugin, proxy, logger, this::config, seasons, messages, scheduler::now);
        seasons.setWaiterCheck(queue::hasWaiters);
        lastServers = new LastServerStore(dataDir.resolve("last-servers.json"), logger, Runnable::run);
        router = new Router(plugin, proxy, logger, this::config, seasons, queue, lastServers, messages);
        chat = new ChatService(plugin, proxy, logger, this::config, messages, PelicanClient.newHttpClient());
        when(proxy.getChannelRegistrar()).thenReturn(mock(com.velocitypowered.api.proxy.messages.ChannelRegistrar.class));
        mapIds = new MapIdService(plugin, proxy, logger, this::config);
    }

    // ------------------------------------------------------------------ config

    public PluginConfig config() {
        return config;
    }

    public void config(UnaryOperator<PluginConfig> change) {
        config = change.apply(config);
    }

    public void enableChat() {
        config(c -> new PluginConfig(c.panelUrl(), c.apiKey(), c.authServer(), c.waitingServer(), c.newest(),
                c.seasons(), c.rememberLastServer(), c.prestartOnConnect(), c.startupTimeoutSeconds(),
                c.pollIntervalSeconds(), c.pingTimeoutMs(),
                new PluginConfig.ChatConfig(true, c.chat().format(), c.chat().joinFormat(), c.chat().leaveFormat(),
                        c.chat().excludeServers(), ""),
                c.messages(), c.mapMods()));
    }

    public void prestart(boolean on) {
        config(c -> new PluginConfig(c.panelUrl(), c.apiKey(), c.authServer(), c.waitingServer(), c.newest(),
                c.seasons(), c.rememberLastServer(), on, c.startupTimeoutSeconds(),
                c.pollIntervalSeconds(), c.pingTimeoutMs(), c.chat(), c.messages(), c.mapMods()));
    }

    // ------------------------------------------------------------------ servers

    private void addServer(String name) {
        RegisteredServer s = mock(RegisteredServer.class);
        ServerInfo info = new ServerInfo(name, InetSocketAddress.createUnresolved(name + ".local", 25565));
        when(s.getServerInfo()).thenReturn(info);
        when(s.ping()).thenAnswer(i -> Boolean.TRUE.equals(up.get(name))
                ? CompletableFuture.completedFuture(mock(ServerPing.class))
                : CompletableFuture.failedFuture(new IOException("connection refused (fake)")));
        when(s.getPlayersConnected()).thenAnswer(i -> onServer(name));
        servers.put(name, s);
    }

    public RegisteredServer server(String name) {
        return servers.get(name);
    }

    private static String panelId(String season) {
        return switch (season) {
            case "s3" -> S3_PANEL;
            case "s2" -> S2_PANEL;
            case "s1" -> S1_PANEL;
            default -> throw new IllegalArgumentException(season);
        };
    }

    /** Season fully up: panel running and answering pings. */
    public void running(String season) {
        panel.set(panelId(season), State.RUNNING);
        up.put(season, true);
    }

    /** Season fully off. */
    public void offline(String season) {
        panel.set(panelId(season), State.OFFLINE);
        up.put(season, false);
    }

    public void panelState(String season, State state) {
        panel.set(panelId(season), state);
    }

    public void ping(String season, boolean answers) {
        up.put(season, answers);
    }

    public long startCalls(String season) {
        return panel.count("start:" + panelId(season));
    }

    public long stopCalls(String season) {
        return panel.count("stop:" + panelId(season));
    }

    /** The next {@code n} connects to this server fail like a refused backend connection. */
    public void rejectNextConnects(String server, int n) {
        rejectConnects.put(server, n);
    }

    public Collection<Player> onServer(String name) {
        return players.values().stream().filter(p -> p.active && name.equals(p.server)).map(p -> p.mock).toList();
    }

    // ------------------------------------------------------------------ players

    public FakePlayer player(String name) {
        FakePlayer p = new FakePlayer(name);
        players.put(p.id, p);
        return p;
    }

    /** Login like VeloAuth: limbo, PostLoginEvent, /login, then VeloAuth connects to the try server. */
    public FakePlayer login(String name) {
        FakePlayer p = player(name);
        p.server = "limbo";
        visits.computeIfAbsent(name, k -> new ArrayList<>()).add("limbo");
        router.onPostLogin(new PostLoginEvent(p.mock));
        connect(p, "waiting");
        return p;
    }

    /** Simulates Velocity's connect: ServerPreConnectEvent → (maybe) connected → ServerConnectedEvent. */
    public ConnectionRequestBuilder.Result connect(FakePlayer p, String target) {
        RegisteredServer previous = p.server == null ? null : servers.get(p.server);
        ServerPreConnectEvent event = new ServerPreConnectEvent(p.mock, servers.get(target), previous);
        if (p.authDenied) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied()); // what VeloAuth does before /login
        }
        router.onPreConnect(event);
        if (!event.getResult().isAllowed()) {
            return new Result(ConnectionRequestBuilder.Status.CONNECTION_CANCELLED, servers.get(target));
        }
        RegisteredServer dest = event.getResult().getServer().orElseThrow();
        String destName = dest.getServerInfo().getName();
        if (destName.equals(p.server)) {
            return new Result(ConnectionRequestBuilder.Status.ALREADY_CONNECTED, dest);
        }
        int reject = rejectConnects.getOrDefault(destName, 0);
        if (!Boolean.TRUE.equals(up.get(destName)) || reject > 0) {
            rejectConnects.put(destName, Math.max(0, reject - 1));
            return new Result(ConnectionRequestBuilder.Status.SERVER_DISCONNECTED, dest);
        }
        arrive(p, destName, previous);
        return new Result(ConnectionRequestBuilder.Status.SUCCESS, dest);
    }

    private void arrive(FakePlayer p, String destName, RegisteredServer previous) {
        p.server = destName;
        visits.computeIfAbsent(p.name, k -> new ArrayList<>()).add(destName);
        ServerConnectedEvent connected = new ServerConnectedEvent(p.mock, servers.get(destName), previous);
        router.onServerConnected(connected);
        chat.onServerConnected(connected);
        mapIds.onPostConnect(new com.velocitypowered.api.event.player.ServerPostConnectEvent(p.mock, previous));
    }

    /** Backend kicks the player (crash, stop, ban): Velocity's fallback sends them to the try server. */
    public void kick(FakePlayer p) {
        RegisteredServer from = servers.get(p.server);
        router.onKicked(new KickedFromServerEvent(p.mock, from, Component.text("Server closed"), false,
                KickedFromServerEvent.RedirectPlayer.create(servers.get("waiting"), Component.empty())));
        p.server = null; // Velocity has dropped the backend connection
        RegisteredServer waiting = servers.get("waiting");
        ServerPreConnectEvent event = new ServerPreConnectEvent(p.mock, waiting, from);
        router.onPreConnect(event);
        if (event.getResult().isAllowed()) {
            arrive(p, event.getResult().getServer().orElseThrow().getServerInfo().getName(), from);
        }
    }

    public void disconnect(FakePlayer p) {
        DisconnectEvent event = new DisconnectEvent(p.mock, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN);
        router.onDisconnect(event);
        chat.onDisconnect(event);
        p.active = false;
        p.server = null;
    }

    public void chat(FakePlayer p, String message) {
        chat.onChat(new PlayerChatEvent(p.mock, message));
    }

    record Result(ConnectionRequestBuilder.Status getStatus, RegisteredServer getAttemptedConnection)
            implements ConnectionRequestBuilder.Result {
        @Override
        public Optional<Component> getReasonComponent() {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ FakePlayer

    public final class FakePlayer {
        public final String name;
        public final UUID id = UUID.randomUUID();
        public final Player mock = mock(Player.class);
        public volatile String server;
        public volatile boolean active = true;
        public volatile boolean authDenied;
        public final List<Component> chatLines = Collections.synchronizedList(new ArrayList<>());
        public final List<Component> actionBars = Collections.synchronizedList(new ArrayList<>());
        public int connectRequests;
        /** Plugin messages sent to this client: channel id + payload. */
        public final List<Map.Entry<String, byte[]>> pluginMessages = Collections.synchronizedList(new ArrayList<>());
        public com.velocitypowered.api.network.ProtocolVersion protocol =
                com.velocitypowered.api.network.ProtocolVersion.MAXIMUM_VERSION;

        FakePlayer(String name) {
            this.name = name;
            when(mock.getUniqueId()).thenReturn(id);
            when(mock.getUsername()).thenReturn(name);
            when(mock.isActive()).thenAnswer(i -> active);
            when(mock.getCurrentServer()).thenAnswer(i -> Optional.ofNullable(server).map(this::connection));
            doAnswer(i -> chatLines.add(i.getArgument(0))).when(mock).sendMessage(any(Component.class));
            doAnswer(i -> actionBars.add(i.getArgument(0))).when(mock).sendActionBar(any(Component.class));
            when(mock.createConnectionRequest(any(RegisteredServer.class))).thenAnswer(i -> request(i.getArgument(0)));
            when(mock.getProtocolVersion()).thenAnswer(i -> protocol);
            when(mock.sendPluginMessage(any(com.velocitypowered.api.proxy.messages.ChannelIdentifier.class), any(byte[].class)))
                    .thenAnswer(i -> pluginMessages.add(Map.entry(
                            i.<com.velocitypowered.api.proxy.messages.ChannelIdentifier>getArgument(0).getId(),
                            i.<byte[]>getArgument(1))));
        }

        private ServerConnection connection(String serverName) {
            RegisteredServer rs = servers.get(serverName);
            ServerInfo info = rs.getServerInfo(); // resolve before stubbing starts
            ServerConnection c = mock(ServerConnection.class);
            when(c.getServerInfo()).thenReturn(info);
            when(c.getServer()).thenReturn(rs);
            when(c.getPlayer()).thenReturn(mock);
            return c;
        }

        private ConnectionRequestBuilder request(RegisteredServer target) {
            ConnectionRequestBuilder b = mock(ConnectionRequestBuilder.class);
            String targetName = target.getServerInfo().getName();
            when(b.getServer()).thenReturn(target);
            when(b.connect()).thenAnswer(i -> {
                connectRequests++;
                return CompletableFuture.completedFuture(TestNetwork.this.connect(this, targetName));
            });
            doAnswer(i -> {
                connectRequests++;
                TestNetwork.this.connect(this, targetName);
                return null;
            }).when(b).fireAndForget();
            return b;
        }

        /** Payloads sent on one channel, in order. */
        public List<byte[]> sent(String channel) {
            synchronized (pluginMessages) {
                return pluginMessages.stream().filter(e -> e.getKey().equals(channel)).map(Map.Entry::getValue).toList();
            }
        }

        /** The ServerConnection this player currently has (for building plugin-message events). */
        public ServerConnection connectionNow() {
            return connection(server);
        }

        public List<String> chat() {
            synchronized (chatLines) {
                return chatLines.stream().map(TestNetwork::plain).toList();
            }
        }

        public String lastActionBar() {
            synchronized (actionBars) {
                return actionBars.isEmpty() ? null : plain(actionBars.get(actionBars.size() - 1));
            }
        }

        public List<String> visits() {
            return visits.getOrDefault(name, List.of());
        }

        @Override
        public String toString() {
            return name + "@" + server;
        }
    }

    public static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }
}
