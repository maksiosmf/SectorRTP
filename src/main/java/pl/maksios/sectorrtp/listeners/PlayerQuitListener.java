package pl.maksios.sectorrtp.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import pl.maksios.sectorrtp.SectorRTPPlugin;

/**
 * Cleans up any active countdown / lock when the player disconnects so we
 * don't leak boss bars or block the player from /rtp on rejoin.
 */
public final class PlayerQuitListener implements Listener {

    private final SectorRTPPlugin plugin;

    public PlayerQuitListener(SectorRTPPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        var id = event.getPlayer().getUniqueId();
        plugin.getCountdownService().cancel(id, "quit");
        // Note: we intentionally do NOT clear the cross-sector lock — the
        // player might be in the middle of the NATS transfer and the lock
        // is what prevents the duplicate-teleport bug on the destination.
    }
}
