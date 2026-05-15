package pl.maksios.sectorrtp.api;

/**
 * Static accessor for {@link SectorRTPAPI} – useful when the consumer cannot
 * easily go through the Bukkit ServicesManager.
 */
public final class SectorRTPProvider {

    private static SectorRTPAPI instance;

    private SectorRTPProvider() {}

    public static SectorRTPAPI get() {
        if (instance == null) {
            throw new IllegalStateException("SectorRTPAPI is not yet initialized – is SectorRTP enabled and listed as (soft)depend?");
        }
        return instance;
    }

    /** @internal called by SectorRTPPlugin on enable. */
    public static void register(SectorRTPAPI api) {
        instance = api;
    }

    /** @internal called by SectorRTPPlugin on disable. */
    public static void unregister() {
        instance = null;
    }
}
