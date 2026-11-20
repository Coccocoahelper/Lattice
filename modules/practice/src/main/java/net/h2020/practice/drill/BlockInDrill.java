package net.h2020.practice.drill;

import net.h2020.api.core.Messages;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import net.h2020.practice.PracticeArena;
import net.h2020.practice.PracticeGame;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Block-in: break into your own defence, then shut the hole behind you.
 *
 * <p>The defence stands whole - the second shape from the bed-defence trainer, untouched. Nothing is cut open for
 * you: you break in where you like, and closing that same hole is the run. Which blocks a good block-in goes through
 * is a judgement call, so this class does not make it.
 *
 * <p>The first player to close a hole defines it: the cells they broke and filled are written to
 * {@code plugins/Lattice/block-in-hole.yml}. Later runs and the bots start from that shape, so the drill converges on
 * how a person really does it instead of on a guess made here.
 */
public final class BlockInDrill implements Drill {

    private static final int BED_X = 0, BED_Y = 65, BED_Z = 0;
    /** The defence the hole is made in: the trainer's own shape 2. */
    private static final String DEFENCE = "2";

    private static File file;
    /** The hole as a person left it: cells relative to the bed, with the material they were made of. */
    private static final List<List<Object[]>> HOLES = new ArrayList<List<Object[]>>();

    public static void init(JavaPlugin plugin) {
        // beside the defence templates, not next to the plugin jar: the practice plugin's own data folder is
        // plugins/LatticePractice while everything this server owns lives in plugins/Lattice
        file = new File(plugin.getDataFolder().getParentFile(), "Lattice/block-in-hole.yml");
        loadLearned();
    }

    private static void loadLearned() {
        HOLES.clear();
        if (file == null || !file.isFile()) return;
        try {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
            List<Map<?, ?>> list = y.getMapList("holes");
            for (Map<?, ?> m : list) {
                List<Object[]> cells = new ArrayList<Object[]>();
                Object raw = m.get("cells");
                if (raw instanceof List) {
                    for (Object o : (List<?>) raw) {
                        String[] p = String.valueOf(o).split(",");
                        if (p.length < 4) continue;
                        cells.add(new Object[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()),
                                Integer.parseInt(p[2].trim()), Material.valueOf(p[3].trim())});
                    }
                }
                if (!cells.isEmpty()) HOLES.add(cells);
            }
            java.util.logging.Logger.getLogger("LatticePractice").info("[blockin] learned " + HOLES.size()
                    + " hole(s) from " + file.getName());
        } catch (Throwable t) {
            java.util.logging.Logger.getLogger("LatticePractice").warning("[blockin] could not read " + file + ": " + t);
        }
    }

    /** A hole a person closed. Kept, appended to the ones already known - there is more than one place to block in. */
    private static void saveLearned(Player by, long ms, Map<Long, Material> cells) {
        if (file == null) return;
        List<String> rows = new ArrayList<String>();
        for (Map.Entry<Long, Material> e : cells.entrySet()) {
            rows.add((unkeyX(e.getKey()) - BED_X) + "," + (unkeyY(e.getKey()) - BED_Y) + ","
                    + (unkeyZ(e.getKey()) - BED_Z) + "," + e.getValue());
        }
        java.util.Set<String> known = new java.util.HashSet<String>();
        for (List<Object[]> h : HOLES) {
            java.util.List<String> k = new ArrayList<String>();
            for (Object[] c : h) k.add(c[0] + "," + c[1] + "," + c[2]);
            java.util.Collections.sort(k);
            known.add(k.toString());
        }
        java.util.List<String> mine = new ArrayList<String>();
        for (Object[] c : new ArrayList<Object[]>(rowsToCells(rows))) mine.add(c[0] + "," + c[1] + "," + c[2]);
        java.util.Collections.sort(mine);
        try {
            YamlConfiguration y = new YamlConfiguration();
            List<Map<?, ?>> all = new ArrayList<Map<?, ?>>();
            if (file.isFile()) all.addAll(YamlConfiguration.loadConfiguration(file).getMapList("holes"));
            if (!known.contains(mine.toString())) {
                Map<String, Object> entry = new LinkedHashMap<String, Object>();
                entry.put("by", by == null ? "?" : by.getName());
                entry.put("ms", ms);
                entry.put("cells", rows);
                all.add(entry);
            }
            y.set("holes", all);
            if (file.getParentFile() != null && !file.getParentFile().isDirectory()) file.getParentFile().mkdirs();
            y.save(file);
        } catch (Throwable ignored) { }
        loadLearned();
    }

    private static List<Object[]> rowsToCells(List<String> rows) {
        List<Object[]> out = new ArrayList<Object[]>();
        for (String s : rows) {
            String[] p = s.split(",");
            if (p.length < 4) continue;
            out.add(new Object[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()),
                    Integer.parseInt(p[2].trim()), Material.valueOf(p[3].trim())});
        }
        return out;
    }

    /** Every hole a person has closed, so the bots learn all of them rather than one. */
    public static List<List<Object[]>> learnedHoles() { return new ArrayList<List<Object[]>>(HOLES); }

    private PracticeArena arena;
    private Location spawn;
    /** Blocks this player has taken out of the defence: the hole they are making. */
    private final Map<UUID, Map<Long, Material>> broken = new HashMap<UUID, Map<Long, Material>>();
    private final Map<UUID, long[]> rows = new HashMap<UUID, long[]>();      // {start, sealed, wasted, best, done}
    private final Map<UUID, List<int[]>> traces = new HashMap<UUID, List<int[]>>();
    /** One "wrong material" warning per attempt, not one per tick. */
    private final java.util.Set<UUID> r0Notified = new java.util.HashSet<UUID>();

    @Override public String id() { return "blockin"; }
    @Override public String display() { return "Block In"; }

    @Override public String hint() {
        return "break into the defence, then seal the hole you made before he follows you through it";
    }

    @Override
    public void build(PracticeArena a) {
        this.arena = a;
        a.platform(BED_X, BED_Y - 1, BED_Z, 6, Material.SANDSTONE);
        rebuildDefence();
        this.spawn = a.at(BED_X + 0.5, BED_Y, BED_Z - 4.5, 0f);      // outside, on the attacking side
    }

    /** The trainer's defence 2, whole. No holes until somebody makes one. */
    private void rebuildDefence() {
        for (int x = BED_X - 3; x <= BED_X + 5; x++) {
            for (int z = BED_Z - 3; z <= BED_Z + 3; z++) {
                for (int dy = 0; dy <= 3; dy++) {
                    if (x >= BED_X && x <= BED_X + 1 && z == BED_Z && dy == 0) continue;   // the bed itself
                    arena.world().getBlockAt(x, BED_Y + dy, z).setType(Material.AIR, false);
                }
            }
        }
        List<Object[]> cells = BedDefenceTemplates.get(DEFENCE);
        if (cells == null || cells.isEmpty()) cells = BedDefenceTemplates.at(2);
        for (Object[] c : cells) {
            arena.world().getBlockAt(BED_X + (Integer) c[0], BED_Y + (Integer) c[1], BED_Z + (Integer) c[2])
                    .setType((Material) c[3], false);
        }
        placeBed();
    }

    private void placeBed() {
        org.bukkit.block.Block foot = arena.world().getBlockAt(BED_X, BED_Y, BED_Z);
        foot.setType(Material.BED_BLOCK, false);
        foot.setData((byte) 2, false);
        org.bukkit.block.Block head = arena.world().getBlockAt(BED_X + 1, BED_Y, BED_Z);
        head.setType(Material.BED_BLOCK, false);
        head.setData((byte) 10, false);
    }

    @Override public Location spawn() { return spawn; }
    @Override public Location spectatorSpawn() { return arena.at(BED_X + 0.5, BED_Y + 5, BED_Z + 7.5, 0f); }

    @Override
    public void kit(Player p) {
        p.getInventory().setItem(0, new ItemStack(Material.WOOL, 32));
        p.getInventory().setItem(1, new ItemStack(Material.WOOD, 32));
        p.getInventory().setItem(2, new ItemStack(Material.IRON_PICKAXE));
    }

    @Override
    public void start(PracticeGame g) { rebuildDefence(); }

    @Override public void onJoin(PracticeGame g, Player p) { row(p); hole(p); }

    /** A block out of the defence opens the hole - and starts the clock. */
    @Override
    public void onBreak(PracticeGame g, Player p, Block b) {
        if (!inDefence(b.getX(), b.getY(), b.getZ())) return;
        hole(p).put(key(b.getX(), b.getY(), b.getZ()), b.getType());
        long[] r = row(p);
        if (r[0] == 0) r[0] = System.currentTimeMillis();
    }

    @Override
    public void onPlace(PracticeGame g, Player p, Block b) {
        long[] r = row(p);
        Map<Long, Material> h = hole(p);
        if (!h.containsKey(key(b.getX(), b.getY(), b.getZ()))) {
            if (inDefence(b.getX(), b.getY(), b.getZ())) r[2]++;   // somewhere other than the hole
            return;
        }
        r[1]++;
        long t0 = r[0] > 0 ? r[0] : System.currentTimeMillis();
        trace(p).add(new int[]{(int) (System.currentTimeMillis() - t0), b.getX(), b.getY(), b.getZ()});
    }

    @Override
    public void tick(PracticeGame g) {
        for (Player p : g.alivePlayers()) {
            if (g.isFollowing(p)) continue;
            Map<Long, Material> h = hole(p);
            if (h.isEmpty()) continue;                             // nothing broken: nothing to close
            boolean closed = true;
            int wrong = 0;
            for (java.util.Map.Entry<Long, Material> e : h.entrySet()) {
                Material now = arena.world().getBlockAt(unkeyX(e.getKey()), unkeyY(e.getKey()),
                        unkeyZ(e.getKey())).getType();
                if (now == Material.AIR) { closed = false; break; }
                // the material matters: a hole filled with whatever was in the hand is not the defence restored
                if (e.getValue() != null && now != e.getValue()) wrong++;
            }
            if (closed && wrong > 0) {
                // sealed, but with the wrong blocks: report it and let them fix it rather than scoring it
                if (r0Notified.add(p.getUniqueId())) {
                    p.sendMessage(ChatColor.RED + "" + wrong + " of " + h.size()
                            + " blocks are the wrong material - put back what you took out");
                }
                continue;
            }
            r0Notified.remove(p.getUniqueId());
            if (!closed) continue;
            long[] r = row(p);
            long ms = r[0] > 0 ? System.currentTimeMillis() - r[0] : 0;
            r[4]++;
            if (r[3] == 0 || ms < r[3]) r[3] = ms;
            p.sendMessage(Messages.get("practice.blockindrill.sealed", "p1", ms, "p2", h.size()));
            p.playSound(p.getLocation(), Sound.LEVEL_UP, 0.7f, 1.6f);
            RunLog.record(p, 1, ms, h.size(), (int) r[2], h.size(), trace(p));
            // The hole this person just shut becomes the shape everybody else practises on - but only a person's.
            // A bot runs this drill too, and writing its hole into the reference defeats the point of the file, which
            // exists to learn how a human does it.
            if (!net.h2020.practice.PracticeModule.isPracticeBot(p)) saveLearned(p, ms, h);
            else net.h2020.api.stats.CombatStats.inc("practice.blockin.bot_solution");
            h.clear();
            r[0] = 0; r[1] = 0; r[2] = 0;
            trace(p).clear();
            rebuildDefence();
            g.reset(p);
            break;
        }
    }

    @Override
    public String hud(PracticeGame g, Player p) {
        long[] r = row(p);
        Map<Long, Material> h = hole(p);
        int open = 0;
        for (Long k : h.keySet()) {
            if (arena.world().getBlockAt(unkeyX(k), unkeyY(k), unkeyZ(k)).getType() == Material.AIR) open++;
        }
        String learned = HOLES.isEmpty() ? "" : ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "holes learned "
                + ChatColor.WHITE + HOLES.size();
        return ChatColor.AQUA + "Block in" + ChatColor.DARK_GRAY + " | " + ChatColor.WHITE + h.size()
                + ChatColor.GRAY + " broken" + ChatColor.DARK_GRAY + ", " + ChatColor.WHITE + (h.size() - open)
                + ChatColor.GRAY + "/" + h.size() + " sealed"
                + ChatColor.DARK_GRAY + " | " + ChatColor.YELLOW + "Best " + ChatColor.WHITE
                + (r[3] == 0 ? "-" : r[3] + "ms")
                + ChatColor.DARK_GRAY + " | " + ChatColor.GREEN + "Done " + ChatColor.WHITE + r[4] + learned;
    }

    /** Is this cell part of the standing defence? */
    private boolean inDefence(int x, int y, int z) {
        if (Math.abs(x - BED_X) > 6 || Math.abs(z - BED_Z) > 6) return false;
        if (y < BED_Y || y > BED_Y + 3) return false;
        if (x >= BED_X && x <= BED_X + 1 && z == BED_Z && y == BED_Y) return false;   // the bed is not defence
        return true;
    }

    @Override
    public void onQuit(PracticeGame g, Player p) {
        broken.remove(p.getUniqueId());
        rows.remove(p.getUniqueId());
        traces.remove(p.getUniqueId());
        r0Notified.remove(p.getUniqueId());
    }

    @Override
    public void cleanup(PracticeGame g) {
        broken.clear();
        rows.clear();
        traces.clear();
        r0Notified.clear();
    }

    /** Every learned hole in absolute cells, for a bot to open and close itself. */
    public List<int[][]> holeSets() {
        List<int[][]> out = new ArrayList<int[][]>();
        for (List<Object[]> h : HOLES) {
            int[][] cells = new int[h.size()][];
            for (int i = 0; i < h.size(); i++) {
                Object[] c = h.get(i);
                cells[i] = new int[]{BED_X + (Integer) c[0], BED_Y + (Integer) c[1], BED_Z + (Integer) c[2]};
            }
            out.add(cells);
        }
        return out;
    }

    public int bedX() { return BED_X; }
    public int bedY() { return BED_Y; }
    public int bedZ() { return BED_Z; }

    private Map<Long, Material> hole(Player p) {
        Map<Long, Material> h = broken.get(p.getUniqueId());
        if (h == null) { h = new LinkedHashMap<Long, Material>(); broken.put(p.getUniqueId(), h); }
        return h;
    }

    private long[] row(Player p) {
        long[] r = rows.get(p.getUniqueId());
        if (r == null) { r = new long[5]; rows.put(p.getUniqueId(), r); }
        return r;
    }

    private List<int[]> trace(Player p) {
        List<int[]> t = traces.get(p.getUniqueId());
        if (t == null) { t = new ArrayList<int[]>(); traces.put(p.getUniqueId(), t); }
        return t;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }
    private static int unkeyX(long k) { return (int) (k >> 38); }
    private static int unkeyY(long k) { return (int) ((k >> 26) & 0xFFF); }
    private static int unkeyZ(long k) { return (int) (k & 0x3FFFFFF); }

    @Override public String level(Player p) { return "1"; }
    @Override public String levels() { return "1"; }
    @Override public String blocksToPlace(Player p) { return String.valueOf(hole(p).size()); }
    @Override public String best(Player p) { long[] r = row(p); return r[3] == 0 ? "-" : r[3] + "ms"; }
    @Override public String completed(Player p) { return String.valueOf(row(p)[4]); }
    @Override public long bestMillis(Player p) { return row(p)[3]; }
}
