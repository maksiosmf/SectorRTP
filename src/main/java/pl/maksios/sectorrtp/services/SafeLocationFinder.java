package pl.maksios.sectorrtp.services;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import pl.maksios.sectorrtp.SectorRTPPlugin;
import pl.maksios.sectorrtp.config.PluginConfig;
import pl.maksios.sectorrtp.model.SafeLocation;
import pl.maksios.sectorrtp.model.SectorBounds;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Async, retry-aware finder of safe RTP locations.
 *
 * <p>Strategy:</p>
 * <ol>
 *   <li>Generate a random (x, z) inside the sector bounds, minus margin.</li>
 *   <li>Asynchronously load the target chunk via Paper's
 *       {@link World#getChunkAtAsync(int, int)}.</li>
 *   <li>On the main thread, perform a top-down ground scan for the first
 *       valid (solid block + N air above) Y.</li>
 *   <li>Validate against the {@code unsafe-blocks} and
 *       {@code blacklisted-occupy-blocks} configuration.</li>
 *   <li>If validation fails, recurse up to {@code max-attempts} times.</li>
 * </ol>
 *
 * <p>The whole chain returns a {@link CompletableFuture} so callers can
 * progress an actionbar / boss bar without freezing the main thread.</p>
 */
public final class SafeLocationFinder {

    private final SectorRTPPlugin plugin;

    public SafeLocationFinder(SectorRTPPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * @param attemptCallback notified after each failed attempt (UI updates).
     * @return future with the first safe location, or completed exceptionally
     *         with {@link SafeLocationFailure} if {@code max-attempts} exhausted.
     */
    public CompletableFuture<SafeLocation> findSafeLocation(SectorBounds bounds, java.util.function.IntConsumer attemptCallback) {
        // Cache hit short-circuits the whole pipeline.
        SafeLocation cached = plugin.getSafeChunkCacheManager().poll(bounds.name());
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        CompletableFuture<SafeLocation> future = new CompletableFuture<>();
        attempt(bounds, 0, attemptCallback, future);
        return future;
    }

    private void attempt(SectorBounds bounds, int attemptCount,
                         java.util.function.IntConsumer attemptCallback,
                         CompletableFuture<SafeLocation> future) {

        PluginConfig cfg = plugin.getPluginConfig();
        if (attemptCount >= cfg.getMaxAttempts()) {
            future.completeExceptionally(new SafeLocationFailure(
                    "max-attempts exceeded (" + cfg.getMaxAttempts() + ")", attemptCount));
            return;
        }

        attemptCallback.accept(attemptCount + 1);

        World world = Bukkit.getWorld(bounds.worldName());
        if (world == null) {
            future.completeExceptionally(new SafeLocationFailure(
                    "world '" + bounds.worldName() + "' not loaded on this server", attemptCount));
            return;
        }

        SectorBounds shrunken = bounds.shrunken(cfg.getSectorMargin());
        if (!shrunken.isValid()) {
            future.completeExceptionally(new SafeLocationFailure(
                    "sector '" + bounds.name() + "' is too small for the configured margin", attemptCount));
            return;
        }

        int x = ThreadLocalRandom.current().nextInt(shrunken.minX(), shrunken.maxX() + 1);
        int z = ThreadLocalRandom.current().nextInt(shrunken.minZ(), shrunken.maxZ() + 1);

        int chunkX = x >> 4;
        int chunkZ = z >> 4;

        // PaperMC API: returns CompletableFuture<Chunk>, loaded off-main when needed.
        world.getChunkAtAsync(chunkX, chunkZ, true).thenAccept(chunk -> {
            // The result handler will execute on the main thread for safety.
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    SafeLocation safe = evaluate(world, chunk, bounds, x, z, cfg);
                    if (safe != null) {
                        plugin.getSafeChunkCacheManager().offer(safe);
                        future.complete(safe);
                    } else {
                        attempt(bounds, attemptCount + 1, attemptCallback, future);
                    }
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        }).exceptionally(ex -> {
            // Chunk failed to load – treat as failed attempt and retry.
            Bukkit.getScheduler().runTask(plugin,
                    () -> attempt(bounds, attemptCount + 1, attemptCallback, future));
            return null;
        });
    }

    private SafeLocation evaluate(World world, Chunk chunk, SectorBounds bounds, int x, int z, PluginConfig cfg) {
        int y = world.getHighestBlockYAt(x, z);
        int minY = Math.max(cfg.getMinY(), world.getMinHeight());
        int maxY = Math.min(cfg.getMaxY(), world.getMaxHeight() - cfg.getRequiredAirAbove() - 1);

        if (y < minY || y > maxY) return null;

        Block ground = world.getBlockAt(x, y, z);
        if (cfg.isRequireSolidGround() && !ground.getType().isSolid()) return null;

        Set<Material> unsafe = cfg.getUnsafeGroundBlocks();
        if (unsafe.contains(ground.getType())) return null;

        // Waterlogged blocks count as water for safety purposes.
        BlockData groundData = ground.getBlockData();
        if (groundData instanceof Waterlogged wl && wl.isWaterlogged()) return null;

        // Check N empty blocks above the ground block.
        Set<Material> blockedOccupy = cfg.getBlacklistedOccupyBlocks();
        for (int i = 1; i <= cfg.getRequiredAirAbove(); i++) {
            Block above = world.getBlockAt(x, y + i, z);
            if (!above.isPassable() && above.getType() != Material.AIR) return null;
            if (blockedOccupy.contains(above.getType())) return null;
        }

        // Biome blacklist
        if (!cfg.getBiomeBlacklist().isEmpty()
                && cfg.getBiomeBlacklist().contains(world.getBlockAt(x, y, z).getBiome())) {
            return null;
        }

        // Place feet on top of the ground block (so y+1 is the player's lower half).
        Location loc = new Location(world,
                x + 0.5,
                y + 1,
                z + 0.5,
                ThreadLocalRandom.current().nextFloat() * 360f - 180f,
                0f);

        // Ensure the safe Y respects min-y (the highest block could be e.g. void at y=63 = below water level if min-y=64).
        if (loc.getBlockY() < cfg.getMinY()) return null;

        return new SafeLocation(bounds.name(), loc, System.currentTimeMillis());
    }

    /** Thrown via the future when no safe spot could be found. */
    public static final class SafeLocationFailure extends RuntimeException {
        private final int attempts;
        public SafeLocationFailure(String message, int attempts) {
            super(message);
            this.attempts = attempts;
        }
        public int getAttempts() { return attempts; }
    }
}
