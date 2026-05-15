package pl.maksios.sectorrtp.managers;

import pl.maksios.sectorrtp.SectorRTPPlugin;
import pl.maksios.sectorrtp.redis.RedisCommands;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Cross-sector cooldown store backed by Redis.
 *
 * <p>Storage layout:</p>
 * <pre>
 *   HSET &lt;prefix&gt;:cooldowns &lt;uuid&gt; &lt;epoch-millis-expiry&gt;
 * </pre>
 *
 * <p>TTL semantics:</p>
 * <ul>
 *   <li>Each field carries its own expiry timestamp – the value itself.</li>
 *   <li>Expired fields are pruned lazily on read.</li>
 *   <li>A 200 ms in-process LRU absorbs /rtp spam from a single player
 *       without amplifying Redis QPS.</li>
 * </ul>
 *
 * <p>The underlying I/O is delegated to a {@link RedisCommands} backend so
 * the same logic works for the EndSectors-shared connection and for the
 * stand-alone Jedis pool.</p>
 */
public final class RedisCooldownManager implements CooldownManager {

    private static final long READ_CACHE_TTL_MS = 200L;

    private final SectorRTPPlugin plugin;
    private final RedisCommands redis;
    private final String hashKey;
    private final ConcurrentMap<UUID, CachedEntry> readCache = new ConcurrentHashMap<>();

    public RedisCooldownManager(SectorRTPPlugin plugin, RedisCommands redis, String keyPrefix) {
        this.plugin = plugin;
        this.redis = redis;
        this.hashKey = (keyPrefix == null || keyPrefix.isEmpty() ? "sectorrtp" : keyPrefix) + ":cooldowns";
    }

    @Override
    public boolean isOnCooldown(UUID id) {
        long until = readUntilMillis(id);
        if (until <= 0L) return false;
        if (System.currentTimeMillis() >= until) {
            redis.hdel(hashKey, id.toString());
            readCache.remove(id);
            return false;
        }
        return true;
    }

    @Override
    public long remainingMillis(UUID id) {
        long until = readUntilMillis(id);
        return Math.max(0L, until - System.currentTimeMillis());
    }

    @Override
    public void apply(UUID id, long millis) {
        if (millis <= 0L) return;
        long until = System.currentTimeMillis() + millis;
        redis.hsetAsync(hashKey, id.toString(), Long.toString(until));
        readCache.put(id, new CachedEntry(until, System.currentTimeMillis()));
    }

    @Override
    public void reset(UUID id) {
        redis.hdel(hashKey, id.toString());
        readCache.remove(id);
    }

    /**
     * Reads the expiry timestamp for a player, honoring the short-lived read
     * cache so a player's repeated clicks don't slam Redis.
     *
     * @return epoch millis until which the cooldown is active, or 0L.
     */
    private long readUntilMillis(UUID id) {
        long now = System.currentTimeMillis();
        CachedEntry cached = readCache.get(id);
        if (cached != null && (now - cached.fetchedAt) < READ_CACHE_TTL_MS) {
            return cached.until;
        }
        String raw = redis.hget(hashKey, id.toString());
        if (raw == null) {
            readCache.remove(id);
            return 0L;
        }
        try {
            long until = Long.parseLong(raw);
            readCache.put(id, new CachedEntry(until, now));
            return until;
        } catch (NumberFormatException ex) {
            redis.hdel(hashKey, id.toString());
            return 0L;
        }
    }

    private record CachedEntry(long until, long fetchedAt) {}
}
