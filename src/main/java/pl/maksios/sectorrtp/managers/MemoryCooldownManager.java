package pl.maksios.sectorrtp.managers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local-process cooldown store.
 *
 * <p>Cooldowns are stored as expiry instants (epoch millis) so stale entries
 * expire automatically. Backed by {@link ConcurrentHashMap} because /rtp can
 * be triggered from any thread (admin force, API callers, …).</p>
 */
public final class MemoryCooldownManager implements CooldownManager {

    private final Map<UUID, Long> expiries = new ConcurrentHashMap<>();

    @Override
    public boolean isOnCooldown(UUID id) {
        Long until = expiries.get(id);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            expiries.remove(id, until);
            return false;
        }
        return true;
    }

    @Override
    public long remainingMillis(UUID id) {
        Long until = expiries.get(id);
        if (until == null) return 0L;
        return Math.max(0L, until - System.currentTimeMillis());
    }

    @Override
    public void apply(UUID id, long millis) {
        if (millis <= 0L) return;
        expiries.put(id, System.currentTimeMillis() + millis);
    }

    @Override
    public void reset(UUID id) {
        expiries.remove(id);
    }

    @Override
    public void clear() {
        expiries.clear();
    }
}
