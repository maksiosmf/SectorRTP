package pl.maksios.sectorrtp.redis;

/**
 * Minimal abstraction over the Redis commands the plugin actually uses.
 *
 * <p>Two implementations exist:</p>
 * <ul>
 *   <li>{@link EndSectorsRedisCommands} – reflectively piggybacks on the
 *       Lettuce connection that EndSectors already owns. Zero extra sockets.</li>
 *   <li>{@link JedisRedisCommands} – opens SectorRTP's own Jedis pool with
 *       credentials from {@code config.yml}. Used when EndSectors is not
 *       installed or its Redis isn't actually working.</li>
 * </ul>
 *
 * <p>Every method must be safe to call from the main server thread – writes
 * are fire-and-forget, reads cap at the configured connect timeout.</p>
 */
public interface RedisCommands {

    /** @return true if a round-trip to Redis succeeded. */
    boolean ping();

    /** Fire-and-forget HSET. */
    void hsetAsync(String key, String field, String value);

    /** Blocking HGET (best effort – returns null on any failure). */
    String hget(String key, String field);

    /** Blocking HDEL of a single field. */
    void hdel(String key, String field);

    /** Closes any underlying resources. */
    default void close() {}

    /** Human-readable name shown in logs. */
    String describe();
}
