package pl.maksios.sectorrtp;

import lombok.Getter;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import pl.maksios.sectorrtp.api.SectorRTPAPI;
import pl.maksios.sectorrtp.api.SectorRTPProvider;
import pl.maksios.sectorrtp.commands.RtpCommand;
import pl.maksios.sectorrtp.config.MessagesConfig;
import pl.maksios.sectorrtp.config.PluginConfig;
import pl.maksios.sectorrtp.hooks.EndSectorsHook;
import pl.maksios.sectorrtp.hooks.PlaceholderAPIHook;
import pl.maksios.sectorrtp.listeners.PlayerMoveListener;
import pl.maksios.sectorrtp.listeners.PlayerQuitListener;
import pl.maksios.sectorrtp.managers.CooldownManager;
import pl.maksios.sectorrtp.managers.PendingTeleportManager;
import pl.maksios.sectorrtp.managers.SafeChunkCacheManager;
import pl.maksios.sectorrtp.services.CountdownService;
import pl.maksios.sectorrtp.services.EffectService;
import pl.maksios.sectorrtp.services.RtpService;
import pl.maksios.sectorrtp.services.SafeLocationFinder;
import pl.maksios.sectorrtp.utils.UpdateChecker;

/**
 * SectorRTP – RTP system that integrates with the EndSectors plugin.
 *
 * <p>The plugin is split into managers (state), services (logic) and hooks
 * (3rd-party integrations) so each component can be swapped or unit-tested
 * independently.</p>
 */
public final class SectorRTPPlugin extends JavaPlugin {

    @Getter private static SectorRTPPlugin instance;

    @Getter private PluginConfig pluginConfig;
    @Getter private MessagesConfig messagesConfig;

    @Getter private EndSectorsHook endSectorsHook;

    @Getter private CooldownManager cooldownManager;
    @Getter private PendingTeleportManager pendingTeleportManager;
    @Getter private SafeChunkCacheManager safeChunkCacheManager;

    @Getter private SafeLocationFinder safeLocationFinder;
    @Getter private CountdownService countdownService;
    @Getter private EffectService effectService;
    @Getter private RtpService rtpService;

    @Getter private SectorRTPAPI api;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        this.pluginConfig = new PluginConfig(this);
        this.pluginConfig.load();

        this.messagesConfig = new MessagesConfig(this);
        this.messagesConfig.load();

        // ---------- hooks ----------
        this.endSectorsHook = new EndSectorsHook(this);
        this.endSectorsHook.initialize();
        PlaceholderAPIHook.initialize();

        // ---------- managers ----------
        this.cooldownManager = new CooldownManager();
        this.pendingTeleportManager = new PendingTeleportManager();
        this.safeChunkCacheManager = new SafeChunkCacheManager(this);

        // ---------- services ----------
        this.safeLocationFinder = new SafeLocationFinder(this);
        this.effectService = new EffectService(this);
        this.countdownService = new CountdownService(this);
        this.rtpService = new RtpService(this);

        // ---------- listeners ----------
        Bukkit.getPluginManager().registerEvents(new PlayerMoveListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerQuitListener(this), this);

        // ---------- commands ----------
        RtpCommand executor = new RtpCommand(this);
        var cmd = getCommand("rtp");
        if (cmd != null) {
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        // ---------- public API ----------
        this.api = new SectorRTPAPI(this);
        SectorRTPProvider.register(api);
        Bukkit.getServicesManager().register(SectorRTPAPI.class, api, this, org.bukkit.plugin.ServicePriority.Normal);

        // ---------- metrics + update checker ----------
        if (pluginConfig.isMetricsEnabled()) {
            try { new Metrics(this, pluginConfig.getMetricsPluginId()); }
            catch (Throwable t) { getLogger().warning("bStats failed to start: " + t.getMessage()); }
        }
        if (pluginConfig.isUpdateCheckerEnabled()) {
            UpdateChecker.checkAsync(this);
        }

        getLogger().info("SectorRTP enabled" +
                (endSectorsHook.isPresent() ? " (EndSectors detected)" : " (standalone mode)") + ".");
    }

    @Override
    public void onDisable() {
        if (countdownService != null) countdownService.shutdown();
        if (safeChunkCacheManager != null) safeChunkCacheManager.shutdown();
        if (api != null) {
            Bukkit.getServicesManager().unregister(api);
            SectorRTPProvider.unregister();
        }
        instance = null;
        getLogger().info("SectorRTP disabled.");
    }

    /**
     * Reloads config.yml + messages.yml without re-creating manager state.
     */
    public void reload() {
        reloadConfig();
        pluginConfig.load();
        messagesConfig.load();
        safeChunkCacheManager.invalidateAll();
    }
}
