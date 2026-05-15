package pl.maksios.sectorrtp.hooks;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.lang.reflect.Method;

/**
 * Static helper that runs strings through PlaceholderAPI iff it's loaded.
 *
 * <p>Reflective to keep the hard dependency optional.</p>
 */
public final class PlaceholderAPIHook {

    private static boolean present;
    private static Method setPlaceholders;

    private PlaceholderAPIHook() {}

    public static void initialize() {
        present = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        if (!present) return;
        try {
            Class<?> papiClass = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            setPlaceholders = papiClass.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
        } catch (ReflectiveOperationException ex) {
            present = false;
        }
    }

    public static String apply(OfflinePlayer player, String input) {
        if (!present || setPlaceholders == null || input == null || input.isEmpty()) return input;
        try { return (String) setPlaceholders.invoke(null, player, input); }
        catch (ReflectiveOperationException ex) { return input; }
    }

    public static boolean isPresent() {
        return present;
    }
}
