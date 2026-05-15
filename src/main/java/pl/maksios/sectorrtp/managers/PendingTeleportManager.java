package pl.maksios.sectorrtp.managers;

import pl.maksios.sectorrtp.model.PendingTeleport;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks active countdowns and the post-teleport "cross-sector lock".
 *
 * <p>The cross-sector lock is what protects against the well-known EndSectors
 * fallback bug: when a player is mid-transfer (NATS packet in flight) and
 * issues /rtp again, the source server may still process the request and
 * send the player <i>back</i> to the old sector. We keep a short-lived lock
 * keyed by UUID, refreshed every time a teleport is dispatched.</p>
 */
public final class PendingTeleportManager {

    private final Map<UUID, PendingTeleport> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Long> crossSectorLocks = new ConcurrentHashMap<>();

    // ----------- countdowns -----------

    public boolean hasPending(UUID id) { return pending.containsKey(id); }

    public PendingTeleport get(UUID id) { return pending.get(id); }

    public void register(UUID id, PendingTeleport teleport) { pending.put(id, teleport); }

    public PendingTeleport remove(UUID id) { return pending.remove(id); }

    // ----------- cross-sector locks -----------

    public boolean isLocked(UUID id) {
        Long until = crossSectorLocks.get(id);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            crossSectorLocks.remove(id, until);
            return false;
        }
        return true;
    }

    public void lock(UUID id, long durationMs) {
        if (durationMs <= 0L) return;
        crossSectorLocks.put(id, System.currentTimeMillis() + durationMs);
    }

    public void unlock(UUID id) {
        crossSectorLocks.remove(id);
    }
}
