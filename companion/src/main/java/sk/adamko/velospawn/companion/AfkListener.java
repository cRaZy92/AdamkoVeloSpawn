package sk.adamko.velospawn.companion;

import net.ess3.api.events.AfkStatusChangeEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Kept in its own class so the plugin loads fine without EssentialsX (this class is only touched if it's there). */
final class AfkListener implements Listener {

    private final Companion plugin;

    AfkListener(Companion plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAfk(AfkStatusChangeEvent event) {
        // Joining and quitting reset AFK silently in game too; the proxy already posts join/leave.
        if (event.getCause() == AfkStatusChangeEvent.Cause.JOIN || event.getCause() == AfkStatusChangeEvent.Cause.QUIT) {
            return;
        }
        if (event.getAffected().isVanished() || event.getAffected().isHidden()) {
            return;
        }
        Player player = event.getAffected().getBase();
        if (player == null) {
            return;
        }
        plugin.send(player, "afk", null, event.getValue());
    }
}
