package pl.maksios.sectorrtp.utils;

import org.bukkit.Bukkit;
import pl.maksios.sectorrtp.SectorRTPPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub Releases-based update checker.
 *
 * <p>Fully async using {@link HttpClient}. Falls back silently on any
 * network error so a flaky GitHub never affects server startup.</p>
 */
public final class UpdateChecker {

    private static final Pattern TAG_PATTERN = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"");

    private UpdateChecker() {}

    public static void checkAsync(SectorRTPPlugin plugin) {
        String repo = plugin.getPluginConfig().getUpdateRepository();
        if (repo == null || repo.isBlank()) return;

        String url = "https://api.github.com/repos/" + repo + "/releases/latest";
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "SectorRTP/" + plugin.getPluginMeta().getVersion())
                .GET()
                .build();

        client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) return;
                    Matcher m = TAG_PATTERN.matcher(response.body());
                    if (!m.find()) return;
                    String latest = m.group(1).trim();
                    String current = plugin.getPluginMeta().getVersion();
                    if (latest.isEmpty() || latest.equalsIgnoreCase(current)) return;

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        plugin.getLogger().info("New version available on GitHub: " + latest + " (current: " + current + ")");
                        if (plugin.getPluginConfig().isUpdateNotifyOps()) {
                            for (var p : Bukkit.getOnlinePlayers()) {
                                if (p.isOp()) {
                                    p.sendMessage(plugin.getMessagesConfig().get(
                                            "update-available", p, java.util.Map.of("version", latest)));
                                }
                            }
                        }
                    });
                })
                .exceptionally(ex -> null);
    }
}
