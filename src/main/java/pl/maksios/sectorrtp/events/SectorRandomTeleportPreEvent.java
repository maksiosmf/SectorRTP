package pl.maksios.sectorrtp.events;

import lombok.Getter;
import lombok.Setter;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Pre-RTP event – cancellable. Fires after sector selection but before the
 * safe location search.
 */
@Getter
public final class SectorRandomTeleportPreEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String sectorName;
    private final boolean forced;
    @Setter private boolean cancelled;

    public SectorRandomTeleportPreEvent(Player player, String sectorName, boolean forced) {
        this.player = player;
        this.sectorName = sectorName;
        this.forced = forced;
    }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }
}
