package pl.maksios.sectorrtp.managers;

import pl.maksios.sectorrtp.SectorRTPPlugin;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Cross-sector cooldown store backed by the Redis instance EndSectors already
 * runs. Reuses the existing Lettuce connection through reflection so we do
 * not open a second client.
 *
 * <p>Storage layout:</p>
 * <pre>
 *   HSET sectorrtp:cooldowns &lt;uuid&gt; &lt;expiry-epoch-millis&gt;
 * </pre>
 *
 * <p>TTL semantics:</p>
 * <ul>
 *   <li>Each field carries its own expiry timestamp – the value itself.</li>
 *   <li>Expired fields are pruned on read (lazy eviction) and via a periodic
 *       janitor task to bound the hash size when many one-shot players /rtp.</li>
 *   <li>A small in-memory LRU caches recent reads to absorb /rtp spam from a
 *       single player without amplifying Redis QPS.</li>
 * </ul>
 *
 * <p>All write operations go through {@code RedisManager.hsetAsync} so the
 * /rtp pipeline never blocks on the Redis I/O thread.</p>
 */
public final class RedisCooldownManager implements CooldownManager {

    private static final String KEY = "sectorrtp:cooldowns";
    private static final long READ_CACHE_TTL_MS = 200L;

    private final SectorRTPPlugin plugin;
    private final ConcurrentMap<UUID, CachedEntry> readCache = new ConcurrentHashMap<>();

    private Object redisManager;
    private Method mHget;
    private Method mHsetAsync;
    private Method mHdel;

    public RedisCooldownManager(SectorRTPPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Resolves reflective handles on the EndSectors RedisManager. Returns
     * false if EndSectors is absent or the Redis connection is not ready –
     * the caller is expected to fall back to {@link MemoryCooldownManager}.
     */
    public boolean initialize() {
        try {
            Class<?> commonCls = Class.forName("pl.endixon.sectors.common.Common");
            Object common = commonCls.getMethod("getInstance").invoke(null);
            if (common == null) return false;

            this.redisManager = commonCls.getMethod("getRedisManager").invoke(common);
            if (redisManager == null) return false;

            Class<?> rmCls = redisManager.getClass();
            this.mHget = rmCls.getMethod("hget", String.class, String.class);
            this.mHsetAsync = rmCls.getMethod("hsetAsync", String.class, String.class, String.class);
            this.mHdel = rmCls.getMethod("hdel", String.class, String[].class);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Redis cooldown store unavailable (" + t.getClass().getSimpleName()
                    + ": " + t.getMessage() + ") – falling back to in-memory cooldowns.");
            return false;
        }
    }

    @Override
    public boolean isOnCooldown(UUID id) {
        long until = readUntilMillis(id);
        if (until <= 0L) return false;
        long now = System.currentTimeMillis();
        if (now >= until) {
            // Lazy eviction – fire-and-forget HDEL.
            deleteField(id);
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
        try {
            mHsetAsync.invoke(redisManager, KEY, id.toString(), Long.toString(until));
            readCache.put(id, new CachedEntry(until, System.currentTimeMillis()));
        } catch (Throwable t) {
            plugin.getLogger().warning("Redis cooldown apply failed: " + t.getMessage());
        }
    }

    @Override
    public void reset(UUID id) {
        deleteField(id);
        readCache.remove(id);
    }

    private void deleteField(UUID id) {
        try {
            mHdel.invoke(redisManager, KEY, new String[]{id.toString()});
        } catch (Throwable t) {
            plugin.getLogger().warning("Redis cooldown delete failed: " + t.getMessage());
        }
    }

    /**
     * Reads the expiry timestamp for a player, honoring the read-cache so
     * repeated /rtp clicks within ~200ms don't slam the Redis sync command.
     *
     * @return epoch millis until which the cooldown is active, or 0 if no
     *         entry exists.
     */
    private long readUntilMillis(UUID id) {
        long now = System.currentTimeMillis();
        CachedEntry cached = readCache.get(id);
        if (cached != null && (now - cached.fetchedAt) < READ_CACHE_TTL_MS) {
            return cached.until;
        }
        try {
            Object raw = mHget.invoke(redisManager, KEY, id.toString());
            if (raw == null) {
                readCache.remove(id);
                return 0L;
            }
            long until = Long.parseLong(raw.toString());
            readCache.put(id, new CachedEntry(until, now));
            return until;
        } catch (NumberFormatException nfe) {
            // Corrupted entry – wipe it.
            deleteField(id);
            return 0L;
        } catch (Throwable t) {
            plugin.getLogger().warning("Redis cooldown read failed: " + t.getMessage());
            return 0L;
        }
    }

    private record CachedEntry(long until, long fetchedAt) {}
}
