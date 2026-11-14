package net.h2020.practice.drill;

import net.h2020.practice.PracticeArena;
import net.h2020.practice.PracticeGame;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bed defence building, against the clock.
 *
 * <p>You are handed the blocks, shown the defence on your left as a full-size model floating beside your own bed, and
 * the clock starts when your first block lands. Match the model and the run is recorded: milliseconds, blocks used and
 * blocks wasted (anything you placed that is not part of the defence).
 *
 * <p>Every finished run is appended to {@code bed-defence-runs.jsonl} with a {@code kind} field, so a person's times and
 * a bot's times sit in one file and stay distinguishable - the point of the drill is comparing the two, and the bots
 * run the same arena through their own brain.
 *
 * <p>The defence grows with each completion: a front row, then a full ring, then the roof, then a second layer. The
 * model is always rebuilt, so the thing you copy is the thing being timed.
 */
public final class BedDefenceDrill implements Drill {

    private static final int BED_X = 0, BED_Z = 0, BASE_Y = 64;
    /**
     * The bed sits one above the floor. Flush with it, the ring around the bed is the platform itself - every target
     * cell already sandstone - and no amount of building can complete the run. Measured: 574 placement sweeps, zero
     * completions, four cells permanently "to place".
     */
    private static final int BED_Y = BASE_Y + 1;
    /** The model floats this many blocks to the player's left, on a glass shelf. */
    private static final int MODEL_DX = -11;

    private PracticeArena arena;
    private Location spawn;
    private final java.util.Map<UUID, long[]> run = new HashMap<UUID, long[]>();   // {startTick, placed, wasted, best, runs}
    private int level = 1;
    private final Map<Long, Material> target = new LinkedHashMap<Long, Material>();
    private final List<int[]> modelCells = new ArrayList<int[]>();
    /** Per player, the run's placements as {ms since the first block, x, y, z}: the run's replay. */
    private final Map<UUID, List<int[]>> trace = new HashMap<UUID, List<int[]>>();

    @Override public String id() { return "beddefence"; }
    @Override public String display() { return "Bed Defence"; }

    @Override public String hint() {
        return "copy the defence on your left - the clock starts on your first block";
    }

    @Override
    public void build(PracticeArena a) {
        this.arena = a;
        // the platform has to contain the spot people stand on: a spawn outside it drops the body into the void,
        // which is exactly what the first run did (bots at y=39 before a single block was placed)
        a.platform(BED_X, BASE_Y, BED_Z, 7, Material.SANDSTONE);
        a.platform(BED_X, BASE_Y - 1, BED_Z, 7, Material.SANDSTONE);
        // the shelf the model stands on, far enough left that it cannot be reached by accident
        a.box(BED_X + MODEL_DX - 3, BASE_Y, BED_Z - 3, BED_X + MODEL_DX + 3, BASE_Y, BED_Z + 3, Material.GLASS);
        this.spawn = a.at(BED_X + 0.5, BASE_Y + 1, BED_Z + 5.5, 180f);
        setLevel(1);
    }

    @Override public Location spawn() { return spawn; }
    @Override public Location spectatorSpawn() { return arena.at(BED_X + 0.5, BASE_Y + 8, BED_Z + 12.5, 180f); }

    @Override
    public void kit(Player p) {
        p.getInventory().setItem(0, new ItemStack(Material.WOOL, 64, colour()));
        p.getInventory().setItem(1, new ItemStack(Material.ENDER_STONE, 64));   // the second layer's material
        p.getInventory().setItem(8, new ItemStack(Material.WOOD_PICKAXE));
    }

    private short colour() { return 14; }                                          // red wool, easy to tell from sand

    /**
     * The defence for a level: a front row, a full ring, a roof, then a second layer. Cells are the ring around the two
     * bed blocks; the bed itself is never part of the target.
     */
    private void setLevel(int lvl) {
        this.level = Math.max(1, Math.min(4, lvl));
        target.clear();
        int x0 = BED_X, z0 = BED_Z;
        // the ring around a bed occupying (x0,z0) and (x0+1,z0), at bed level
        int[][] ring = {
                {-1, 0}, {-1, 1}, {0, 1}, {1, 1}, {2, 1}, {2, 0}, {2, -1}, {1, -1}, {0, -1}, {-1, -1}
        };
        for (int[] c : ring) target.put(key(x0 + c[0], BED_Y, z0 + c[1]), Material.WOOL);
        if (level == 1) {
            // front row only: the four cells closest to the player, so the first attempt is a shape and not a wall
            target.keySet().retainAll(java.util.Arrays.asList(
                    key(x0 - 1, BED_Y, z0 + 1), key(x0, BED_Y, z0 + 1), key(x0 + 1, BED_Y, z0 + 1), key(x0 + 2, BED_Y, z0 + 1)));
        } else if (level == 2) {
            target.keySet().retainAll(java.util.Arrays.asList(
                    key(x0 - 1, BED_Y, z0 + 1), key(x0, BED_Y, z0 + 1), key(x0 + 1, BED_Y, z0 + 1), key(x0 + 2, BED_Y, z0 + 1),
                    key(x0 - 1, BED_Y, z0), key(x0 + 2, BED_Y, z0), key(x0 - 1, BED_Y, z0 - 1), key(x0 + 2, BED_Y, z0 - 1)));
        }
        if (level >= 3) {
            // the roof: the same footprint one block up
            for (int dx = -1; dx <= 2; dx++) for (int dz = -1; dz <= 1; dz++) {
                target.put(key(x0 + dx, BED_Y + 1, z0 + dz), Material.WOOL);
            }
            target.remove(key(x0, BED_Y + 1, z0));
            target.remove(key(x0 + 1, BED_Y + 1, z0));
        }
        if (level == 4) {
            // second layer of end stone under the wool ring, the way a real defence is built
            for (int dx = -2; dx <= 3; dx++) for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) < 3 && Math.abs(dz) < 2) target.put(key(x0 + dx, BASE_Y - 1, z0 + dz), Material.ENDER_STONE);
            }
        }
        buildModel();
        buildBed();
    }

    private void buildBed() {
        Block foot = arena.world().getBlockAt(BED_X, BED_Y, BED_Z);
        Block head = arena.world().getBlockAt(BED_X + 1, BED_Y, BED_Z);
        // Bed data: the low two bits are the direction the head sits in from the foot (0 south, 1 west, 2 north,
        // 3 east) and 0x8 marks the head block. The head is one block east, so the foot is 3 and the head is 3|8.
        // Setting the foot north (2) with the head to the east (10) is what made the two halves point different ways.
        foot.setType(Material.BED_BLOCK, false);
        foot.setData((byte) 3, false);
        head.setType(Material.BED_BLOCK, false);
        head.setData((byte) 11, false);
    }

    /** The copy on the left: the same cells, floating on the glass shelf, always current for this level. */
    private void buildModel() {
        for (int[] c : modelCells) arena.world().getBlockAt(c[0], c[1], c[2]).setType(Material.AIR, false);
        modelCells.clear();
        for (Map.Entry<Long, Material> e : target.entrySet()) {
            int[] c = unkey(e.getKey());
            int mx = c[0] + MODEL_DX, my = c[1], mz = c[2];
            arena.world().getBlockAt(mx, my, mz).setType(e.getValue(), false);
            modelCells.add(new int[]{mx, my, mz});
        }
        // the bed of the model too, so the shape reads as a bed and not as a box
        Block mFoot = arena.world().getBlockAt(BED_X + MODEL_DX, BED_Y, BED_Z);
        Block mHead = arena.world().getBlockAt(BED_X + MODEL_DX + 1, BED_Y, BED_Z);
        mFoot.setType(Material.BED_BLOCK, false);
        mFoot.setData((byte) 3, false);
        mHead.setType(Material.BED_BLOCK, false);
        mHead.setData((byte) 11, false);
    }

    @Override public void onJoin(PracticeGame g, Player p) { row(p); }

    @Override
    public void tick(PracticeGame g) {
        if (ctxLevelCheck(g)) return;
    }

    /** One pass: has every target cell the right material? Then the run is done. */
    private boolean ctxLevelCheck(PracticeGame g) {
        for (Player p : g.alivePlayers()) {
            if (g.isFollowing(p)) continue;
            long[] r = row(p);
            boolean anyPlaced = false, complete = true;
            for (Map.Entry<Long, Material> e : target.entrySet()) {
                int[] c = unkey(e.getKey());
                Material m = arena.world().getBlockAt(c[0], c[1], c[2]).getType();
                if (m == e.getValue()) anyPlaced = true;
                else complete = false;
            }
            if (complete) {
                long ms = r[0] > 0 ? (System.currentTimeMillis() - r[0]) : 0;
                r[4]++;
                if (r[3] == 0 || ms < r[3]) r[3] = ms;
                p.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "Defence up" + ChatColor.RESET + ChatColor.GRAY
                        + "  " + ChatColor.WHITE + ms + " ms" + ChatColor.GRAY + ", "
                        + ChatColor.WHITE + r[1] + ChatColor.GRAY + " blocks"
                        + (r[2] > 0 ? ChatColor.GRAY + ", " + ChatColor.RED + r[2] + " wasted" : ""));
                p.playSound(p.getLocation(), Sound.LEVEL_UP, 0.7f, 1.6f);
                RunLog.record(p, level, ms, (int) r[1], (int) r[2], target.size(), traceRow(p));
                clearTarget();
                r[0] = 0; r[1] = 0; r[2] = 0; traceRow(p).clear();
                setLevel(level + 1);
                g.reset(p);
                return true;
            }
            // the clock starts on the first correct block
            if (r[0] == 0 && anyPlaced) r[0] = System.currentTimeMillis();
        }
        return false;
    }

    private void clearTarget() {
        for (Long k : target.keySet()) {
            int[] c = unkey(k);
            arena.world().getBlockAt(c[0], c[1], c[2]).setType(Material.AIR, false);
        }
    }

    @Override
    public void onPlace(PracticeGame g, Player p, Block b) {
        long[] r = row(p);
        if (target.containsKey(key(b.getX(), b.getY(), b.getZ()))) {
            r[1]++;
            long t0 = r[0] > 0 ? r[0] : System.currentTimeMillis();
            traceRow(p).add(new int[]{(int) (System.currentTimeMillis() - t0), b.getX(), b.getY(), b.getZ()});
        } else if (b.getY() >= BED_Y - 1 && b.getY() <= BED_Y + 2
                && Math.abs(b.getX() - BED_X) <= 6 && Math.abs(b.getZ() - BED_Z) <= 6) {
            r[2]++;                                  // in the work area but not part of the defence
        }
    }

    @Override
    public String hud(PracticeGame g, Player p) {
        long[] r = row(p);
        return ChatColor.AQUA + "Defence " + ChatColor.WHITE + level + ChatColor.DARK_GRAY + "/4"
                + ChatColor.DARK_GRAY + " | " + ChatColor.WHITE + target.size() + ChatColor.GRAY + " blocks to place"
                + ChatColor.DARK_GRAY + " | " + ChatColor.YELLOW + "Best " + ChatColor.WHITE
                + (r[3] == 0 ? "-" : r[3] + "ms")
                + ChatColor.DARK_GRAY + " | " + ChatColor.GREEN + "Done " + ChatColor.WHITE + r[4];
    }

    @Override
    public List<String> stats(PracticeGame g, Player p) {
        long[] r = row(p);
        List<String> out = new ArrayList<String>();
        out.add("defences built " + r[4]);
        out.add("best time " + (r[3] == 0 ? "-" : r[3] + " ms"));
        out.add("current level " + level + " (" + target.size() + " blocks)");
        return out;
    }

    /** The cells a bot has to fill, for a bot brain handed this drill. */
    public List<int[]> targetCells() {
        List<int[]> out = new ArrayList<int[]>();
        for (Long k : target.keySet()) out.add(unkey(k));
        return out;
    }

    public Material targetMaterial() { return Material.WOOL; }

    // ---- cell keys ----
    private static long key(int x, int y, int z) { return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF); }
    private static int[] unkey(long k) {
        int x = (int) (k >> 38); if (x > 0x1FFFFFF) x -= 0x4000000;
        int y = (int) ((k >> 26) & 0xFFF);
        int z = (int) (k & 0x3FFFFFF); if (z > 0x1FFFFFF) z -= 0x4000000;
        return new int[]{x, y, z};
    }

    private List<int[]> traceRow(Player p) {
        List<int[]> t = trace.get(p.getUniqueId());
        if (t == null) { t = new ArrayList<int[]>(); trace.put(p.getUniqueId(), t); }
        return t;
    }

    private long[] row(Player p) {
        long[] r = run.get(p.getUniqueId());
        if (r == null) { r = new long[5]; run.put(p.getUniqueId(), r); }
        return r;
    }
}
