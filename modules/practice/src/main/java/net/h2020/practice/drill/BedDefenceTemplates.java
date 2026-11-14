package net.h2020.practice.drill;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The defence shapes the drill asks for, editable in game.
 *
 * <p>A template is a list of cells relative to the bed's foot block and the material each one wants. They are kept in
 * {@code bed-defence-templates.yml} in order, and the drill walks them from the first to the last as the player
 * completes each one - so adding a shape in game adds a level, and removing one removes it.
 *
 * <p>The four shipped shapes are written on first load, so a fresh install has something to build and an operator can
 * open the file to see the format. Everything after that is theirs.
 */
public final class BedDefenceTemplates {

    private static final Map<String, List<Object[]>> TEMPLATES = new LinkedHashMap<String, List<Object[]>>();
    private static File file;
    private static String active;

    private BedDefenceTemplates() { }

    public static void load(JavaPlugin plugin) {
        file = new File(plugin.getDataFolder().getParentFile(), "Lattice/bed-defence-templates.yml");
        TEMPLATES.clear();
        active = null;
        if (file.isFile()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
            active = y.getString("active");
            ConfigurationSection sec = y.getConfigurationSection("templates");
            if (sec != null) {
                for (String id : sec.getKeys(false)) {
                    List<Object[]> cells = new ArrayList<Object[]>();
                    for (String row : sec.getStringList(id + ".cells")) {
                        String[] c = row.split(",");
                        if (c.length < 4) continue;
                        try {
                            cells.add(new Object[]{Integer.parseInt(c[0].trim()), Integer.parseInt(c[1].trim()),
                                    Integer.parseInt(c[2].trim()), Material.valueOf(c[3].trim())});
                        } catch (RuntimeException ignored) { }
                    }
                    if (!cells.isEmpty()) TEMPLATES.put(id, cells);
                }
            }
        }
        if (TEMPLATES.isEmpty()) defaults();
        save(plugin);
    }

    /** The four shapes the drill shipped with, so the first run is not an empty list. */
    private static void defaults() {
        TEMPLATES.put("front-row", row(-1, 1, 2, 2));
        TEMPLATES.put("ring", ring());
        TEMPLATES.put("ring-roof", roofed());
    }

    private static List<Object[]> row(int x1, int z, int x2, int pad) {
        List<Object[]> out = new ArrayList<Object[]>();
        for (int x = x1; x <= x2; x++) out.add(new Object[]{x, 0, z, Material.WOOL});
        return out;
    }

    private static List<Object[]> ring() {
        List<Object[]> out = new ArrayList<Object[]>();
        int[][] ring = {{-1, 0}, {-1, 1}, {0, 1}, {1, 1}, {2, 1}, {2, 0}, {2, -1}, {1, -1}, {0, -1}, {-1, -1}};
        for (int[] c : ring) out.add(new Object[]{c[0], 0, c[1], Material.WOOL});
        return out;
    }

    private static List<Object[]> roofed() {
        List<Object[]> out = ring();
        for (int dx = -1; dx <= 2; dx++) for (int dz = -1; dz <= 1; dz++) {
            if ((dx == 0 || dx == 1) && dz == 0) continue;             // the bed itself
            out.add(new Object[]{dx, 1, dz, Material.WOOL});
        }
        return out;
    }

    public static void save(JavaPlugin plugin) {
        if (file == null) return;
        YamlConfiguration y = new YamlConfiguration();
        y.set("active", active);
        if (!file.getParentFile().isDirectory()) file.getParentFile().mkdirs();
        for (Map.Entry<String, List<Object[]>> e : TEMPLATES.entrySet()) {
            List<String> rows = new ArrayList<String>();
            for (Object[] c : e.getValue()) rows.add(c[0] + "," + c[1] + "," + c[2] + "," + c[3]);
            y.set("templates." + e.getKey() + ".cells", rows);
        }
        try {
            y.save(file);
        } catch (IOException ignored) { }
    }

    public static List<String> ids() { return new ArrayList<String>(TEMPLATES.keySet()); }

    public static boolean has(String id) { return id != null && TEMPLATES.containsKey(id); }

    public static List<Object[]> get(String id) {
        if (id == null) {
            // no id given: the first template, so a drill called without a selection still works
            for (List<Object[]> v : TEMPLATES.values()) return v;
            return new ArrayList<Object[]>();
        }
        List<Object[]> v = TEMPLATES.get(id);
        return v == null ? new ArrayList<Object[]>() : v;
    }

    /** The template for a level: the nth in the file, clamped to the last one. */
    public static List<Object[]> at(int level) {
        if (TEMPLATES.isEmpty()) return new ArrayList<Object[]>();
        int i = Math.max(0, Math.min(TEMPLATES.size() - 1, level - 1));
        int n = 0;
        for (List<Object[]> v : TEMPLATES.values()) if (n++ == i) return v;
        return new ArrayList<Object[]>();
    }

    public static int count() { return TEMPLATES.size(); }

    public static void put(String id, List<Object[]> cells) {
        if (id == null || id.isEmpty()) return;
        TEMPLATES.put(id, cells);
        if (active == null) active = id;
    }

    public static boolean remove(String id) {
        boolean had = TEMPLATES.remove(id) != null;
        if (had && id.equals(active)) active = TEMPLATES.isEmpty() ? null : TEMPLATES.keySet().iterator().next();
        return had;
    }

    public static String activeId() { return active; }

    public static void active(String id) { if (TEMPLATES.containsKey(id)) active = id; }

    public static String path() { return file == null ? "bed-defence-templates.yml" : file.getPath(); }
}
