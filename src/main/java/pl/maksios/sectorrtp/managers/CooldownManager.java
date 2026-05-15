package pl.maksios.sectorrtp.managers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-player cooldowns for /rtp.
 *
 * <p>Cooldowns are stored as the expiry instant (epoch millis) so resets after
 * server reloads automatically expire. Backed by {@link ConcurrentHashMap}
 * because the command can be triggered from any thread (admin /rtp force
 * invoked from async context, plugin API calls, …).</p>
 */
public final class CooldownManager {

    private final Map<UUID, Long> expiries = new ConcurrentHashMap<>();

    public boolean isOnCooldown(UUID id) {
        Long until = expiries.get(id);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            expiries.remove(id, until);
            return false;
        }
        return true;
    }

    public long remainingMillis(UUID id) {
        Long until = expiries.get(id);
        if (until == null) return 0L;
        return Math.max(0L, until - System.currentTimeMillis());
    }

    public void apply(UUID id, long millis) {
        if (millis <= 0L) return;
        expiries.put(id, System.currentTimeMillis() + millis);
    }

    public void reset(UUID id) {
        expiries.remove(id);
    }

    public void clear() {
        expiries.clear();
    }
}
