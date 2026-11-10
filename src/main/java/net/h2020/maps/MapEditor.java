package net.h2020.maps;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-place editing of one point in a map.yml, the job the setup items do in BedWars1058 addons: stand where the spawn,
 * generator or NPC should be, run the command, and the map is fixed without regenerating it.
 */
public final class MapEditor {
    private MapEditor() {}

    public static boolean setPoint(File mapDir, String key, Location at, boolean secondPoint) {
        return writePoint(mapDir, key, at, secondPoint, -1);
    }

    /** index -2 appends a new entry (a second diamond generator), >= 0 replaces that entry, -1 replaces the first. */
    public static boolean writePoint(File mapDir, String key, Location at, boolean secondPoint, int index) {
        if (mapDir == null || !mapDir.isDirectory()) return false;
        File f = new File(mapDir, MapFiles.MAP_YML);
        if (!f.isFile()) return false;
        // same path separator as MapFiles: '/' so point keys keep their dots (team.red.spawn is one path)
        YamlConfiguration y = new YamlConfiguration();
        y.options().pathSeparator('/');
        try { y.load(f); } catch (Exception e) { return false; }
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        String path = "points/" + key;
        if (y.isList(path)) {
            for (Object o : y.getList(path)) {
                if (o instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) o;
                    Map<String, Object> copy = new LinkedHashMap<String, Object>();
                    for (Map.Entry<?, ?> e : m.entrySet()) copy.put(String.valueOf(e.getKey()), e.getValue());
                    list.add(copy);
                }
            }
        }
        Map<String, Object> p = point(at);
        if (index == -2) {
            list.add(p);
            y.set(path, list);
            try { y.save(f); return true; } catch (IOException e) { return false; }
        }
        if (index > 0 && index < list.size()) {
            list.set(index, p);
            y.set(path, list);
            try { y.save(f); return true; } catch (IOException e) { return false; }
        }
        if (list.isEmpty() || !secondPoint) {
            if (list.isEmpty()) list.add(p); else list.set(0, p);
        } else {
            // a bed is two blocks: the clicked one is the foot, the head sits beside it on the long axis
            list.set(0, p);
            Map<String, Object> head = point(at.clone().add(at.getYaw() >= -45 && at.getYaw() < 45 ? 1 : 0, 0,
                    Math.abs(at.getYaw()) >= 45 && Math.abs(at.getYaw()) < 135 ? (at.getYaw() > 0 ? -1 : 1) : 0));
            if (list.size() > 1) list.set(1, head); else list.add(head);
        }
        y.set(path, list);
        try { y.save(f); return true; } catch (IOException e) { return false; }
    }

    /**
     * Replace a key with exactly two points: a base box is two opposite corners, not a bed whose head is derived from
     * the foot's yaw, so it cannot go through writePoint's second-point path.
     */
    public static boolean setPair(File mapDir, String key, Location first, Location second) {
        if (mapDir == null || !mapDir.isDirectory()) return false;
        File f = new File(mapDir, MapFiles.MAP_YML);
        if (!f.isFile()) return false;
        YamlConfiguration y = new YamlConfiguration();
        y.options().pathSeparator('/');
        try { y.load(f); } catch (Exception e) { return false; }
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        list.add(point(first));
        list.add(point(second));
        y.set("points/" + key, list);
        try { y.save(f); return true; } catch (IOException e) { return false; }
    }

    private static Map<String, Object> point(Location l) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("x", num(l.getX()));
        m.put("y", num(l.getY()));
        m.put("z", num(l.getZ()));
        m.put("yaw", num(Math.round(l.getYaw() / 15f) * 15f));
        return m;
    }

    private static Object num(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e9) return (int) d;
        return Math.round(d * 1000.0) / 1000.0;
    }

    /** Set both halves of a bed from a clicked bed block, using its own facing rather than the player's yaw. */
    public static boolean setBed(File mapDir, String key, org.bukkit.block.Block block) {
        byte data = block.getData();
        boolean head = (data & 0x8) != 0;
        int dirX = 0, dirZ = 0;
        switch (data & 0x3) {
            case 0: dirZ = 1; break;      // south: head is +z
            case 1: dirX = -1; break;     // west
            case 2: dirZ = -1; break;     // north
            default: dirX = 1; break;     // east
        }
        int fx = head ? block.getX() - dirX : block.getX();
        int fz = head ? block.getZ() - dirZ : block.getZ();
        Location foot = new Location(block.getWorld(), fx + 0.5, block.getY(), fz + 0.5);
        Location top = new Location(block.getWorld(), fx + dirX + 0.5, block.getY(), fz + dirZ + 0.5);
        return writePoint(mapDir, key, foot, false, -1) && writePoint(mapDir, key, top, true, 1);
    }

    /** Remove one entry of a multi-point key (a generator). */
    public static boolean removePoint(File mapDir, String key, int index) {
        File f = new File(mapDir, MapFiles.MAP_YML);
        if (!f.isFile()) return false;
        YamlConfiguration y = new YamlConfiguration();
        y.options().pathSeparator('/');
        try { y.load(f); } catch (Exception e) { return false; }
        String path = "points/" + key;
        if (!y.isList(path)) return false;
        List<?> list = y.getList(path);
        if (index < 0 || index >= list.size()) return false;
        List<Object> copy = new ArrayList<Object>(list);
        copy.remove(index);
        y.set(path, copy);
        try { y.save(f); return true; } catch (IOException e) { return false; }
    }

    /** Write a scalar setting: build-limit, or one axis of a bounds corner. */
    public static boolean setScalar(File mapDir, String key, String value) {
        File f = new File(mapDir, MapFiles.MAP_YML);
        if (!f.isFile()) return false;
        YamlConfiguration y = new YamlConfiguration();
        y.options().pathSeparator('/');
        try { y.load(f); } catch (Exception e) { return false; }
        if (key.equals("build-limit")) {
            try { y.set("build-limit", Integer.parseInt(value.trim())); } catch (NumberFormatException e) { return false; }
        } else if (key.startsWith("bounds.")) {
            return false;   // bounds are set as points; see setBounds
        } else {
            y.set(key, value);
        }
        try { y.save(f); return true; } catch (IOException e) { return false; }
    }

    /** Bounds are two corners, stored as bounds/min and bounds/max, not as a points list. */
    public static boolean setBounds(File mapDir, org.bukkit.block.Block block, boolean max) {
        File f = new File(mapDir, MapFiles.MAP_YML);
        if (!f.isFile()) return false;
        YamlConfiguration y = new YamlConfiguration();
        y.options().pathSeparator('/');
        try { y.load(f); } catch (Exception e) { return false; }
        String base = max ? "bounds/max/" : "bounds/min/";
        y.set(base + "x", block.getX());
        y.set(base + "y", block.getY());
        y.set(base + "z", block.getZ());
        try { y.save(f); return true; } catch (IOException e) { return false; }
    }

    /** Keys a setup command accepts, for tab completion. */
    public static String[] keys() {
        List<String> out = new ArrayList<String>();
        for (String c : new String[]{"red", "blue", "green", "yellow", "aqua", "white", "pink", "gray"}) {
            for (String k : new String[]{"spawn", "bed", "generator", "shop", "upgrades"}) out.add("team." + c + "." + k);
        }
        out.add("waiting.spawn");
        out.add("spectator.spawn");
        return out.toArray(new String[0]);
    }

    public static String describe(File mapDir, String key) {
        if (mapDir == null) return "no such map";
        YamlConfiguration y = new YamlConfiguration();
        y.options().pathSeparator('/');
        try { y.load(new File(mapDir, MapFiles.MAP_YML)); } catch (Exception e) { return "unreadable"; }
        org.bukkit.configuration.ConfigurationSection s = y.getConfigurationSection("points/" + key);
        return s == null ? "not set" : "set";
    }
}
