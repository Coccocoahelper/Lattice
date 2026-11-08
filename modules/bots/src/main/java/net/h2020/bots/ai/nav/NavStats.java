package net.h2020.bots.ai.nav;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Navigation health counters per world family (map), for measuring how often bots get stuck or fail to plan.
 * `/bot navstats [reset]` prints them; `/bot navdebug on|off` (or -Dh2020.navdebug=true) also logs every event.
 */
public final class NavStats {
    private NavStats() {}

    /** Runtime-switchable debug logging (initially -Dh2020.navdebug). */
    public static volatile boolean debug = Boolean.getBoolean("h2020.navdebug");
    /** Per-tick navigation trace of one bot (`/bot navtrace <name|off>`). */
    public static volatile String trace;

    /** Partial-block fixes, switchable at runtime for A/B measurement (`/bot navfix <mask>`). */
    public static final int FIX_START = 1, FIX_HEADROOM = 2, FIX_BOXES = 4, FIX_STAIRS = 8, FIX_GUI = 16, FIX_LOOKAHEAD = 32, FIX_WATER = 64;
    public static volatile int fix = 0xFF;

    public static final class Counters {
        public long navTicks, stuck, stuckPartial, stuckFails, noPath, searchFails;
        public final Map<String, Integer> blocks = new TreeMap<>();
    }

    private static final Map<String, Counters> BY_MAP = new TreeMap<>();

    /** Game instance worlds are named after their map plus a counter: group them by the map part. */
    public static String key(World w) {
        if (w == null) return "?";
        String n = w.getName();
        return n.replaceAll("[-_]?\\d+$", "");
    }

    private static Counters of(World w) { return BY_MAP.computeIfAbsent(key(w), k -> new Counters()); }

    public static void navTick(World w) { of(w).navTicks++; }
    public static void searchFailed(World w) { of(w).searchFails++; }
    public static void noPath(World w) { of(w).noPath++; }
    public static void stuckFail(World w) { of(w).stuckFails++; }

    /**
     * A stuck episode (no progress for ~2 checks). Records the materials of the feet cell, the floor and the cell the
     * bot is pushing into so partial blocks (stairs, slabs, ...) can be told apart from plain walls.
     */
    public static void stuck(String bot, Location p, double dirX, double dirZ, String move, net.h2020.bots.ai.world.WorldView wv) {
        Counters c = of(p.getWorld());
        c.stuck++;
        World w = p.getWorld();
        int x = floor(p.getX()), y = floor(p.getY() + 0.01), z = floor(p.getZ());
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        int ax = x, az = z;
        if (len > 1e-3) { ax = floor(p.getX() + dirX / len * 0.7); az = floor(p.getZ() + dirZ / len * 0.7); }
        List<String> mats = new ArrayList<>();
        boolean partial = false;
        int[][] cells = {{x, y - 1, z}, {x, y, z}, {ax, y, az}, {ax, y + 1, az}, {ax, y - 1, az}, {x, y + 2, z}};
        for (int[] cell : cells) {
            Material m = w.getBlockAt(cell[0], cell[1], cell[2]).getType();
            if (partial(m)) {
                partial = true;
                c.blocks.merge(m.name(), 1, Integer::sum);
            }
            mats.add(m.name().toLowerCase());
        }
        if (partial) c.stuckPartial++;
        if (debug) {
            Block feet = w.getBlockAt(x, y, z);
            org.bukkit.Bukkit.getLogger().info(String.format(
                    "[navdebug] %s stuck %s at %.2f,%.2f,%.2f (%s) dir %.2f,%.2f floor=%s feet=%s:%d ahead=%s/%s/%s(below) head=%s",
                    bot, move, p.getX(), p.getY(), p.getZ(), key(w), dirX, dirZ, mats.get(0), mats.get(1), feet.getData(),
                    mats.get(2), mats.get(3), mats.get(4), mats.get(5))
                    + String.format(" | model: here %d/%s ahead %d,%d,%d below=%d:%.2f feet=%d:%.2f head=%d stand(y)=%.2f stand(y+1)=%.2f",
                    wv.shape(x, y, z), fmt(wv.stand(x, y, z)), ax, y, az, wv.shape(ax, y - 1, az), wv.height(ax, y - 1, az),
                    wv.shape(ax, y, az), wv.height(ax, y, az), wv.shape(ax, y + 1, az), wv.stand(ax, y, az), wv.stand(ax, y + 1, az)));
        }
    }

    private static String fmt(double d) { return Double.isNaN(d) ? "-" : String.format("%.2f", d); }

    private static boolean partial(Material m) {
        String n = m.name();
        return n.endsWith("_STAIRS") || n.contains("STEP") || n.contains("SLAB") || n.contains("FENCE") || n.equals("COBBLE_WALL")
                || n.equals("CARPET") || n.equals("SNOW") || n.equals("SOUL_SAND") || n.contains("CHEST") || n.equals("ENCHANTMENT_TABLE")
                || n.contains("PANE") || n.equals("IRON_FENCE") || n.equals("THIN_GLASS") || n.equals("LADDER") || n.contains("TRAP_DOOR")
                || n.equals("BED_BLOCK") || n.equals("SOIL") || n.equals("ENDER_PORTAL_FRAME") || n.equals("DAYLIGHT_DETECTOR")
                || n.equals("CAULDRON") || n.equals("HOPPER") || n.equals("ANVIL") || n.equals("BREWING_STAND") || n.equals("SKULL")
                || n.equals("FLOWER_POT") || n.equals("CAKE_BLOCK") || n.contains("DOOR");
    }

    public static List<String> report() {
        List<String> out = new ArrayList<>();
        long tt = 0, ts = 0, tp = 0, tf = 0, tn = 0, tsf = 0;
        for (Map.Entry<String, Counters> e : BY_MAP.entrySet()) {
            Counters c = e.getValue();
            tt += c.navTicks; ts += c.stuck; tp += c.stuckPartial; tf += c.stuckFails; tn += c.noPath; tsf += c.searchFails;
            out.add(line(e.getKey(), c.navTicks, c.stuck, c.stuckPartial, c.stuckFails, c.noPath, c.searchFails) + " " + c.blocks);
        }
        out.add(line("TOTAL", tt, ts, tp, tf, tn, tsf));
        return out;
    }

    private static String line(String k, long ticks, long stuck, long partial, long fails, long noPath, long searchFails) {
        double min = Math.max(1e-9, ticks / 1200.0);
        return String.format("%s: %.1f bot-min nav | stuck %d (%.2f/min, partial-block %d = %.2f/min) | stuckFail %d | noPath %d | searchFail %d (%.2f/min)",
                k, ticks / 1200.0, stuck, stuck / min, partial, partial / min, fails, noPath, searchFails, searchFails / min);
    }

    public static void reset() { BY_MAP.clear(); }

    private static int floor(double d) { int i = (int) d; return d < i ? i - 1 : i; }
}
