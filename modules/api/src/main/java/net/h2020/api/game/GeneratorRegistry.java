package net.h2020.api.game;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Where the generators of a game world are, so anything outside the game module can ask "is somebody farming?". */
public final class GeneratorRegistry {
    private static final Map<World, List<Location>> BY_WORLD = new HashMap<World, List<Location>>();
    /** Island centres (team bases): which island somebody is on, and which one he came from. */
    private static final Map<World, List<Location>> ISLANDS = new HashMap<World, List<Location>>();
    private static final Map<World, List<String>> ISLAND_NAMES = new HashMap<World, List<String>>();
    /** Typed points of interest: team forges, diamond generators, emerald generators, shops. */
    public static final int TEAM_GEN = 0, DIAMOND_GEN = 1, EMERALD_GEN = 2, SHOP = 3, BED = 4, TYPES = 5;
    private static final Map<World, List<Location>[]> POI = new HashMap<World, List<Location>[]>();

    private GeneratorRegistry() {}

    public static void set(World w, List<Location> gens) {
        if (w == null) return;
        BY_WORLD.put(w, new ArrayList<Location>(gens));
    }

    public static void clear(World w) {
        if (w == null) return;
        BY_WORLD.remove(w);
        ISLANDS.remove(w);
        POI.remove(w);
        ISLAND_NAMES.remove(w);
    }

    @SuppressWarnings("unchecked")
    public static void setPoints(World w, int type, List<Location> points) {
        if (w == null || type < 0 || type >= TYPES) return;
        List<Location>[] arr = POI.get(w);
        if (arr == null) { arr = new List[TYPES]; POI.put(w, arr); }
        arr[type] = new ArrayList<Location>(points);
    }

    /** Distance to the nearest point of that type, or null when the world has none. */
    public static Double nearest(World w, int type, Location at) {
        List<Location>[] arr = POI.get(w);
        if (arr == null || arr[type] == null || arr[type].isEmpty() || at == null) return null;
        double best = Double.MAX_VALUE;
        for (Location l : arr[type]) {
            if (l.getWorld() != at.getWorld()) continue;
            best = Math.min(best, l.distance(at));
        }
        return best == Double.MAX_VALUE ? null : best;
    }

    /** The points of that type in a world (empty when none). */
    public static List<Location> points(World w, int type) {
        List<Location>[] arr = POI.get(w);
        if (arr == null || type < 0 || type >= TYPES || arr[type] == null) return java.util.Collections.emptyList();
        return arr[type];
    }

    /** Is the position inside the given radius of any point of that type? */
    public static boolean near(World w, int type, Location at, double radius) {
        Double d = nearest(w, type, at);
        return d != null && d <= radius;
    }

    public static void setIslands(World w, List<Location> islands) {
        setIslands(w, islands, null);
    }

    public static void setIslands(World w, List<Location> islands, List<String> names) {
        if (w == null) return;
        ISLANDS.put(w, new ArrayList<Location>(islands));
        if (names != null) ISLAND_NAMES.put(w, new ArrayList<String>(names));
    }

    /** The islands of a world with their names (team colours), for "which base is he heading for". */
    public static List<Location> islands(World w) {
        List<Location> l = ISLANDS.get(w);
        return l == null ? java.util.Collections.<Location>emptyList() : l;
    }

    public static String islandName(World w, int index) {
        List<String> names = ISLAND_NAMES.get(w);
        if (names == null || index < 0 || index >= names.size()) return null;
        return names.get(index);
    }

    /** Index of the island this position is on/next to (0 when the world has none registered). */
    public static int islandIndex(World w, Location at) {
        List<Location> islands = ISLANDS.get(w);
        if (islands == null || islands.isEmpty() || at == null) return 0;
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < islands.size(); i++) {
            Location l = islands.get(i);
            if (l.getWorld() != at.getWorld()) continue;
            double d = l.distanceSquared(at);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    /** Distance to the nearest known generator (null when the world has none registered). */
    public static Double nearest(World w, Location at) {
        List<Location> gens = BY_WORLD.get(w);
        if (gens == null || gens.isEmpty() || at == null) return null;
        double best = Double.MAX_VALUE;
        for (Location g : gens) {
            if (g.getWorld() != at.getWorld()) continue;
            best = Math.min(best, g.distance(at));
        }
        return best == Double.MAX_VALUE ? null : best;
    }
}
