package pl.maksios.sectorrtp.redis;

import org.bukkit.Bukkit;
import pl.maksios.sectorrtp.SectorRTPPlugin;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisException;

/**
 * Stand-alone Redis backend using a small Jedis pool.
 *
 * <p>Used when EndSectors is missing or its Redis isn't usable. Credentials
 * come from {@code config.yml} under the {@code redis:} section.</p>
 *
 * <p>Writes are dispatched to the Bukkit async scheduler so the /rtp main-
 * thread path never blocks on socket I/O. Reads use the pool directly with
 * the configured timeout – the cooldown gate is on the main thread but the
 * 200 ms LRU in {@link pl.maksios.sectorrtp.managers.RedisCooldownManager}
 * absorbs repeated reads from the same player.</p>
 */
public final class JedisRedisCommands implements RedisCommands {

    private final SectorRTPPlugin plugin;
    private final String host;
    private final int port;
    private final String password;
    private final int database;
    private final int timeoutMs;
    private JedisPool pool;

    public JedisRedisCommands(SectorRTPPlugin plugin,
                              String host, int port, String password,
                              int database, int timeoutMs) {
        this.plugin = plugin;
        this.host = host;
        this.port = port;
        this.password = password == null ? "" : password;
        this.database = database;
        this.timeoutMs = timeoutMs;
    }

    public boolean tryConnect() {
        try {
            JedisPoolConfig cfg = new JedisPoolConfig();
            cfg.setMaxTotal(8);
            cfg.setMaxIdle(4);
            cfg.setMinIdle(1);
            cfg.setTestOnBorrow(false);
            cfg.setTestOnReturn(false);
            cfg.setBlockWhenExhausted(true);

            pool = password.isEmpty()
                    ? new JedisPool(cfg, host, port, timeoutMs, null, database)
                    : new JedisPool(cfg, host, port, timeoutMs, password, database);

            try (Jedis jedis = pool.getResource()) {
                String pong = jedis.ping();
                if (!"PONG".equalsIgnoreCase(pong)) {
                    plugin.getLogger().warning("[Redis/Jedis] PING returned '" + pong + "' instead of PONG.");
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("[Redis/Jedis] connect to " + host + ":" + port + " failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            close();
            return false;
        }
    }

    @Override
    public boolean ping() {
        if (pool == null) return false;
        try (Jedis jedis = pool.getResource()) {
            return "PONG".equalsIgnoreCase(jedis.ping());
        } catch (JedisException ex) {
            return false;
        }
    }

    @Override
    public void hsetAsync(String key, String field, String value) {
        if (pool == null) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Jedis jedis = pool.getResource()) {
                jedis.hset(key, field, value);
            } catch (JedisException ex) {
                plugin.getLogger().warning("[Redis/Jedis] hset failed: " + ex.getMessage());
            }
        });
    }

    @Override
    public String hget(String key, String field) {
        if (pool == null) return null;
        try (Jedis jedis = pool.getResource()) {
            return jedis.hget(key, field);
        } catch (JedisException ex) {
            plugin.getLogger().warning("[Redis/Jedis] hget failed: " + ex.getMessage());
            return null;
        }
    }

    @Override
    public void hdel(String key, String field) {
        if (pool == null) return;
        try (Jedis jedis = pool.getResource()) {
            jedis.hdel(key, field);
        } catch (JedisException ex) {
            plugin.getLogger().warning("[Redis/Jedis] hdel failed: " + ex.getMessage());
        }
    }

    @Override
    public void close() {
        if (pool != null) {
            try { pool.close(); } catch (Throwable ignored) {}
            pool = null;
        }
    }

    @Override
    public String describe() {
        return "Jedis standalone (" + host + ":" + port + " db=" + database + ")";
    }
}
