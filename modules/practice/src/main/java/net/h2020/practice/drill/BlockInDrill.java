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
    private static final List<Object[]> LEARNED = new ArrayList<Object[]>();

    public static void init(JavaPlugin plugin) {
        file = new File(plugin.getDataFolder(), "block-in-hole.yml");
        loadLearned();
    }

    private static void loadLearned() {
        LEARNED.clear();
        if (file == null || !file.isFile()) return;
        try {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
            for (String s : y.getStringList("cells")) {
                String[] p = s.split(",");
                if (p.length < 4) continue;
                LEARNED.add(new Object[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()),
                        Integer.parseInt(p[2].trim()), Material.valueOf(p[3].trim())});
            }
        } catch (Throwable ignored) { }
    }

    private static void saveLearned(Player by, long ms, Map<Long, Material> cells) {
        if (file == null) return;
        try {
            YamlConfiguration y = new YamlConfiguration();
            List<String> rows = new ArrayList<String>();
            for (Map.Entry<Long, Material> e : cells.entrySet()) {
                rows.add((unkeyX(e.getKey()) - BED_X) + "," + (unkeyY(e.getKey()) - BED_Y) + ","
                        + (unkeyZ(e.getKey()) - BED_Z) + "," + e.getValue());
            }
            y.set("by", by == null ? "?" : by.getName());
            y.set("ms", ms);
            y.set("cells", rows);
            if (file.getParentFile() != null && !file.getParentFile().isDirectory()) file.getParentFile().mkdirs();
            y.save(file);
        } catch (Throwable ignored) { }
        loadLearned();
    }

    /** The hole a person left, so a bot - or the next run - has the same shape to close. */
    public static List<Object[]> learnedHole() { return new ArrayList<Object[]>(LEARNED); }

    private PracticeArena arena;
    private Location spawn;
    /** Blocks this player has taken out of the defence: the hole they are making. */
    private final Map<UUID, Map<Long, Material>> broken = new HashMap<UUID, Map<Long, Material>>();
    private final Map<UUID, long[]> rows = new HashMap<UUID, long[]>();      // {start, sealed, wasted, best, done}
    private final Map<UUID, List<int[]>> traces = new HashMap<UUID, List<int[]>>();

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
            for (Long k : h.keySet()) {
                if (arena.world().getBlockAt(unkeyX(k), unkeyY(k), unkeyZ(k)).getType() == Material.AIR) {
                    closed = false;
                    break;
                }
            }
            if (!closed) continue;
            long[] r = row(p);
            long ms = r[0] > 0 ? System.currentTimeMillis() - r[0] : 0;
            r[4]++;
            if (r[3] == 0 || ms < r[3]) r[3] = ms;
            p.sendMessage(Messages.get("practice.blockindrill.sealed", "p1", ms, "p2", h.size()));
            p.playSound(p.getLocation(), Sound.LEVEL_UP, 0.7f, 1.6f);
            RunLog.record(p, 1, ms, h.size(), (int) r[2], h.size(), trace(p));
            // the hole this person just shut becomes the shape everybody else practises on
            saveLearned(p, ms, h);
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
        String learned = LEARNED.isEmpty() ? "" : ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "hole by "
                + ChatColor.WHITE + learnedBy();
        return ChatColor.AQUA + "Block in" + ChatColor.DARK_GRAY + " | " + ChatColor.WHITE + h.size()
                + ChatColor.GRAY + " broken" + ChatColor.DARK_GRAY + ", " + ChatColor.WHITE + (h.size() - open)
                + ChatColor.GRAY + "/" + h.size() + " sealed"
                + ChatColor.DARK_GRAY + " | " + ChatColor.YELLOW + "Best " + ChatColor.WHITE
                + (r[3] == 0 ? "-" : r[3] + "ms")
                + ChatColor.DARK_GRAY + " | " + ChatColor.GREEN + "Done " + ChatColor.WHITE + r[4] + learned;
    }

    private static String learnedBy() {
        if (file == null || !file.isFile()) return "-";
        try { return YamlConfiguration.loadConfiguration(file).getString("by", "-"); } catch (Throwable t) { return "-"; }
    }

    /** Is this cell part of the standing defence? */
    private boolean inDefence(int x, int y, int z) {
        if (Math.abs(x - BED_X) > 6 || Math.abs(z - BED_Z) > 6) return false;
        if (y < BED_Y || y > BED_Y + 3) return false;
        if (x >= BED_X && x <= BED_X + 1 && z == BED_Z && y == BED_Y) return false;   // the bed is not defence
        return true;
    }

    /** Absolute cells of the last hole a person made, for a bot brain to fill. */
    public List<int[]> holeCells() {
        List<int[]> out = new ArrayList<int[]>();
        for (Object[] c : LEARNED) {
            out.add(new int[]{BED_X + (Integer) c[0], BED_Y + (Integer) c[1], BED_Z + (Integer) c[2]});
        }
        return out;
    }

    /** Open the learned hole, so a bot has something to close. */
    public void openLearnedHole() {
        for (Object[] c : LEARNED) {
            arena.world().getBlockAt(BED_X + (Integer) c[0], BED_Y + (Integer) c[1], BED_Z + (Integer) c[2])
                    .setType(Material.AIR, false);
        }
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
