package pl.maksios.sectorrtp.services;

import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.Title.Times;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import pl.maksios.sectorrtp.SectorRTPPlugin;
import pl.maksios.sectorrtp.config.PluginConfig;

import java.time.Duration;
import java.util.Map;

/**
 * Plays particles, sounds and titles. Centralised so the toggles live in
 * one place — services / commands just say <i>"play preTeleport"</i>.
 */
public final class EffectService {

    private final SectorRTPPlugin plugin;

    public EffectService(SectorRTPPlugin plugin) {
        this.plugin = plugin;
    }

    public void playPreTeleport(Player player) {
        PluginConfig cfg = plugin.getPluginConfig();
        Location loc = player.getLocation();
        if (cfg.isParticlesEnabled()) {
            player.getWorld().spawnParticle(
                    cfg.getParticleType(),
                    loc.clone().add(0, 1, 0),
                    cfg.getParticleCount(),
                    cfg.getParticleOffsetX(),
                    cfg.getParticleOffsetY(),
                    cfg.getParticleOffsetZ(),
                    cfg.getParticleSpeed());
        }
        if (cfg.isPreSoundEnabled()) {
            player.playSound(loc, cfg.getPreSound(), cfg.getPreSoundVolume(), cfg.getPreSoundPitch());
        }
    }

    public void playPostTeleport(Player player, String sectorName) {
        PluginConfig cfg = plugin.getPluginConfig();
        if (cfg.isPostSoundEnabled()) {
            player.playSound(player.getLocation(), cfg.getPostSound(), cfg.getPostSoundVolume(), cfg.getPostSoundPitch());
        }
        if (cfg.isParticlesEnabled()) {
            player.getWorld().spawnParticle(
                    cfg.getParticleType(),
                    player.getLocation().clone().add(0, 1, 0),
                    cfg.getParticleCount(),
                    cfg.getParticleOffsetX(),
                    cfg.getParticleOffsetY(),
                    cfg.getParticleOffsetZ(),
                    cfg.getParticleSpeed());
        }
        if (cfg.isTitleEnabled()) {
            var msg = plugin.getMessagesConfig();
            var ph = Map.of("sector", sectorName == null ? "" : sectorName);
            var title = Title.title(
                    msg.get("title-success", player, ph),
                    msg.get("subtitle-success", player, ph),
                    Times.times(
                            Duration.ofMillis(cfg.getTitleFadeInMs()),
                            Duration.ofMillis(cfg.getTitleStayMs()),
                            Duration.ofMillis(cfg.getTitleFadeOutMs())));
            player.showTitle(title);
        }
    }

    public void playFail(Player player) {
        PluginConfig cfg = plugin.getPluginConfig();
        if (cfg.isFailSoundEnabled()) {
            player.playSound(player.getLocation(), cfg.getFailSound(), cfg.getFailSoundVolume(), cfg.getFailSoundPitch());
        }
    }
}
