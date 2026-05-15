package pl.maksios.sectorrtp.config;

import lombok.Getter;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.block.Biome;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.configuration.file.FileConfiguration;
import pl.maksios.sectorrtp.SectorRTPPlugin;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strongly-typed snapshot of config.yml.
 *
 * <p>Loaded eagerly on enable / reload so that the hot RTP path never has to
 * touch the underlying {@link FileConfiguration}.</p>
 */
@Getter
public final class PluginConfig {

    public enum SectorSelectionMode { POOL, PATTERN, AUTO }
    public enum CooldownStorage { AUTO, REDIS, MEMORY }

    private final SectorRTPPlugin plugin;

    // bounds (standalone fallback)
    private String fallbackWorld;
    private int minX, maxX, minZ, maxZ;

    // generic
    private long cooldownMillis;
    private CooldownStorage cooldownStorage;
    private int maxAttempts;
    private int sectorMargin;
    private int countdownSeconds;
    private double movementThreshold;

    // sectors
    private SectorSelectionMode sectorMode;
    private List<String> sectorPool;
    private Pattern sectorPattern;
    private Set<String> sectorBlacklist;
    private Set<String> blacklistWorlds;
    private boolean preferCrossSector;
    private boolean requireOnline;

    // cross-sector
    private boolean activateTransferOffset;
    private long pendingLockMs;
    private boolean preserveCoordinates;
    private boolean forceTransfer;

    // safety
    private int minY, maxY;
    private int requiredAirAbove;
    private boolean requireSolidGround;
    private Set<Material> unsafeGroundBlocks;
    private Set<Material> blacklistedOccupyBlocks;
    private Set<Biome> biomeBlacklist;

    // cache
    private boolean cacheEnabled;
    private int cachePerSector;
    private long cacheTtlMillis;

    // effects
    private boolean particlesEnabled;
    private Particle particleType;
    private int particleCount;
    private double particleOffsetX, particleOffsetY, particleOffsetZ;
    private double particleSpeed;

    private boolean preSoundEnabled;
    private Sound preSound;
    private float preSoundVolume, preSoundPitch;

    private boolean postSoundEnabled;
    private Sound postSound;
    private float postSoundVolume, postSoundPitch;

    private boolean failSoundEnabled;
    private Sound failSound;
    private float failSoundVolume, failSoundPitch;

    private boolean bossbarEnabled;
    private BarColor bossbarColor;
    private BarStyle bossbarStyle;

    private boolean titleEnabled;
    private int titleFadeInMs, titleStayMs, titleFadeOutMs;

    // update checker
    private boolean updateCheckerEnabled;
    private String updateRepository;
    private boolean updateNotifyOps;

    // bStats
    private boolean metricsEnabled;
    private int metricsPluginId;

    // debug
    private boolean debug;

    public PluginConfig(SectorRTPPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        FileConfiguration c = plugin.getConfig();

        fallbackWorld = c.getString("bounds.world", "world");
        minX = c.getInt("bounds.minX", -5000);
        maxX = c.getInt("bounds.maxX", 5000);
        minZ = c.getInt("bounds.minZ", -5000);
        maxZ = c.getInt("bounds.maxZ", 5000);

        cooldownMillis = Math.max(0, c.getLong("cooldown", 60)) * 1000L;
        cooldownStorage = enumOrDefault(CooldownStorage.class, c.getString("cooldown-storage", "AUTO"), CooldownStorage.AUTO);
        maxAttempts = Math.max(1, c.getInt("max-attempts", 30));
        sectorMargin = Math.max(0, c.getInt("sector-margin", 32));
        countdownSeconds = Math.max(0, c.getInt("countdown-seconds", 3));
        movementThreshold = c.getDouble("movement-threshold", 0.5D);

        sectorMode = enumOrDefault(SectorSelectionMode.class,
                c.getString("sectors.mode", "POOL"), SectorSelectionMode.POOL);
        sectorPool = c.getStringList("sectors.pool");
        sectorPattern = Pattern.compile(c.getString("sectors.name-pattern", "^sector_\\d+$"));
        sectorBlacklist = Set.copyOf(c.getStringList("sectors.blacklist"));
        blacklistWorlds = Set.copyOf(c.getStringList("sectors.blacklist-worlds"));
        preferCrossSector = c.getBoolean("sectors.prefer-cross-sector", false);
        requireOnline = c.getBoolean("sectors.require-online", true);

        activateTransferOffset = c.getBoolean("cross-sector.activate-transfer-offset", true);
        pendingLockMs = Math.max(0L, c.getLong("cross-sector.pending-lock-ms", 7000));
        preserveCoordinates = c.getBoolean("cross-sector.preserve-coordinates", true);
        forceTransfer = c.getBoolean("cross-sector.force-transfer", true);

        minY = c.getInt("safety.min-y", 40);
        maxY = c.getInt("safety.max-y", 200);
        requiredAirAbove = Math.max(1, c.getInt("safety.required-air-above", 2));
        requireSolidGround = c.getBoolean("safety.require-solid-ground", true);
        unsafeGroundBlocks = materialSet(c.getStringList("safety.unsafe-blocks"));
        blacklistedOccupyBlocks = materialSet(c.getStringList("safety.blacklisted-occupy-blocks"));
        Set<Biome> biomes = new HashSet<>();
        for (String raw : c.getStringList("safety.biome-blacklist")) {
            Biome b = lookupKeyed(Biome.class, raw);
            if (b != null) biomes.add(b);
        }
        biomeBlacklist = Set.copyOf(biomes);

        cacheEnabled = c.getBoolean("cache.enabled", true);
        cachePerSector = Math.max(0, c.getInt("cache.per-sector-size", 32));
        cacheTtlMillis = Math.max(0L, c.getLong("cache.ttl-seconds", 300)) * 1000L;

        particlesEnabled = c.getBoolean("effects.particles.enabled", true);
        particleType = enumOrDefault(Particle.class, c.getString("effects.particles.type", "PORTAL"), Particle.PORTAL);
        particleCount = c.getInt("effects.particles.count", 80);
        particleOffsetX = c.getDouble("effects.particles.offset-x", 0.5);
        particleOffsetY = c.getDouble("effects.particles.offset-y", 1.0);
        particleOffsetZ = c.getDouble("effects.particles.offset-z", 0.5);
        particleSpeed = c.getDouble("effects.particles.speed", 0.1);

        preSoundEnabled = c.getBoolean("effects.sounds.pre-teleport.enabled", true);
        preSound = soundOrDefault(c.getString("effects.sounds.pre-teleport.sound", "ENTITY_ENDERMAN_TELEPORT"), Sound.ENTITY_ENDERMAN_TELEPORT);
        preSoundVolume = (float) c.getDouble("effects.sounds.pre-teleport.volume", 1.0);
        preSoundPitch = (float) c.getDouble("effects.sounds.pre-teleport.pitch", 1.0);

        postSoundEnabled = c.getBoolean("effects.sounds.post-teleport.enabled", true);
        postSound = soundOrDefault(c.getString("effects.sounds.post-teleport.sound", "ENTITY_PLAYER_LEVELUP"), Sound.ENTITY_PLAYER_LEVELUP);
        postSoundVolume = (float) c.getDouble("effects.sounds.post-teleport.volume", 1.0);
        postSoundPitch = (float) c.getDouble("effects.sounds.post-teleport.pitch", 1.0);

        failSoundEnabled = c.getBoolean("effects.sounds.fail.enabled", true);
        failSound = soundOrDefault(c.getString("effects.sounds.fail.sound", "ENTITY_VILLAGER_NO"), Sound.ENTITY_VILLAGER_NO);
        failSoundVolume = (float) c.getDouble("effects.sounds.fail.volume", 1.0);
        failSoundPitch = (float) c.getDouble("effects.sounds.fail.pitch", 1.0);

        bossbarEnabled = c.getBoolean("effects.bossbar.enabled", true);
        bossbarColor = enumOrDefault(BarColor.class, c.getString("effects.bossbar.color", "PURPLE"), BarColor.PURPLE);
        bossbarStyle = parseBarStyle(c.getString("effects.bossbar.overlay", "PROGRESS"));

        titleEnabled = c.getBoolean("effects.title.enabled", true);
        titleFadeInMs = c.getInt("effects.title.fade-in-ms", 250);
        titleStayMs = c.getInt("effects.title.stay-ms", 2000);
        titleFadeOutMs = c.getInt("effects.title.fade-out-ms", 500);

        updateCheckerEnabled = c.getBoolean("update-checker.enabled", true);
        updateRepository = c.getString("update-checker.repository", "maksiosmf/SectorRTP");
        updateNotifyOps = c.getBoolean("update-checker.notify-ops", true);

        metricsEnabled = c.getBoolean("metrics.enabled", true);
        metricsPluginId = c.getInt("metrics.plugin-id", 24001);

        debug = c.getBoolean("debug", false);
    }

    private static Set<Material> materialSet(List<String> raw) {
        Set<Material> out = EnumSet.noneOf(Material.class);
        for (String s : raw) {
            Material m = Material.matchMaterial(s);
            if (m != null) out.add(m);
        }
        return Set.copyOf(out);
    }

    private static BarStyle parseBarStyle(String raw) {
        if (raw == null) return BarStyle.SOLID;
        return switch (raw.toUpperCase(Locale.ROOT)) {
            case "NOTCHED_6" -> BarStyle.SEGMENTED_6;
            case "NOTCHED_10" -> BarStyle.SEGMENTED_10;
            case "NOTCHED_12" -> BarStyle.SEGMENTED_12;
            case "NOTCHED_20" -> BarStyle.SEGMENTED_20;
            default -> BarStyle.SOLID;
        };
    }

    private static <E extends Enum<E>> E enumOrDefault(Class<E> type, String raw, E fallback) {
        if (raw == null) return fallback;
        try { return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { return fallback; }
    }

    /**
     * Sound is no longer a Java enum in Paper 1.21+; it's an interface
     * carrying the legacy constants as {@code public static final} fields.
     * Reflectively reading those fields gives us a deprecation-free lookup
     * that also tolerates new constants being added without code changes.
     */
    private static Sound soundOrDefault(String raw, Sound fallback) {
        if (raw == null || raw.isEmpty()) return fallback;
        Sound matched = lookupKeyed(Sound.class, raw);
        return matched != null ? matched : fallback;
    }

    /**
     * Reflective lookup of a {@code public static final} constant declared on
     * a Bukkit interface (Sound, Biome, …). Both the legacy enum-style name
     * ({@code ENTITY_ENDERMAN_TELEPORT}) and the lower-case form
     * ({@code entity_enderman_teleport}) are accepted.
     */
    @SuppressWarnings("unchecked")
    private static <T> T lookupKeyed(Class<T> type, String raw) {
        if (raw == null) return null;
        String upper = raw.toUpperCase(Locale.ROOT).replace('.', '_').replace(':', '_');
        try {
            Object value = type.getField(upper).get(null);
            return type.isInstance(value) ? (T) value : null;
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
            return null;
        }
    }
}
