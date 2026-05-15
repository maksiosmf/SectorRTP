package pl.maksios.sectorrtp.events;

import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired after a successful RTP teleport.
 *
 * <p>For pre-teleport interception, listen to {@link SectorRandomTeleportPreEvent}
 * which is cancellable.</p>
 */
@Getter
public final class SectorRandomTeleportEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String sectorName;
    private final Location destination;
    private final boolean forced;

    public SectorRandomTeleportEvent(Player player, String sectorName, Location destination, boolean forced) {
        this.player = player;
        this.sectorName = sectorName;
        this.destination = destination;
        this.forced = forced;
    }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
