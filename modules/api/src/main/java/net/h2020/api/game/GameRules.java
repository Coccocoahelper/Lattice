package net.h2020.api.game;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * What a family's matches allow, as data. Bed Wars and SkyWars both allow everything here, but a future mode (a
 * build-free PvP arena, a no-fall-damage practice lobby) is a config edit rather than a new listener.
 */
public final class GameRules {
    public boolean build = true;
    public boolean breakBlocks = true;
    public boolean pvp = true;
    public boolean hunger = true;
    public boolean fallDamage = true;
    public int respawnSeconds = 5;
    public String winCondition = "last-standing";

    private static final java.util.Map<GameFamily, GameRules> MAP = new java.util.EnumMap<>(GameFamily.class);

    public static GameRules of(GameFamily family) {
        GameRules r = MAP.get(family);
        if (r == null) { r = new GameRules(); MAP.put(family, r); }
        return r;
    }

    /** Overrides from network.yml: families.<F>.rules.<field>. */
    public static void load(File dataFolder) {
        File f = new File(dataFolder, "network.yml");
        if (!f.isFile()) return;
        FileConfiguration c = YamlConfiguration.loadConfiguration(f);
        if (c.getConfigurationSection("families") == null) return;
        for (String key : c.getConfigurationSection("families").getKeys(false)) {
            GameFamily family;
            try { family = GameFamily.valueOf(key.toUpperCase()); } catch (IllegalArgumentException e) { continue; }
            String base = "families." + key + ".rules.";
            GameRules r = of(family);
            if (c.isSet(base + "build")) r.build = c.getBoolean(base + "build");
            if (c.isSet(base + "break-blocks")) r.breakBlocks = c.getBoolean(base + "break-blocks");
            if (c.isSet(base + "pvp")) r.pvp = c.getBoolean(base + "pvp");
            if (c.isSet(base + "hunger")) r.hunger = c.getBoolean(base + "hunger");
            if (c.isSet(base + "fall-damage")) r.fallDamage = c.getBoolean(base + "fall-damage");
            if (c.isSet(base + "respawn-seconds")) r.respawnSeconds = c.getInt(base + "respawn-seconds");
            if (c.isSet(base + "win-condition")) r.winCondition = c.getString(base + "win-condition");
        }
    }
}
