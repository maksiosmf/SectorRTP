package pl.maksios.sectorrtp.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.bukkit.Location;
import org.bukkit.boss.BossBar;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

/**
 * State carried during the pre-teleport countdown.
 */
@Getter
@RequiredArgsConstructor
public final class PendingTeleport {

    private final UUID playerId;
    private final Location originLocation;
    private final long startedAt;
    private final BukkitTask countdownTask;
    private final BossBar bossBar;

    /**
     * @return true if the player has moved more than {@code threshold}
     * blocks (horizontally) from the location at which they issued /rtp.
     */
    public boolean hasMoved(Location current, double threshold) {
        if (current == null || originLocation == null) return false;
        if (!current.getWorld().equals(originLocation.getWorld())) return true;
        double dx = current.getX() - originLocation.getX();
        double dz = current.getZ() - originLocation.getZ();
        return (dx * dx + dz * dz) > (threshold * threshold);
    }
}
