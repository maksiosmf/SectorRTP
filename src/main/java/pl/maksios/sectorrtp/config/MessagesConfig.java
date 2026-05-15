package pl.maksios.sectorrtp.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import pl.maksios.sectorrtp.SectorRTPPlugin;
import pl.maksios.sectorrtp.hooks.PlaceholderAPIHook;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads and renders messages.yml.
 *
 * <p>All entries are interpreted as <b>MiniMessage</b> strings. The literal
 * tag {@code <prefix>} is replaced by the value of {@code prefix} before
 * parsing so users can compose messages naturally.</p>
 *
 * <p>If PlaceholderAPI is installed, {@code %xxx_yyy%} placeholders are
 * expanded as well — see {@link PlaceholderAPIHook}.</p>
 */
public final class MessagesConfig {

    private final SectorRTPPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    private final Map<String, String> raw = new HashMap<>();
    private String prefix = "";

    public MessagesConfig(SectorRTPPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        raw.clear();
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }

        FileConfiguration fileCfg = YamlConfiguration.loadConfiguration(file);

        // Merge with defaults bundled inside the jar so missing keys do not crash.
        try (InputStreamReader in = new InputStreamReader(
                plugin.getResource("messages.yml"), StandardCharsets.UTF_8)) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(in);
            fileCfg.setDefaults(defaults);
        } catch (IOException | NullPointerException ignored) {
            // bundled resource missing — defaults will simply be empty
        }

        for (String key : fileCfg.getKeys(true)) {
            Object value = fileCfg.get(key);
            if (value instanceof String s) raw.put(key, s);
        }
        prefix = raw.getOrDefault("prefix", "");
    }

    public Component get(String key, OfflinePlayer player, Map<String, String> placeholders) {
        String template = raw.getOrDefault(key, key);
        template = template.replace("<prefix>", prefix);

        // Apply PAPI placeholders first (only if player is known).
        if (player != null) {
            template = PlaceholderAPIHook.apply(player, template);
        }

        TagResolver.Builder builder = TagResolver.builder();
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                String name = entry.getKey();
                builder.resolver(Placeholder.parsed(name, entry.getValue() == null ? "" : entry.getValue()));
            }
        }
        return mm.deserialize(template, builder.build());
    }

    public Component get(String key) {
        return get(key, null, Map.of());
    }

    public Component get(String key, Map<String, String> placeholders) {
        return get(key, null, placeholders);
    }

    public String getPrefix() {
        return prefix;
    }
}
