package pl.maksios.sectorrtp.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.bukkit.Location;

/**
 * A pre-computed location that has already passed all safety checks.
 *
 * <p>Stored in {@code SafeChunkCacheManager} so concurrent /rtp calls don't
 * repeat the heavy ground scan.</p>
 */
@Getter
@RequiredArgsConstructor
public final class SafeLocation {

    private final String sectorName;
    private final Location location;
    private final long computedAt;

    public boolean isExpired(long ttlMillis) {
        return ttlMillis > 0 && (System.currentTimeMillis() - computedAt) > ttlMillis;
    }
}
