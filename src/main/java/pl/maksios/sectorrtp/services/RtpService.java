package pl.maksios.sectorrtp.services;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import pl.maksios.sectorrtp.SectorRTPPlugin;
import pl.maksios.sectorrtp.config.PluginConfig;
import pl.maksios.sectorrtp.events.SectorRandomTeleportEvent;
import pl.maksios.sectorrtp.events.SectorRandomTeleportPreEvent;
import pl.maksios.sectorrtp.hooks.EndSectorsHook;
import pl.maksios.sectorrtp.managers.CooldownManager;
import pl.maksios.sectorrtp.managers.PendingTeleportManager;
import pl.maksios.sectorrtp.model.SafeLocation;
import pl.maksios.sectorrtp.model.SectorBounds;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Orchestrator of the /rtp flow.
 *
 * <p>Pipeline:</p>
 * <pre>
 *   request -> validate gates (perms / cooldown / pending / lock)
 *           -> pick random sector (1-4)         ← {@link EndSectorsHook}
 *           -> fetch bounds of picked sector
 *           -> async find safe location         ← {@link SafeLocationFinder}
 *           -> countdown                        ← {@link CountdownService}
 *           -> dispatch teleport (local or cross-sector)
 *           -> apply cooldown + cross-sector lock
 *           -> post-effects
 * </pre>
 */
public final class RtpService {

    public enum FailureReason {
        NO_SECTORS, NO_SAFE_LOCATION, CANCELLED, INTERNAL, FORBIDDEN
    }

    public record RtpResult(boolean success, SafeLocation location, String sector, FailureReason reason) {
        public static RtpResult ok(SafeLocation loc, String sector) {
            return new RtpResult(true, loc, sector, null);
        }
        public static RtpResult fail(FailureReason reason) {
            return new RtpResult(false, null, null, reason);
        }
    }

    private final SectorRTPPlugin plugin;

    public RtpService(SectorRTPPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Main public entry point.
     *
     * @param force if true: bypass cooldown / pending / locks (used by /rtp force).
     */
    public CompletableFuture<RtpResult> requestRtp(Player player, boolean force) {
        UUID id = player.getUniqueId();
        PluginConfig cfg = plugin.getPluginConfig();
        CooldownManager cooldowns = plugin.getCooldownManager();
        PendingTeleportManager pending = plugin.getPendingTeleportManager();
        var messages = plugin.getMessagesConfig();

        // ---- gate 1: cooldown ----
        if (!force && !player.hasPermission("sectorrtp.bypass.cooldown") && cooldowns.isOnCooldown(id)) {
            long secs = (cooldowns.remainingMillis(id) + 999L) / 1000L;
            player.sendMessage(messages.get("cooldown", player, Map.of("time", String.valueOf(secs))));
            return CompletableFuture.completedFuture(RtpResult.fail(FailureReason.FORBIDDEN));
        }

        // ---- gate 2: cross-sector lock (anti-fallback) ----
        if (!force && pending.isLocked(id)) {
            player.sendMessage(messages.get("locked-during-transfer", player, Map.of()));
            return CompletableFuture.completedFuture(RtpResult.fail(FailureReason.FORBIDDEN));
        }

        // ---- gate 3: countdown already running ----
        if (!force && pending.hasPending(id)) {
            player.sendMessage(messages.get("already-pending", player, Map.of()));
            return CompletableFuture.completedFuture(RtpResult.fail(FailureReason.FORBIDDEN));
        }

        // ---- step 1: pick a random sector first (1-4) ----
        Optional<SectorBounds> chosenOpt = pickSector(player);
        if (chosenOpt.isEmpty()) {
            player.sendMessage(messages.get("no-sectors-available", player, Map.of()));
            plugin.getEffectService().playFail(player);
            return CompletableFuture.completedFuture(RtpResult.fail(FailureReason.NO_SECTORS));
        }
        SectorBounds chosen = chosenOpt.get();

        // ---- pre-event (cancellable, gives other plugins veto power) ----
        SectorRandomTeleportPreEvent preEvent = new SectorRandomTeleportPreEvent(player, chosen.name(), force);
        Bukkit.getPluginManager().callEvent(preEvent);
        if (preEvent.isCancelled()) {
            return CompletableFuture.completedFuture(RtpResult.fail(FailureReason.CANCELLED));
        }

        player.sendMessage(messages.get("searching", player, Map.of("sector", chosen.name())));
        plugin.getEffectService().playPreTeleport(player);

        // ---- step 2 & 3: search safe location asynchronously ----
        CompletableFuture<RtpResult> result = new CompletableFuture<>();

        plugin.getSafeLocationFinder().findSafeLocation(chosen, attempts -> {
            // actionbar tick on the main thread (sendActionBar is thread-safe via Adventure).
            player.sendActionBar(messages.get("search-actionbar", player,
                    Map.of("attempts", String.valueOf(attempts))));
        }).whenComplete((safe, throwable) -> {
            // continue on main thread
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (throwable != null) {
                    int attempts = (throwable instanceof SafeLocationFinder.SafeLocationFailure f) ? f.getAttempts() : 0;
                    player.sendMessage(messages.get("no-safe-location", player,
                            Map.of("attempts", String.valueOf(Math.max(attempts, cfg.getMaxAttempts())))));
                    plugin.getEffectService().playFail(player);
                    result.complete(RtpResult.fail(FailureReason.NO_SAFE_LOCATION));
                    return;
                }

                // ---- step 4: countdown ----
                plugin.getCountdownService().start(player).whenComplete((v, cdEx) -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (cdEx != null) {
                            result.complete(RtpResult.fail(FailureReason.CANCELLED));
                            return;
                        }
                        // ---- step 5: dispatch ----
                        dispatchTeleport(player, safe, chosen, force).whenComplete((dispatched, dEx) -> {
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (dEx != null || !Boolean.TRUE.equals(dispatched)) {
                                    player.sendMessage(messages.get("teleport-failed", player,
                                            Map.of("reason", dEx == null ? "dispatch failed" : dEx.getMessage())));
                                    plugin.getEffectService().playFail(player);
                                    result.complete(RtpResult.fail(FailureReason.INTERNAL));
                                    return;
                                }

                                // ---- cooldown + lock ----
                                if (!player.hasPermission("sectorrtp.bypass.cooldown")) {
                                    plugin.getCooldownManager().apply(id, cfg.getCooldownMillis());
                                }
                                // Always lock to protect against double-execution during the NATS round trip.
                                plugin.getPendingTeleportManager().lock(id, cfg.getPendingLockMs());

                                // ---- post-effects ----
                                plugin.getEffectService().playPostTeleport(player, chosen.name());
                                player.sendMessage(messages.get("teleport-success", player, Map.of(
                                        "sector", chosen.name(),
                                        "world", safe.getLocation().getWorld().getName(),
                                        "x", String.valueOf(safe.getLocation().getBlockX()),
                                        "y", String.valueOf(safe.getLocation().getBlockY()),
                                        "z", String.valueOf(safe.getLocation().getBlockZ())
                                )));

                                // Fire custom event for other plugins to react.
                                Bukkit.getPluginManager().callEvent(new SectorRandomTeleportEvent(
                                        player, chosen.name(), safe.getLocation(), force));

                                result.complete(RtpResult.ok(safe, chosen.name()));
                            });
                        });
                    });
                });
            });
        });

        return result;
    }

    // -----------------------------------------------------------------
    //  Internals
    // -----------------------------------------------------------------

    private Optional<SectorBounds> pickSector(Player player) {
        EndSectorsHook hook = plugin.getEndSectorsHook();
        var candidates = hook.getEligibleSectors();
        if (candidates.isEmpty()) return Optional.empty();

        if (plugin.getPluginConfig().isPreferCrossSector() && hook.isPresent()) {
            Optional<String> currentName = hook.getCurrentSectorName();
            if (currentName.isPresent()) {
                var filtered = candidates.stream()
                        .filter(s -> !s.name().equals(currentName.get()))
                        .toList();
                if (!filtered.isEmpty()) candidates = filtered;
            }
        }

        return Optional.of(candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())));
    }

    private CompletableFuture<Boolean> dispatchTeleport(Player player, SafeLocation safe, SectorBounds target, boolean force) {
        EndSectorsHook hook = plugin.getEndSectorsHook();
        Location loc = safe.getLocation();

        if (!hook.isPresent()) {
            // standalone mode → simple local teleport
            CompletableFuture<Boolean> f = new CompletableFuture<>();
            player.teleportAsync(loc, PlayerTeleportEvent.TeleportCause.PLUGIN)
                    .whenComplete((ok, ex) -> f.complete(ex == null && Boolean.TRUE.equals(ok)));
            return f;
        }

        // Local teleport when the target sector lives on this same physical Paper server.
        Optional<String> current = hook.getCurrentSectorName();
        boolean sameServer = current.isPresent() && current.get().equals(target.name());

        if (sameServer) {
            CompletableFuture<Boolean> f = new CompletableFuture<>();
            player.teleportAsync(loc, PlayerTeleportEvent.TeleportCause.PLUGIN)
                    .whenComplete((ok, ex) -> f.complete(ex == null && Boolean.TRUE.equals(ok)));
            return f;
        }

        // Cross-sector path – EndSectors will send the player to the destination
        // server via NATS. We save the destination location to Redis first via
        // SectorsAPI so the destination server spawns the player at the random
        // location instead of the previous one (the "fallback" bug protection).
        boolean ok = hook.dispatchCrossSectorTeleport(player, loc, target.name());
        return CompletableFuture.completedFuture(ok);
    }
}
