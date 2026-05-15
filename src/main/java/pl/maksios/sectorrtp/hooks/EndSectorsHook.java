package pl.maksios.sectorrtp.hooks;

import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import pl.maksios.sectorrtp.SectorRTPPlugin;
import pl.maksios.sectorrtp.config.PluginConfig;
import pl.maksios.sectorrtp.model.SectorBounds;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Adapter around the EndSectors plugin (pl.endixon.sectors.paper.*).
 *
 * <p>The EndSectors plugin exposes a public {@code SectorsAPI} singleton but
 * we still use reflection so that:</p>
 *
 * <ul>
 *   <li>the plugin keeps compiling even when EndSectors is not in {@code libs/};</li>
 *   <li>minor API drift (renamed methods, additional parameters) only breaks the
 *       hook, never the whole plugin;</li>
 *   <li>SectorRTP can run as a standalone RTP plugin if EndSectors is absent.</li>
 * </ul>
 *
 * <p><b>Fallback hardening:</b> when the chosen target sector lives on a
 * different physical server, EndSectors transports the player via NATS. The
 * source server can briefly believe the player is still local — if we don't
 * persist the new coordinates first, the destination server will re-spawn the
 * player at the last known location (the "fallback" bug). This adapter takes
 * care of that flow: it writes the random location to the player's
 * {@code UserProfile} via {@code setLocationAndSave}, activates the
 * {@code transferOffsetUntil} guard so the destination server nudges the
 * player away from the spawn block, and finally fires
 * {@code teleportToSector(..., preserveCoordinates=true)} so the saved random
 * location is used on the destination server instead of the old one.</p>
 */
@Getter
public final class EndSectorsHook {

    private static final String PLUGIN_NAME = "EndSectors";
    private static final String API_CLASS = "pl.endixon.sectors.paper.SectorsAPI";
    private static final String SECTOR_CLASS = "pl.endixon.sectors.paper.sector.Sector";
    private static final String SECTOR_TYPE_CLASS = "pl.endixon.sectors.common.sector.SectorType";

    private final SectorRTPPlugin plugin;
    private boolean present;

    // Cached reflective handles. Resolved once on enable.
    private Object apiInstance;
    private Method mGetSectorManager;
    private Method mGetSectors;
    private Method mGetUser;
    private Method mGetCurrentSector;
    private Method mTeleportPlayer;

    private Method mSectorGetName;
    private Method mSectorGetWorldName;
    private Method mSectorGetType;
    private Method mSectorIsOnline;
    private Method mSectorGetFirstCorner;
    private Method mSectorGetSecondCorner;
    private Method mCornerGetX;
    private Method mCornerGetZ;

    private Method mProfileSetLocationAndSave;
    private Method mProfileActivateTransferOffset;

    private Class<?> sectorTypeClass;

    public EndSectorsHook(SectorRTPPlugin plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        if (Bukkit.getPluginManager().getPlugin(PLUGIN_NAME) == null) {
            present = false;
            plugin.getLogger().info("EndSectors plugin not found – running in standalone mode.");
            return;
        }

        try {
            Class<?> apiClass = Class.forName(API_CLASS);
            apiInstance = apiClass.getMethod("getInstance").invoke(null);
            if (apiInstance == null) {
                plugin.getLogger().warning("SectorsAPI.getInstance() returned null – EndSectors not fully initialized.");
                present = false;
                return;
            }

            mGetSectorManager = apiClass.getMethod("getSectorManager");
            mGetUser = apiClass.getMethod("getUser", Player.class);
            mTeleportPlayer = apiClass.getMethod("teleportPlayer",
                    Player.class,
                    Class.forName("pl.endixon.sectors.paper.user.profile.UserProfile"),
                    Class.forName(SECTOR_CLASS),
                    boolean.class,
                    boolean.class);

            Object sectorManager = mGetSectorManager.invoke(apiInstance);
            Class<?> smClass = sectorManager.getClass();
            mGetSectors = smClass.getMethod("getSectors");
            mGetCurrentSector = smClass.getMethod("getCurrentSector");

            Class<?> sectorClass = Class.forName(SECTOR_CLASS);
            mSectorGetName = sectorClass.getMethod("getName");
            mSectorGetWorldName = sectorClass.getMethod("getWorldName");
            mSectorGetType = sectorClass.getMethod("getType");
            mSectorIsOnline = sectorClass.getMethod("isOnline");
            mSectorGetFirstCorner = sectorClass.getMethod("getFirstCorner");
            mSectorGetSecondCorner = sectorClass.getMethod("getSecondCorner");

            Class<?> cornerClass = Class.forName("pl.endixon.sectors.common.util.Corner");
            mCornerGetX = cornerClass.getMethod("getPosX");
            mCornerGetZ = cornerClass.getMethod("getPosZ");

            Class<?> userProfileClass = Class.forName("pl.endixon.sectors.paper.user.profile.UserProfile");
            mProfileSetLocationAndSave = userProfileClass.getMethod("setLocationAndSave", Location.class);
            mProfileActivateTransferOffset = userProfileClass.getMethod("activateTransferOffset");

            sectorTypeClass = Class.forName(SECTOR_TYPE_CLASS);
            present = true;
            plugin.getLogger().info("EndSectors hook initialized successfully.");
        } catch (ReflectiveOperationException ex) {
            present = false;
            plugin.getLogger().warning("EndSectors hook failed to initialize (" + ex.getMessage()
                    + ") – falling back to standalone mode.");
        }
    }

    public boolean isPresent() {
        return present;
    }

    // -----------------------------------------------------------------
    //  Public adapter API
    // -----------------------------------------------------------------

    /**
     * @return all online, non-blacklisted sectors eligible for /rtp,
     *         based on the configured selection mode.
     */
    public List<SectorBounds> getEligibleSectors() {
        PluginConfig cfg = plugin.getPluginConfig();
        if (!present) {
            return standaloneFallback(cfg);
        }
        try {
            @SuppressWarnings("unchecked")
            Collection<Object> sectors = (Collection<Object>) mGetSectors.invoke(getSectorManagerHandle());
            if (sectors == null || sectors.isEmpty()) return Collections.emptyList();

            List<SectorBounds> out = new ArrayList<>();
            for (Object sector : sectors) {
                SectorBounds b = toBounds(sector);
                if (b == null) continue;
                if (!isEligible(b, sector, cfg)) continue;
                out.add(b);
            }
            return out;
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("EndSectors getEligibleSectors() reflection failure: " + ex.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Random pick following the configured strategy.
     *
     * <p>Implements the requirement: "First randomize a sector (1-4), THEN
     * fetch its bounds and only then randomize coordinates" – this method
     * does the first step.</p>
     */
    public Optional<SectorBounds> pickRandomSector() {
        List<SectorBounds> candidates = getEligibleSectors();
        if (candidates.isEmpty()) return Optional.empty();
        return Optional.of(candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())));
    }

    /**
     * Looks up the sector the player is currently standing on (according to
     * EndSectors). Used to enforce {@code prefer-cross-sector}.
     */
    public Optional<String> getCurrentSectorName() {
        if (!present) return Optional.empty();
        try {
            Object current = mGetCurrentSector.invoke(getSectorManagerHandle());
            if (current == null) return Optional.empty();
            return Optional.ofNullable((String) mSectorGetName.invoke(current));
        } catch (ReflectiveOperationException ex) {
            return Optional.empty();
        }
    }

    /**
     * Persist coordinates and optionally activate the transfer offset on the
     * EndSectors UserProfile, then trigger the cross-sector teleport packet.
     *
     * <p>This is the method that protects against the "fallback to previous
     * sector" race condition.</p>
     */
    public boolean dispatchCrossSectorTeleport(Player player, Location safeLocation, String sectorName) {
        if (!present) return false;
        try {
            Object userOpt = mGetUser.invoke(apiInstance, player);
            if (userOpt == null) return false;
            Method isPresent = userOpt.getClass().getMethod("isPresent");
            if (!(boolean) isPresent.invoke(userOpt)) return false;
            Object userProfile = userOpt.getClass().getMethod("get").invoke(userOpt);

            // 1) write the destination location BEFORE the teleport packet.
            //    The destination server reads this from Redis on join.
            mProfileSetLocationAndSave.invoke(userProfile, safeLocation);

            // 2) activate the transfer offset – nudges the player a few blocks
            //    forward on the destination server, so portal/back-tp loops
            //    don't drop them on the spawn block of the previous sector.
            if (plugin.getPluginConfig().isActivateTransferOffset()) {
                mProfileActivateTransferOffset.invoke(userProfile);
            }

            // 3) find the live Sector object for the target.
            Object targetSector = resolveSectorByName(sectorName);
            if (targetSector == null) {
                plugin.getLogger().warning("Cross-sector teleport: target sector '" + sectorName + "' is not loaded.");
                return false;
            }

            // 4) call EndSectors' SectorTeleport via the public API.
            mTeleportPlayer.invoke(apiInstance,
                    player,
                    userProfile,
                    targetSector,
                    plugin.getPluginConfig().isForceTransfer(),
                    plugin.getPluginConfig().isPreserveCoordinates());
            return true;
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().warning("EndSectors dispatchCrossSectorTeleport failure: " + ex.getMessage());
            return false;
        }
    }

    // -----------------------------------------------------------------
    //  Internals
    // -----------------------------------------------------------------

    private Object getSectorManagerHandle() throws ReflectiveOperationException {
        return mGetSectorManager.invoke(apiInstance);
    }

    private Object resolveSectorByName(String name) throws ReflectiveOperationException {
        @SuppressWarnings("unchecked")
        Collection<Object> sectors = (Collection<Object>) mGetSectors.invoke(getSectorManagerHandle());
        if (sectors == null) return null;
        for (Object s : sectors) {
            if (name.equals(mSectorGetName.invoke(s))) return s;
        }
        return null;
    }

    private SectorBounds toBounds(Object sector) throws ReflectiveOperationException {
        String name = (String) mSectorGetName.invoke(sector);
        String world = (String) mSectorGetWorldName.invoke(sector);
        Object c1 = mSectorGetFirstCorner.invoke(sector);
        Object c2 = mSectorGetSecondCorner.invoke(sector);
        if (c1 == null || c2 == null) return null;

        int x1 = (int) mCornerGetX.invoke(c1);
        int x2 = (int) mCornerGetX.invoke(c2);
        int z1 = (int) mCornerGetZ.invoke(c1);
        int z2 = (int) mCornerGetZ.invoke(c2);
        boolean online = (boolean) mSectorIsOnline.invoke(sector);

        return new SectorBounds(name, world,
                Math.min(x1, x2), Math.max(x1, x2),
                Math.min(z1, z2), Math.max(z1, z2),
                online);
    }

    private boolean isEligible(SectorBounds bounds, Object sector, PluginConfig cfg) throws ReflectiveOperationException {
        if (!bounds.isValid()) return false;
        if (cfg.isRequireOnline() && !bounds.online()) return false;
        if (cfg.getBlacklistWorlds().contains(bounds.worldName())) return false;
        if (cfg.getSectorBlacklist().contains(bounds.name())) return false;

        // Exclude infrastructure-only sector types.
        Object type = mSectorGetType.invoke(sector);
        if (type != null) {
            String typeName = ((Enum<?>) type).name();
            if (typeName.equals("QUEUE") || typeName.equals("AFK") || typeName.equals("NETHER") || typeName.equals("END")) {
                return false;
            }
            // SPAWN sectors are excluded by default – /rtp should send players to survival sectors.
            if (typeName.equals("SPAWN")) return false;
        }

        return switch (cfg.getSectorMode()) {
            case POOL -> cfg.getSectorPool().contains(bounds.name());
            case PATTERN -> {
                Pattern p = cfg.getSectorPattern();
                yield p == null || p.matcher(bounds.name()).matches();
            }
            case AUTO -> true;
        };
    }

    private List<SectorBounds> standaloneFallback(PluginConfig cfg) {
        return List.of(new SectorBounds(
                "fallback",
                cfg.getFallbackWorld(),
                cfg.getMinX(),
                cfg.getMaxX(),
                cfg.getMinZ(),
                cfg.getMaxZ(),
                true));
    }
}
