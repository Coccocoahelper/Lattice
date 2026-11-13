package net.h2020.api.core;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Every message the plugins send, in one editable file.
 *
 * <p>Defaults ship inside the jar as {@code messages.yml}. On boot the file is written to the plugin's data folder if it
 * is missing, and any key the file does not have is appended from the defaults - so an operator's edits are never
 * overwritten, and a new version's new messages appear on their own instead of showing as a missing key.
 *
 * <p>A message is addressed by a dotted key ({@code bedwars.chest.not-yours}) and may contain placeholders written as
 * {@code {name}}. Colour codes are the usual {@code &} codes, translated on read, so the file reads like the old
 * strings did.
 *
 * <p>A key that is missing from both the file and the defaults returns the key itself in brackets and logs once: a
 * message that says {@code <missing: bedwars.foo>} is a bug report, where an empty string is a mystery.
 */
public final class Messages {

    private static YamlConfiguration file;
    private static YamlConfiguration defaults;
    private static File target;
    private static final java.util.Set<String> warned = new java.util.HashSet<String>();

    private Messages() { }

    /**
     * Load (or create) the message file for a plugin and merge in any key it does not have yet.
     * The defaults are read from the jar's own {@code messages.yml}, which is written by this plugin's build.
     */
    public static void load(JavaPlugin plugin) {
        load(plugin, new File(plugin.getDataFolder(), "messages.yml"), "messages.yml");
    }

    public static void load(JavaPlugin plugin, File where, String resource) {
        target = where;
        defaults = new YamlConfiguration();
        try (InputStream in = plugin.getResource(resource)) {
            if (in != null) {
                defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } else {
                plugin.getLogger().warning("no " + resource + " inside the jar: messages fall back to code defaults");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "could not read the bundled " + resource, e);
        }

        YamlConfiguration fresh = new YamlConfiguration();
        boolean parsed = true;
        if (where.isFile()) {
            try {
                fresh.load(where);
            } catch (Exception e) {
                parsed = false;
                // Keep whatever was loaded last rather than throwing every message away over one bad line: an
                // operator who types an unquoted "&a" gets a parse error, not a server with no messages at all.
                plugin.getLogger().log(Level.WARNING, "could not read " + where.getName()
                        + " (a value starting with & must be quoted, like: won: \"&aYou won!\")"
                        + " - keeping the previous messages", e);
            }
        }
        if (!parsed && file != null) return;
        file = fresh;
        int added = merge();
        try {
            if (!where.getParentFile().isDirectory() && !where.getParentFile().mkdirs()) {
                plugin.getLogger().warning("could not create " + where.getParentFile());
            }
            file.save(where);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "could not write " + where.getName(), e);
        }
        if (added > 0) plugin.getLogger().info("messages: " + added + " key(s) added to " + where.getName());
    }

    /** Add every default key the file lacks. Returns how many were added. */
    private static int merge() {
        int added = 0;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) continue;
            if (!file.contains(key)) {
                file.set(key, defaults.get(key));
                added++;
            }
        }
        return added;
    }

    /** Edits made while the file is open are picked up by calling this again. */
    public static void reload(JavaPlugin plugin) { load(plugin); }

    public static boolean has(String key) {
        return (file != null && file.contains(key)) || (defaults != null && defaults.contains(key));
    }

    /** The message, colours translated and placeholders filled. Pairs of (name, value) follow the key. */
    public static String get(String key, Object... placeholders) {
        String raw = null;
        if (file != null && file.contains(key)) raw = file.getString(key);
        if (raw == null && defaults != null && defaults.contains(key)) raw = defaults.getString(key);
        if (raw == null) {
            if (warned.add(key)) {
                org.bukkit.Bukkit.getLogger().warning("[messages] missing key: " + key);
            }
            return ChatColor.RED + "<missing: " + key + ">";
        }
        return colour(fill(raw, placeholders));
    }

    /** The text without colour translation, for a component that sets its own colour. */
    public static String raw(String key, Object... placeholders) {
        String v = file != null && file.contains(key) ? file.getString(key)
                : defaults != null && defaults.contains(key) ? defaults.getString(key) : null;
        if (v == null) { get(key); return "<missing: " + key + ">"; }
        return fill(v, placeholders);
    }

    /** The message with no substitution, for callers that want to do their own. */
    public static String get(String key) { return get(key, new Object[0]); }

    public static void send(CommandSender to, String key, Object... placeholders) {
        if (to != null) to.sendMessage(get(key, placeholders));
    }

    /** Every default key, for a {@code /messages list} style dump or a test. */
    public static Map<String, String> defaults() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (defaults == null) return out;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) continue;
            out.put(key, defaults.getString(key));
        }
        return out;
    }

    /** A list-valued key, for messages that are several lines. */
    public static java.util.List<String> lines(String key, Object... placeholders) {
        java.util.List<String> raw = null;
        if (file != null && file.contains(key)) raw = file.getStringList(key);
        if ((raw == null || raw.isEmpty()) && defaults != null && defaults.contains(key)) {
            raw = defaults.getStringList(key);
        }
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (raw == null) { out.add(get(key, placeholders)); return out; }
        for (String line : raw) out.add(colour(fill(line, placeholders)));
        return out;
    }

    private static String fill(String s, Object... placeholders) {
        if (s == null) return "";
        if (placeholders != null) {
            for (int i = 0; i + 1 < placeholders.length; i += 2) {
                if (placeholders[i] == null) continue;
                String name = String.valueOf(placeholders[i]);
                String value = placeholders[i + 1] == null ? "" : String.valueOf(placeholders[i + 1]);
                s = s.replace("{" + name + "}", value);
            }
        }
        return s;
    }

    private static String colour(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    /** Where the file lives, for a command that tells the operator what to edit. */
    public static String path() {
        return target == null ? "messages.yml" : target.getPath();
    }
}
