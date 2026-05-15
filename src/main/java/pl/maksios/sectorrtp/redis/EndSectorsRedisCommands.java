package pl.maksios.sectorrtp.redis;

import pl.maksios.sectorrtp.SectorRTPPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Reuses the Lettuce connection that EndSectors keeps inside
 * {@code Common.getInstance().getRedisManager()}.
 *
 * <p>Because EndSectors relocates Lettuce to {@code pl.endixon.sectors.shadow.lettuce}
 * we don't reference its classes directly – everything goes through reflection
 * on the {@code RedisManager} public methods.</p>
 *
 * <p>The {@link #ping()} call writes and reads back a sentinel HSET field so a
 * silent failure inside EndSectors' RedisManager (which swallows connection
 * errors) is detected on plugin startup instead of becoming a mysterious
 * "cooldown doesn't carry over" bug at runtime.</p>
 */
public final class EndSectorsRedisCommands implements RedisCommands {

    private static final String PING_KEY = "sectorrtp:ping";

    private final SectorRTPPlugin plugin;

    private Object redisManager;
    private Method mHset;       // hset(String, Map<String, String>)
    private Method mHsetAsync;  // hsetAsync(String, String, String)
    private Method mHget;       // hget(String, String)
    private Method mHdel;       // hdel(String, String...)

    public EndSectorsRedisCommands(SectorRTPPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * @return true when EndSectors' RedisManager is loaded and its sync
     *         commands handle is non-null. False otherwise – caller falls
     *         back to {@link JedisRedisCommands} or memory.
     */
    public boolean tryBind() {
        try {
            Class<?> commonCls = Class.forName("pl.endixon.sectors.common.Common");
            Object common = commonCls.getMethod("getInstance").invoke(null);
            if (common == null) {
                plugin.getLogger().warning("[Redis/EndSectors] Common.getInstance() returned null.");
                return false;
            }

            redisManager = commonCls.getMethod("getRedisManager").invoke(common);
            if (redisManager == null) {
                plugin.getLogger().warning("[Redis/EndSectors] RedisManager is null.");
                return false;
            }

            Class<?> rmCls = redisManager.getClass();
            mHset = rmCls.getMethod("hset", String.class, Map.class);
            mHsetAsync = rmCls.getMethod("hsetAsync", String.class, String.class, String.class);
            mHget = rmCls.getMethod("hget", String.class, String.class);
            mHdel = rmCls.getMethod("hdel", String.class, String[].class);

            // Verify Lettuce sync commands handle is non-null (it stays null if EndSectors' Redis init failed).
            Field syncField = rmCls.getDeclaredField("syncCommands");
            syncField.setAccessible(true);
            Object syncCommands = syncField.get(redisManager);
            if (syncCommands == null) {
                plugin.getLogger().warning("[Redis/EndSectors] syncCommands is null – EndSectors did not connect to Redis (check its config.json).");
                return false;
            }
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("[Redis/EndSectors] reflection failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    @Override
    public boolean ping() {
        try {
            String sentinel = Long.toHexString(System.nanoTime());
            mHset.invoke(redisManager, PING_KEY, Map.of("sentinel", sentinel));
            Object value = mHget.invoke(redisManager, PING_KEY, "sentinel");
            mHdel.invoke(redisManager, PING_KEY, new String[]{"sentinel"});
            return sentinel.equals(value);
        } catch (Throwable t) {
            plugin.getLogger().warning("[Redis/EndSectors] ping failed: " + t.getMessage());
            return false;
        }
    }

    @Override
    public void hsetAsync(String key, String field, String value) {
        try {
            mHsetAsync.invoke(redisManager, key, field, value);
        } catch (Throwable t) {
            plugin.getLogger().warning("[Redis/EndSectors] hsetAsync failed: " + t.getMessage());
        }
    }

    @Override
    public String hget(String key, String field) {
        try {
            Object raw = mHget.invoke(redisManager, key, field);
            return raw == null ? null : raw.toString();
        } catch (Throwable t) {
            plugin.getLogger().warning("[Redis/EndSectors] hget failed: " + t.getMessage());
            return null;
        }
    }

    @Override
    public void hdel(String key, String field) {
        try {
            mHdel.invoke(redisManager, key, new String[]{field});
        } catch (Throwable t) {
            plugin.getLogger().warning("[Redis/EndSectors] hdel failed: " + t.getMessage());
        }
    }

    @Override
    public String describe() {
        return "EndSectors (reflective)";
    }
}
