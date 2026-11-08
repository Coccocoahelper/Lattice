package net.h2020.api.game;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.EnumMap;
import java.util.Map;

/** Per-family presentation, defaulted in code and overridable in network.yml under "families". */
public final class Families {
    private static final Map<GameFamily, FamilyProfile> MAP = new EnumMap<>(GameFamily.class);

    static {
        MAP.put(GameFamily.BEDWARS, new FamilyProfile(GameFamily.BEDWARS, "BED WARS", "Play Bed Wars", "BED WARS", Material.BED));
        MAP.put(GameFamily.SKYWARS, new FamilyProfile(GameFamily.SKYWARS, "SKYWARS", "Play SkyWars", "SKYWARS", Material.EYE_OF_ENDER));
    }

    private Families() {}

    public static FamilyProfile of(GameFamily family) {
        FamilyProfile p = MAP.get(family);
        if (p == null) {
            String n = family.name();
            p = new FamilyProfile(family, n, "Play " + n, n, Material.PAPER);
            MAP.put(family, p);
        }
        return p;
    }

    public static void load(File dataFolder) {
        File f = new File(dataFolder, "network.yml");
        if (!f.isFile()) return;
        FileConfiguration c = YamlConfiguration.loadConfiguration(f);
        for (String key : c.getConfigurationSection("families") == null
                ? java.util.Collections.<String>emptyList() : c.getConfigurationSection("families").getKeys(false)) {
            GameFamily family;
            try { family = GameFamily.valueOf(key.toUpperCase()); } catch (IllegalArgumentException e) { continue; }
            FamilyProfile p = of(family);
            String base = "families." + key + ".";
            if (c.isString(base + "display")) p.displayName = colour(c.getString(base + "display"));
            if (c.isString(base + "menu-title")) p.menuTitle = colour(c.getString(base + "menu-title"));
            if (c.isString(base + "sidebar-title")) p.sidebarTitle = colour(c.getString(base + "sidebar-title"));
            if (c.isString(base + "icon")) {
                Material m = Material.getMaterial(c.getString(base + "icon").toUpperCase());
                if (m != null) p.icon = m;
            }
        }
    }

    private static String colour(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
}
