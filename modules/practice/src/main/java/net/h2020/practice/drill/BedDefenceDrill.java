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
    /** Cells of the level just left, kept for a few seconds after a switch: a click already in flight when the
     *  run was recorded lands after the clear, and a block that lands in a cell no level mentions can hide the
     *  face a later cell needs. Measured: level 5 stood at 11 of 12 with 3,65,-1 (a level 4 cell) in the way. */
    private final java.util.Set<Long> leaving = new java.util.HashSet<Long>();
    /** When the current level's leaving set stops being swept (0 = never armed). */
    private long leavingUntilTick = 0;
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
        p.getInventory().setItem(2, new ItemStack(Material.WOOD, 64));          // oak planks: the wood layer
        p.getInventory().setItem(8, new ItemStack(Material.WOOD_PICKAXE));
    }

    private short colour() { return 14; }                                          // red wool, easy to tell from sand

    /**
     * The defence for a level, taken from the template store: the nth shape in the file, so a shape added in game is a
     * level and one removed is gone. Cells are relative to the bed's foot block.
     */
    private void setLevel(int lvl) {
        // the level being left: its cells that this one does not want have to go with it. clearTarget only knows
        // the shape it was called for, so a block that was still in flight when the run was recorded lands in a
        // cell no later level mentions and stays there for the rest of the session. Measured: level 5 stood at
        // 11 of 12 with 3,65,-1, 2,65,-2 and 3,65,1 in ender stone - all level 4 cells - and the leftover at
        // 3,65,-1 hid every remaining face of the one cell level 5 still wanted, so the level could not be finished.
        leaving.clear();
        leaving.addAll(target.keySet());
        this.level = Math.max(1, Math.min(BedDefenceTemplates.count(), lvl));
        this.leavingUntilTick = System.currentTimeMillis() / 50 + 60;   // three seconds of sweeping
        target.clear();
        for (Object[] c : BedDefenceTemplates.at(this.level)) {
            int dx = (Integer) c[0], dy = (Integer) c[1], dz = (Integer) c[2];
            Material m = (Material) c[3];
            target.put(key(BED_X + dx, BED_Y + dy, BED_Z + dz), m);
        }
        // A level that asks for a material has to start from an empty cell. The defence is cleared when a run is
        // recorded, but a block already in flight from the bot lands after that clear, and a cell left holding the
        // previous level's material can never be filled: the drill only counts the block it asked for, and the brain
        // has no reason to take a wrong one out. Measured: level 3 held 25 of 26 with 2,65,1 in wool where the
        // template asks for wood, and the run could not advance.
        for (Map.Entry<Long, Material> e : target.entrySet()) {
            int[] c = unkey(e.getKey());
            Block b = arena.world().getBlockAt(c[0], c[1], c[2]);
            if (b.getType() != Material.AIR && b.getType() != e.getValue()) b.setType(Material.AIR, false);
        }
        sweepLeaving();
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
    /** Clear the level just left, for the few seconds in which last tick's clicks can still land. */
    private void sweepLeaving() {
        if (leaving.isEmpty()) return;
        if (leavingUntilTick > 0 && System.currentTimeMillis() / 50 > leavingUntilTick) { leaving.clear(); leavingUntilTick = 0; return; }
        for (Long k : leaving) {
            if (target.containsKey(k)) continue;
            int[] c = unkey(k);
            Block b = arena.world().getBlockAt(c[0], c[1], c[2]);
            if (b.getType() != Material.AIR) b.setType(Material.AIR, false);
        }
    }

    private boolean ctxLevelCheck(PracticeGame g) {
        sweepLeaving();
        for (Player p : g.alivePlayers()) {
            if (g.isFollowing(p)) continue;
            long[] r = row(p);
            boolean anyPlaced = false, complete = true;
            for (Map.Entry<Long, Material> e : target.entrySet()) {
                int[] c = unkey(e.getKey());
                Material m = arena.world().getBlockAt(c[0], c[1], c[2]).getType();
                if (m == e.getValue()) anyPlaced = true;
                else {
                    complete = false;
                    // A block of some other material in a target cell cannot be built over: the drill wants this
                    // one, and nothing takes the wrong one out. Clearing it here, every tick, is what makes the
                    // stray that lands just after a level switch harmless - the level-start clear cannot see a
                    // block that is still in flight, and the brain reads a wrong block as "already filled" and
                    // never touches the cell again. Measured: level 4 at 33 of 35 with 2,65,-1 in wool where the
                    // template asks for ender stone, sweeping without placing for the rest of the session.
                    if (m != Material.AIR) arena.world().getBlockAt(c[0], c[1], c[2]).setType(Material.AIR, false);
                }
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
        return ChatColor.AQUA + "Defence " + ChatColor.WHITE + level + ChatColor.DARK_GRAY
                + "/" + BedDefenceTemplates.count()
                + ChatColor.DARK_GRAY + " | " + ChatColor.WHITE + target.size() + ChatColor.GRAY + " blocks to place"
                + ChatColor.DARK_GRAY + " | " + ChatColor.YELLOW + "Best " + ChatColor.WHITE
                + (r[3] == 0 ? "-" : r[3] + "ms")
                + ChatColor.DARK_GRAY + " | " + ChatColor.GREEN + "Done " + ChatColor.WHITE + r[4];
    }

    @Override
    public void onQuit(PracticeGame g, Player p) {
        run.remove(p.getUniqueId());
        trace.remove(p.getUniqueId());
    }

    @Override
    public void cleanup(PracticeGame g) {
        run.clear();
        trace.clear();
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

    @Override public String level(Player p) { return String.valueOf(level); }

    /** The level as a number, for whoever has to hand a bot the current shape. */
    public int levelNow() { return level; }
    @Override public String levels() { return String.valueOf(BedDefenceTemplates.count()); }
    @Override public String blocksToPlace(Player p) { return String.valueOf(target.size()); }
    @Override public String best(Player p) { long b = row(p)[3]; return b == 0 ? "-" : b + "ms"; }
    @Override public String completed(Player p) { return String.valueOf(row(p)[4]); }
    @Override public long bestMillis(Player p) { return row(p)[3]; }

    /**
     * One debug line: the cells being asked for, and whether each is standing.
     *
     * <p>Every cell is printed. The line used to stop at 160 characters, which cut the list after the first fourteen
     * and left the rest of a level invisible: "filled 12/26" with no way to see which fourteen were refusing.
     */
    public String debugLine() {
        StringBuilder sb = new StringBuilder("level " + level + " of " + BedDefenceTemplates.count() + ", cells:");
        StringBuilder missing = new StringBuilder();
        int filled = 0;
        for (Map.Entry<Long, Material> e : target.entrySet()) {
            int[] c = unkey(e.getKey());
            Material now = arena.world().getBlockAt(c[0], c[1], c[2]).getType();
            boolean ok = now == e.getValue();
            if (ok) filled++;
            else missing.append(' ').append(c[0]).append(',').append(c[1]).append(',').append(c[2])
                    .append(now == Material.AIR ? "" : "(" + now.name() + ")");
            sb.append(' ').append(c[0]).append(',').append(c[1]).append(',').append(c[2])
                    .append('=').append(ok ? "ok" : now.name());
        }
        sb.append("  filled ").append(filled).append('/').append(target.size());
        if (missing.length() > 0) sb.append("  missing:").append(missing);
        return sb.toString();
    }

    public int bedX() { return BED_X; }
    public int bedY() { return BED_Y; }
    public int bedZ() { return BED_Z; }

    /**
     * The cells a bot has to fill, for a bot brain handed this drill: x, y, z, and the id of the material the shape
     * wants there. The material is part of the cell, not a property of the drill - level three is wood with wool
     * corners and level four is ender stone with wool trim, so a brain that only knows "a block" fills the shape with
     * whatever it is holding and the drill counts none of it. Measured: level three sat at 10 of 26 with every
     * remaining cell filled in wool where the template asks for wood.
     */
    public List<int[]> targetCells() {
        List<int[]> out = new ArrayList<int[]>();
        for (Map.Entry<Long, Material> e : target.entrySet()) {
            int[] c = unkey(e.getKey());
            out.add(new int[]{c[0], c[1], c[2], e.getValue().getId()});
        }
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
