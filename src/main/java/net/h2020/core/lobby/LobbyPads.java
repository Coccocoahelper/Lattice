package net.h2020.core.lobby;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * Reads plugins/Hypixel2020/lobby-npcs.yml, written by the maps module for its generated hubs: the spawn (with yaw)
 * and the NPC pad of every game / mode. Top-level "world"/"spawn"/"bedwars"/"skywars" describe the main lobby;
 * "bedwars_lobby" / "skywars_lobby" sections describe the family lobbies, with pads keyed by GameType id.
 * Everything returns null when the file or the entry is missing (imported hubs), so callers keep their defaults.
 */
public final class LobbyPads {
    private final JavaPlugin plugin;
    private YamlConfiguration yml;
    private long loadedStamp = -1;

    public LobbyPads(JavaPlugin plugin) { this.plugin = plugin; }

    private long checkedAt;

    private YamlConfiguration yml() {
        long now = System.currentTimeMillis();
        if (yml != null && now - checkedAt < 10000) return yml;
        checkedAt = now;
        File f = new File(plugin.getDataFolder(), "lobby-npcs.yml");
        long stamp = f.isFile() ? f.lastModified() : 0;
        if (yml == null || stamp != loadedStamp) {
            yml = stamp == 0 ? new YamlConfiguration() : YamlConfiguration.loadConfiguration(f);
            loadedStamp = stamp;
        }
        return yml;
    }

    /** The section describing this lobby world (root for the main lobby), or null. */
    private ConfigurationSection sectionFor(World w) {
        if (w == null) return null;
        YamlConfiguration y = yml();
        if (sameHub(w.getName(), y.getString("world"))) return y;
        for (String k : new String[]{"bedwars_lobby", "skywars_lobby"}) {
            ConfigurationSection s = y.getConfigurationSection(k);
            if (s != null && sameHub(w.getName(), s.getString("world"))) return s;
        }
        return null;
    }

    /**
     * Does a live world belong to the hub this section describes? A hub that comes out of the world manager is an
     * instance with a generated name ({@code instances/swm_swofty_lobby_main_7}), so an exact match is not enough.
     */
    private static boolean sameHub(String actual, String configured) {
        if (actual == null || configured == null || configured.isEmpty()) return false;
        return actual.equals(configured) || actual.contains(configured);
    }

    /** Hub spawn with its yaw, or null. */
    public Location spawn(World w) { return loc(w, sectionFor(w), "spawn"); }

    /** NPC pad by key ("bedwars", "skywars" in the main lobby; a GameType id in a family lobby), or null. */
    public Location pad(World w, String key) { return loc(w, sectionFor(w), key); }

    private static Location loc(World w, ConfigurationSection s, String key) {
        if (s == null) return null;
        ConfigurationSection p = s.getConfigurationSection(key);
        if (p == null || !p.contains("x") || !p.contains("z")) return null;
        return new Location(w, p.getDouble("x"), p.getDouble("y"), p.getDouble("z"), (float) p.getDouble("yaw"), 0f);
    }
}
