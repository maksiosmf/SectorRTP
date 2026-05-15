package pl.maksios.sectorrtp.managers;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import pl.maksios.sectorrtp.SectorRTPPlugin;
import pl.maksios.sectorrtp.model.SafeLocation;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sector-keyed FIFO cache of pre-computed safe locations.
 *
 * <p>RTP coordinates are expensive (chunk loads, ground scans). Caching a
 * small pool per sector amortises that cost across many concurrent /rtp
 * calls — a pattern proven on large PvP/survival servers.</p>
 *
 * <p>Entries are evicted on TTL expiry by a periodic janitor task running
 * once per minute.</p>
 */
public final class SafeChunkCacheManager {

    private final SectorRTPPlugin plugin;
    private final Map<String, Deque<SafeLocation>> bySector = new ConcurrentHashMap<>();
    private BukkitTask janitor;

    public SafeChunkCacheManager(SectorRTPPlugin plugin) {
        this.plugin = plugin;
        long period = 20L * 60L;
        this.janitor = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::evictExpired, period, period);
    }

    public SafeLocation poll(String sectorName) {
        if (!plugin.getPluginConfig().isCacheEnabled()) return null;
        Deque<SafeLocation> deque = bySector.get(sectorName);
        if (deque == null) return null;
        synchronized (deque) {
            while (!deque.isEmpty()) {
                SafeLocation candidate = deque.pollFirst();
                if (candidate == null) continue;
                if (!candidate.isExpired(plugin.getPluginConfig().getCacheTtlMillis())) {
                    return candidate;
                }
            }
        }
        return null;
    }

    public void offer(SafeLocation safe) {
        if (!plugin.getPluginConfig().isCacheEnabled()) return;
        int max = plugin.getPluginConfig().getCachePerSector();
        if (max <= 0) return;
        Deque<SafeLocation> deque = bySector.computeIfAbsent(safe.getSectorName(), k -> new ArrayDeque<>());
        synchronized (deque) {
            while (deque.size() >= max) deque.pollLast();
            deque.offerFirst(safe);
        }
    }

    public void invalidateAll() {
        bySector.clear();
    }

    public void shutdown() {
        if (janitor != null) {
            janitor.cancel();
            janitor = null;
        }
        invalidateAll();
    }

    private void evictExpired() {
        long ttl = plugin.getPluginConfig().getCacheTtlMillis();
        if (ttl <= 0L) return;
        for (Deque<SafeLocation> deque : bySector.values()) {
            synchronized (deque) {
                deque.removeIf(s -> s.isExpired(ttl));
            }
        }
    }
}
