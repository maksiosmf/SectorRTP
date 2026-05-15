package pl.maksios.sectorrtp.api;

import org.bukkit.entity.Player;
import pl.maksios.sectorrtp.SectorRTPPlugin;
import pl.maksios.sectorrtp.model.SectorBounds;
import pl.maksios.sectorrtp.services.RtpService;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Public API surface for third-party plugins.
 *
 * <p>Usage:</p>
 * <pre>{@code
 *   SectorRTPAPI api = SectorRTPProvider.get();
 *   api.requestRtp(player).thenAccept(result -> {
 *       if (result.success()) {
 *           player.sendMessage("Teleported!");
 *       }
 *   });
 * }</pre>
 *
 * <p>The API is also registered as a Bukkit service:</p>
 * <pre>{@code
 *   var reg = Bukkit.getServicesManager().getRegistration(SectorRTPAPI.class);
 *   if (reg != null) reg.getProvider().requestRtp(player);
 * }</pre>
 */
public final class SectorRTPAPI {

    private final SectorRTPPlugin plugin;

    public SectorRTPAPI(SectorRTPPlugin plugin) {
        this.plugin = plugin;
    }

    /** Trigger /rtp for the given player. Honors cooldown / permission gates. */
    public CompletableFuture<RtpService.RtpResult> requestRtp(Player player) {
        return plugin.getRtpService().requestRtp(player, false);
    }

    /** Trigger /rtp ignoring cooldown, locks and pending state (admin / event use). */
    public CompletableFuture<RtpService.RtpResult> forceRtp(Player player) {
        return plugin.getRtpService().requestRtp(player, true);
    }

    /** @return immutable snapshot of currently eligible sectors. */
    public List<SectorBounds> getEligibleSectors() {
        return plugin.getEndSectorsHook().getEligibleSectors();
    }

    /** @return the sector this server currently is, as reported by EndSectors. */
    public Optional<String> getCurrentSectorName() {
        return plugin.getEndSectorsHook().getCurrentSectorName();
    }

    public boolean isEndSectorsAvailable() {
        return plugin.getEndSectorsHook().isPresent();
    }

    /** Cooldown remaining in milliseconds, 0 if none. */
    public long getCooldownRemaining(Player player) {
        return plugin.getCooldownManager().remainingMillis(player.getUniqueId());
    }

    /** Manually reset a player's RTP cooldown. */
    public void resetCooldown(Player player) {
        plugin.getCooldownManager().reset(player.getUniqueId());
    }
}
