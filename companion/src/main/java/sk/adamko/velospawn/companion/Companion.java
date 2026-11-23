package sk.adamko.velospawn.companion;

import com.google.gson.JsonObject;
import io.papermc.paper.advancement.AdvancementDisplay;
import io.papermc.paper.text.PaperComponents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameRules;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;

/**
 * Season-server side of AdamkoVeloSpawn. The proxy can't see deaths, advancements or AFK, so this
 * forwards them over a plugin-message channel; the proxy posts them to Discord. Nothing is shown or
 * changed on this server.
 *
 * <p>Wire format (UTF-8 JSON, one message per event):
 * {@code {"v":1,"type":"world|afk|death|advancement","player":"Steve","uuid":"…","world":"world_nether",
 * "env":"NETHER","afk":true,"text":"…"}}. Every message carries the player's current world; "world" is sent
 * on its own when the player arrives and whenever they change worlds. Season stats for /seasons go as
 * {@code {"v":1,"type":"stats",...}} (see {@link SeasonStats}).
 *
 * <p>Paper only sends plugin messages on channels the player's connection has registered. A vanilla client
 * never registers ours, so the proxy registers it on the player's behalf right after each server switch;
 * {@link #onChannelRegistered} is the moment messages can flow.
 */
public final class Companion extends JavaPlugin implements Listener {

    static final String CHANNEL = "adamkovelospawn:events";

    /** Renders vanilla translation keys (death messages, advancement titles) in the server's language. */
    @SuppressWarnings("deprecation")
    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.builder().flattener(PaperComponents.flattener()).build();

    private StatsCollector stats;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        stats = new StatsCollector(this);
        stats.start(getConfig().getLong("stats-interval-minutes", 10));
        getServer().getPluginManager().registerEvents(this, this);
        if (getServer().getPluginManager().getPlugin("Essentials") != null) {
            getServer().getPluginManager().registerEvents(new AfkListener(this), this);
            getLogger().info("Forwarding deaths, advancements and EssentialsX AFK to the proxy");
        } else {
            getLogger().info("Forwarding deaths and advancements to the proxy (EssentialsX not found: no AFK)");
        }
    }

    /** The proxy registered our channel for this player: from now on messages reach it. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChannelRegistered(PlayerRegisterChannelEvent event) {
        if (CHANNEL.equals(event.getChannel())) {
            send(event.getPlayer(), "world", null, null);
            stats.latest().ifPresent(s -> sendJson(event.getPlayer(), s.toJson()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        send(event.getPlayer(), "world", null, null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        Component message = event.deathMessage();
        if (message == null || !Boolean.TRUE.equals(player.getWorld().getGameRuleValue(GameRules.SHOW_DEATH_MESSAGES))) {
            return; // nobody in game sees it either
        }
        send(player, "death", PLAIN.serialize(message), null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        AdvancementDisplay display = event.getAdvancement().getDisplay();
        Player player = event.getPlayer();
        // Only what the game itself announces in chat: no recipes, hidden or silent advancements.
        if (display == null || !display.doesAnnounceToChat() || event.message() == null
                || !Boolean.TRUE.equals(player.getWorld().getGameRuleValue(GameRules.SHOW_ADVANCEMENT_MESSAGES))) {
            return;
        }
        send(player, "advancement", PLAIN.serialize(display.title()), null);
    }

    void send(Player player, String type, String text, Boolean afk) {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("type", type);
        o.addProperty("player", player.getName());
        o.addProperty("uuid", player.getUniqueId().toString());
        o.addProperty("world", player.getWorld().getName());
        o.addProperty("env", player.getWorld().getEnvironment().name());
        if (text != null) {
            o.addProperty("text", text);
        }
        if (afk != null) {
            o.addProperty("afk", afk);
        }
        sendJson(player, o);
    }

    /** Sends one message to the proxy over this player's connection; false if it can't carry it (yet). */
    boolean sendJson(Player player, JsonObject message) {
        if (!player.isOnline() || !player.getListeningPluginChannels().contains(CHANNEL)) {
            return false; // plugin messages travel over the player's own connection, once the proxy registered it
        }
        player.sendPluginMessage(this, CHANNEL, message.toString().getBytes(StandardCharsets.UTF_8));
        return true;
    }
}
