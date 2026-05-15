package pl.maksios.sectorrtp.services;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import pl.maksios.sectorrtp.SectorRTPPlugin;
import pl.maksios.sectorrtp.config.PluginConfig;
import pl.maksios.sectorrtp.model.PendingTeleport;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pre-teleport countdown manager.
 *
 * <p>Uses Bukkit BossBars for compatibility (sent on the main thread) and
 * fires an actionbar each second through Adventure. If the player moves
 * beyond the configured threshold, the countdown is cancelled via the
 * {@link pl.maksios.sectorrtp.listeners.PlayerMoveListener}.</p>
 */
public final class CountdownService {

    private final SectorRTPPlugin plugin;
    private final Map<UUID, org.bukkit.boss.BossBar> bukkitBars = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> futures = new ConcurrentHashMap<>();

    public CountdownService(SectorRTPPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Runs the countdown then completes the returned future.
     *
     * <p>If the countdown is cancelled (movement, quit, force-bypass), the
     * future completes exceptionally with a {@link CountdownCancelledException}.</p>
     */
    public CompletableFuture<Void> start(Player player) {
        PluginConfig cfg = plugin.getPluginConfig();
        CompletableFuture<Void> future = new CompletableFuture<>();
        UUID id = player.getUniqueId();

        int seconds = cfg.getCountdownSeconds();
        if (seconds <= 0 || player.hasPermission("sectorrtp.bypass.movement")) {
            future.complete(null);
            return future;
        }

        org.bukkit.boss.BossBar bar = cfg.isBossbarEnabled() ? createBar(cfg) : null;
        if (bar != null) {
            bar.addPlayer(player);
            bukkitBars.put(id, bar);
        }

        AtomicInteger remaining = new AtomicInteger(seconds);
        BukkitTask[] taskRef = new BukkitTask[1];

        Runnable tick = () -> {
            int left = remaining.getAndDecrement();
            if (left <= 0) {
                taskRef[0].cancel();
                plugin.getPendingTeleportManager().remove(id);
                cleanup(id);
                futures.remove(id);
                future.complete(null);
                return;
            }

            // Actionbar tick.
            Component actionbar = plugin.getMessagesConfig()
                    .get("countdown-actionbar", player, Map.of("time", String.valueOf(left)));
            player.sendActionBar(actionbar);

            // Update boss bar progress.
            if (bar != null) {
                double progress = (double) left / Math.max(1, seconds);
                bar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
                bar.setTitle("§5Teleport §7» §f" + left + "s");
            }
        };

        taskRef[0] = Bukkit.getScheduler().runTaskTimer(plugin, tick, 0L, 20L);

        PendingTeleport pending = new PendingTeleport(
                id,
                player.getLocation(),
                System.currentTimeMillis(),
                taskRef[0],
                bar);
        plugin.getPendingTeleportManager().register(id, pending);
        futures.put(id, future);
        return future;
    }

    /**
     * Cancels a running countdown. Completes the player's future
     * exceptionally so the calling RtpService skips the teleport.
     */
    public boolean cancel(UUID id, String reason) {
        PendingTeleport pending = plugin.getPendingTeleportManager().remove(id);
        if (pending != null) {
            pending.getCountdownTask().cancel();
        }
        cleanup(id);
        CompletableFuture<Void> future = futures.remove(id);
        if (future != null && !future.isDone()) {
            future.completeExceptionally(new CountdownCancelledException(reason));
        }
        Player p = Bukkit.getPlayer(id);
        if (p != null && pending != null) {
            p.sendMessage(plugin.getMessagesConfig().get("countdown-cancelled", p, Map.of("reason", reason)));
        }
        return pending != null;
    }

    public void shutdown() {
        for (Map.Entry<UUID, org.bukkit.boss.BossBar> entry : bukkitBars.entrySet()) {
            entry.getValue().removeAll();
        }
        bukkitBars.clear();
        for (CompletableFuture<Void> f : futures.values()) {
            if (!f.isDone()) f.completeExceptionally(new CountdownCancelledException("plugin disabled"));
        }
        futures.clear();
    }

    private void cleanup(UUID id) {
        org.bukkit.boss.BossBar bar = bukkitBars.remove(id);
        if (bar != null) bar.removeAll();
    }

    private org.bukkit.boss.BossBar createBar(PluginConfig cfg) {
        BarColor color = cfg.getBossbarColor();
        BarStyle style = cfg.getBossbarStyle();
        org.bukkit.boss.BossBar bar = Bukkit.createBossBar("§5Teleport", color, style);
        bar.setProgress(1.0);
        bar.setVisible(true);
        return bar;
    }

    public static final class CountdownCancelledException extends RuntimeException {
        public CountdownCancelledException(String reason) { super(reason); }
    }
}
