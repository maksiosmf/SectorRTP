package pl.maksios.sectorrtp.model;

import org.bukkit.World;

/**
 * Adapter-friendly representation of a sector's rectangular footprint.
 *
 * <p>Y is intentionally absent — bounds in EndSectors are 2D rectangles and the
 * safe-Y is derived from the world at lookup time.</p>
 */
public record SectorBounds(
        String name,
        String worldName,
        int minX,
        int maxX,
        int minZ,
        int maxZ,
        boolean online
) {

    public boolean isValid() {
        return minX < maxX && minZ < maxZ;
    }

    public boolean matchesWorld(World world) {
        return world != null && worldName != null && worldName.equals(world.getName());
    }

    public SectorBounds shrunken(int margin) {
        return new SectorBounds(name, worldName,
                minX + margin, maxX - margin,
                minZ + margin, maxZ - margin,
                online);
    }
}
