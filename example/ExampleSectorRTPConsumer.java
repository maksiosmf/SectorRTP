// =====================================================================
//  Example plugin consuming the SectorRTP public API.
//  Drop this in a separate plugin module – it is NOT compiled as part
//  of SectorRTP itself.
// =====================================================================
package com.example.rtpconsumer;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import pl.maksios.sectorrtp.api.SectorRTPAPI;
import pl.maksios.sectorrtp.api.SectorRTPProvider;
import pl.maksios.sectorrtp.events.SectorRandomTeleportEvent;
import pl.maksios.sectorrtp.events.SectorRandomTeleportPreEvent;

public final class ExampleSectorRTPConsumer extends JavaPlugin implements Listener {

    private SectorRTPAPI api;

    @Override
    public void onEnable() {
        // --- option 1: provider singleton ---
        try {
            api = SectorRTPProvider.get();
        } catch (IllegalStateException ignore) {
            // --- option 2: Bukkit ServicesManager ---
            RegisteredServiceProvider<SectorRTPAPI> reg =
                    Bukkit.getServicesManager().getRegistration(SectorRTPAPI.class);
            if (reg == null) {
                getLogger().warning("SectorRTP is not installed – disabling consumer.");
                Bukkit.getPluginManager().disablePlugin(this);
                return;
            }
            api = reg.getProvider();
        }

        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) return false;

        api.requestRtp(player).thenAccept(result -> {
            if (result.success()) {
                getLogger().info(player.getName() + " teleported to " + result.sector());
            } else {
                getLogger().info(player.getName() + " RTP failed: " + result.reason());
            }
        });
        return true;
    }

    /**
     * Example: cancel /rtp for players in a "no-rtp" world.
     */
    @EventHandler
    public void onPreRtp(SectorRandomTeleportPreEvent event) {
        if (event.getPlayer().getWorld().getName().equalsIgnoreCase("event")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cRTP is disabled during events.");
        }
    }

    /**
     * Example: notify a Discord webhook on successful RTP.
     */
    @EventHandler
    public void onRtp(SectorRandomTeleportEvent event) {
        getLogger().info("RTP success: " + event.getPlayer().getName()
                + " -> " + event.getSectorName()
                + " @ " + event.getDestination().getBlockX()
                + ", " + event.getDestination().getBlockY()
                + ", " + event.getDestination().getBlockZ());
    }
}
