package net.h2020.maps;

import net.h2020.api.game.GameFamily;
import net.h2020.maps.gen.GeneratedMap;
import org.bukkit.block.Biome;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * map.yml reader/writer. The YAML uses '/' as path separator so point keys keep their dots, e.g.
 *
 * <pre>
 * name: Beacon Point
 * family: BEDWARS
 * teams: [8]
 * build-limit: 91
 * biome: BEACH
 * bounds: {min: {x: -120, y: 0, z: -120}, max: {x: 120, y: 91, z: 120}}
 * points:
 *   team.red.spawn: [{x: -27.5, y: 66, z: -80.5, yaw: 0}]
 *   team.red.bed: ["-28,66,-74", "-28,66,-73"]       # string form "x,y,z[,yaw]" also accepted
 * </pre>
 * Nested point keys (team: {red: {spawn: [...]}}) are accepted as well, so hand-written files just work.
 */
public final class MapFiles {
    private MapFiles() {}

    public static final String MAP_YML = "map.yml";

    public static YamlConfiguration newYaml() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().pathSeparator('/');
        return y;
    }

    public static YamlConfiguration loadYaml(File f) {
        YamlConfiguration y = newYaml();
        try {
            y.load(f);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid " + f + ": " + e.getMessage(), e);
        }
        return y;
    }

    /** Parse a template folder. Returns null (with reason logged by the caller) if it is not a usable map. */
    public static StoredMapTemplate read(MapServiceImpl service, File dir) {
        File yml = new File(dir, MAP_YML);
        if (!yml.isFile()) throw new IllegalArgumentException("no map.yml");
        YamlConfiguration y = loadYaml(yml);
        String name = y.getString("name", dir.getName());
        GameFamily family;
        try {
            family = GameFamily.valueOf(y.getString("family", "").trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("family must be BEDWARS or SKYWARS");
        }
        List<Integer> teams = new ArrayList<Integer>();
        if (y.isList("teams")) for (Object o : y.getList("teams")) teams.add(toInt(o));
        else if (y.contains("teams")) teams.add(y.getInt("teams"));
        Map<String, List<Vector>> points = new LinkedHashMap<String, List<Vector>>();
        Map<String, List<Float>> yaws = new LinkedHashMap<String, List<Float>>();
        ConfigurationSection ps = y.getConfigurationSection("points");
        if (ps != null) readPoints(ps, "", points, yaws);
        if (teams.isEmpty()) teams.add(inferTeams(family, points));
        int buildLimit = y.getInt("build-limit", 255);
        Vector min = vec(y.getConfigurationSection("bounds/min"), new Vector(-512, 0, -512));
        Vector max = vec(y.getConfigurationSection("bounds/max"), new Vector(512, 255, 512));
        Vector bmin = Vector.getMinimum(min, max), bmax = Vector.getMaximum(min, max);
        Biome biome = Biome.PLAINS;
        try { biome = Biome.valueOf(y.getString("biome", "PLAINS").toUpperCase()); } catch (IllegalArgumentException ignored) {}
        boolean generated = y.getBoolean("generator/generated", false);
        int version = y.getInt("generator/version", 0);
        String theme = y.getString("generator/theme", "");
        return new StoredMapTemplate(service, dir, name, family, teams, buildLimit, bmin, bmax, points, yaws, biome,
                generated, version, theme, readTags(y));
    }

    /** tags: [dreams, seasonal] or tags: "dreams, seasonal"; always lower case. */
    public static List<String> readTags(YamlConfiguration y) {
        List<String> out = new ArrayList<String>();
        if (y.isList("tags")) {
            for (Object o : y.getList("tags")) addTag(out, String.valueOf(o));
        } else if (y.contains("tags")) {
            for (String t : y.getString("tags", "").split(",")) addTag(out, t);
        }
        return out;
    }

    private static void addTag(List<String> out, String raw) {
        String t = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (!t.isEmpty() && !out.contains(t)) out.add(t);
    }

    /** Rewrite only the tags line of a map.yml, keeping every other line (and comment) exactly as it is. */
    public static void writeTags(File dir, List<String> tags) throws IOException {
        File f = new File(dir, MAP_YML);
        List<String> lines = new ArrayList<String>();
        if (f.isFile()) lines.addAll(java.nio.file.Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder tagLine = new StringBuilder("tags: [");
        for (int i = 0; i < tags.size(); i++) { if (i > 0) tagLine.append(", "); tagLine.append(tags.get(i)); }
        tagLine.append(']');
        boolean replaced = false;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("tags:")) { lines.set(i, tagLine.toString()); replaced = true; break; }
        }
        if (!replaced) {
            int at = lines.size();
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.startsWith("family:") || l.startsWith("teams:") || l.startsWith("build-limit:")) at = i + 1;
            }
            lines.add(at, tagLine.toString());
        }
        java.nio.file.Files.write(f.toPath(), lines, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void readPoints(ConfigurationSection sec, String prefix, Map<String, List<Vector>> points,
                                   Map<String, List<Float>> yaws) {
        for (String k : sec.getKeys(false)) {
            String key = prefix.isEmpty() ? k : prefix + "." + k;
            Object v = sec.get(k);
            List<Vector> pl = new ArrayList<Vector>();
            List<Float> yl = new ArrayList<Float>();
            if (v instanceof ConfigurationSection) {
                ConfigurationSection cs = (ConfigurationSection) v;
                if (cs.contains("x") && cs.contains("z")) {
                    parse(cs.getValues(false), pl, yl);
                } else {
                    readPoints(cs, key, points, yaws);
                    continue;
                }
            } else if (v instanceof List) {
                for (Object o : (List<?>) v) parse(o, pl, yl);
            } else if (v != null) {
                parse(v, pl, yl);
            }
            if (!pl.isEmpty()) { points.put(key, pl); yaws.put(key, yl); }
        }
    }

    private static void parse(Object o, List<Vector> pl, List<Float> yl) {
        if (o instanceof ConfigurationSection) o = ((ConfigurationSection) o).getValues(false);
        if (o instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) o;
            Object x = m.get("x"), y = m.get("y"), z = m.get("z");
            if (x == null || y == null || z == null) return;
            pl.add(new Vector(toDouble(x), toDouble(y), toDouble(z)));
            Object yaw = m.get("yaw");
            yl.add(yaw == null ? 0f : (float) toDouble(yaw));
        } else if (o instanceof String) {
            String[] parts = ((String) o).split("[,\\s]+");
            if (parts.length < 3) return;
            try {
                pl.add(new Vector(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2])));
                yl.add(parts.length > 3 ? Float.parseFloat(parts[3]) : 0f);
            } catch (NumberFormatException ignored) {
                // skip malformed entry
            }
        }
    }

    private static int inferTeams(GameFamily family, Map<String, List<Vector>> points) {
        int n = 0;
        if (family == GameFamily.SKYWARS) {
            while (points.containsKey("island." + n + ".cage")) n++;
        } else {
            for (String k : points.keySet()) if (k.startsWith("team.") && k.endsWith(".spawn")) n++;
        }
        return n;
    }

    private static Vector vec(ConfigurationSection s, Vector def) {
        if (s == null) return def;
        return new Vector(s.getDouble("x", def.getX()), s.getDouble("y", def.getY()), s.getDouble("z", def.getZ()));
    }

    private static double toDouble(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        return Double.parseDouble(String.valueOf(o).trim());
    }

    private static int toInt(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        return Integer.parseInt(String.valueOf(o).trim());
    }

    // ------------------------------------------------------------------------------------------ writing
    public static void write(GeneratedMap m, File dir, int version, long seed) throws IOException {
        YamlConfiguration y = newYaml();
        y.options().header("Hypixel2020 map template. Generated procedurally (" + m.theme + "); delete this folder to regenerate.\n"
                + "Points: spawns/NPCs/generators are standing positions (block centre, feet y), beds/chests/bounds\n"
                + "are block coordinates. Two-point keys (*.bounds, *.base) are min then max corners.");
        y.set("name", m.name);
        y.set("family", m.family.name());
        y.set("teams", m.teamCounts);
        y.set("build-limit", m.buildLimitY);
        y.set("biome", m.biome.name());
        y.set("bounds/min", xyz(m.boundsMin[0], m.boundsMin[1], m.boundsMin[2]));
        y.set("bounds/max", xyz(m.boundsMax[0], m.boundsMax[1], m.boundsMax[2]));
        y.set("generator/generated", true);
        y.set("generator/version", version);
        y.set("generator/theme", m.theme);
        y.set("generator/seed", seed);
        for (Map.Entry<String, Object> e : m.meta.entrySet()) y.set("generator/" + e.getKey(), e.getValue());
        for (Map.Entry<String, List<double[]>> e : m.points.entrySet()) {
            List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
            for (double[] p : e.getValue()) {
                Map<String, Object> pm = new LinkedHashMap<String, Object>();
                pm.put("x", num(p[0])); pm.put("y", num(p[1])); pm.put("z", num(p[2]));
                if (p[3] != 0) pm.put("yaw", num(p[3]));
                list.add(pm);
            }
            y.set("points/" + e.getKey(), list);
        }
        y.save(new File(dir, MAP_YML));
    }

    private static Map<String, Object> xyz(double x, double y, double z) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("x", num(x)); m.put("y", num(y)); m.put("z", num(z));
        return m;
    }

    /** Integers print without ".0". */
    private static Object num(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e9) return (int) d;
        return Math.round(d * 1000.0) / 1000.0;
    }
}
