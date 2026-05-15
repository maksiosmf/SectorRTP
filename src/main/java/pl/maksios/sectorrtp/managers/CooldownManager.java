package pl.maksios.sectorrtp.managers;

import java.util.UUID;

/**
 * Strategy interface for /rtp cooldown storage.
 *
 * <p>Two implementations are provided:</p>
 * <ul>
 *   <li>{@link MemoryCooldownManager} – local {@link java.util.concurrent.ConcurrentHashMap},
 *       cooldowns are <b>not</b> shared between sectors.</li>
 *   <li>{@link RedisCooldownManager} – shared via EndSectors'
 *       {@code Common.getInstance().getRedisManager()} so the same UUID has
 *       the same cooldown on every Paper server in the network.</li>
 * </ul>
 *
 * <p>Use the Redis backend on multi-sector setups: otherwise a player can
 * /rtp on sector_1, get teleported to sector_2 and immediately /rtp again
 * because sector_2 has its own local map.</p>
 */
public interface CooldownManager {

    boolean isOnCooldown(UUID id);

    long remainingMillis(UUID id);

    void apply(UUID id, long millis);

    void reset(UUID id);

    default void clear() {}
}
