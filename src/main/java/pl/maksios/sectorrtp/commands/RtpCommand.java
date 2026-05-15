package pl.maksios.sectorrtp.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import pl.maksios.sectorrtp.SectorRTPPlugin;
import pl.maksios.sectorrtp.config.MessagesConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Routes /rtp, /rtp reload and /rtp force &lt;player&gt;.
 */
public final class RtpCommand implements CommandExecutor, TabCompleter {

    private final SectorRTPPlugin plugin;

    public RtpCommand(SectorRTPPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        MessagesConfig msg = plugin.getMessagesConfig();

        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(msg.get("players-only"));
                return true;
            }
            if (!player.hasPermission("sectorrtp.use")) {
                player.sendMessage(msg.get("no-permission", player, Map.of()));
                return true;
            }
            plugin.getRtpService().requestRtp(player, false);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> handleReload(sender);
            case "force" -> handleForce(sender, args);
            default -> sender.sendMessage(msg.get("unknown-subcommand"));
        }
        return true;
    }

    private void handleReload(CommandSender sender) {
        MessagesConfig msg = plugin.getMessagesConfig();
        if (!sender.hasPermission("sectorrtp.admin")) {
            sender.sendMessage(msg.get("no-permission"));
            return;
        }
        long start = System.currentTimeMillis();
        try {
            plugin.reload();
            long elapsed = System.currentTimeMillis() - start;
            sender.sendMessage(plugin.getMessagesConfig().get(
                    "reload-success", null, Map.of("time", String.valueOf(elapsed))));
        } catch (Exception ex) {
            sender.sendMessage(plugin.getMessagesConfig().get(
                    "reload-failed", null, Map.of("reason", ex.getMessage() == null ? "unknown" : ex.getMessage())));
            plugin.getLogger().warning("Reload failure: " + ex);
        }
    }

    private void handleForce(CommandSender sender, String[] args) {
        MessagesConfig msg = plugin.getMessagesConfig();
        if (!sender.hasPermission("sectorrtp.admin")) {
            sender.sendMessage(msg.get("no-permission"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(msg.get("unknown-subcommand"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(msg.get("admin-force-not-online", null, Map.of("player", args[1])));
            return;
        }
        plugin.getRtpService().requestRtp(target, true);
        sender.sendMessage(msg.get("admin-force-success", null, Map.of("player", target.getName())));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            if (sender.hasPermission("sectorrtp.admin")) {
                out.add("reload");
                out.add("force");
            }
            String prefix = args[0].toLowerCase(Locale.ROOT);
            out.removeIf(s -> !s.startsWith(prefix));
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("force") && sender.hasPermission("sectorrtp.admin")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
            }
        }
        return out;
    }
}
