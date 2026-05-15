package pl.maksios.sectorrtp.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import pl.maksios.sectorrtp.SectorRTPPlugin;
import pl.maksios.sectorrtp.model.PendingTeleport;

/**
 * Cancels the RTP countdown if the player walks away.
 */
public final class PlayerMoveListener implements Listener {

    private final SectorRTPPlugin plugin;

    public PlayerMoveListener(SectorRTPPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        // Fast-path: only check when the player crossed a block boundary —
        // the same trick Essentials uses to keep the listener cheap.
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        var id = event.getPlayer().getUniqueId();
        PendingTeleport pending = plugin.getPendingTeleportManager().get(id);
        if (pending == null) return;
        if (event.getPlayer().hasPermission("sectorrtp.bypass.movement")) return;

        if (pending.hasMoved(event.getTo(), plugin.getPluginConfig().getMovementThreshold())) {
            plugin.getCountdownService().cancel(id, "movement");
        }
    }
}
