package net.h2020.practice.drill;

import net.h2020.api.core.Messages;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import net.h2020.practice.PracticeArena;
import net.h2020.practice.PracticeGame;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Block-in: seal the way in, from inside the defence.
 *
 * <p>This is the move as it happens in a game, not a box built in a field. A two-layer defence is already standing
 * around the bed with an edge block gone - the hole somebody came through, or the one you broke to get in - and the
 * job is to put blocks back into that hole before the attacker follows you through it. Breaking an edge block and
 * blocking in is the whole point: the gap is the drill.
 *
 * <p>The clock starts on your first block in a gap and stops when every gap is shut. Levels widen the hole rather
 * than adding blocks for their own sake: one cell, then two deep, then a second side opened as well.
 */
public final class BlockInDrill implements Drill {

    private static final int BED_X = 0, BED_Y = 65, BED_Z = 0;
    private static final int LEVELS = 5;

    /**
     * The holes, per level, as offsets from the bed's foot block. 0,0,-1 is the inner layer in front of the bed and
     * 0,0,-2 the outer layer behind it, so the first two levels are the same hole opened one layer at a time.
     */
    private static final int[][][] GAPS = {
            {{0, 0, -2}},
            {{0, 0, -2}, {0, 1, -1}},
            {{0, 0, -2}, {1, 0, -2}},
            {{0, 0, -2}, {1, 0, -2}, {0, 1, -1}, {1, 1, -1}},
            {{0, 0, -2}, {1, 0, -2}, {0, 1, -1}, {1, 1, -1}, {0, 0, -1}}
    };

    private PracticeArena arena;
    private Location spawn;
    private int level = 1;
    /** The cells that have to be filled for the current level. */
    private final Map<Long, Material> gaps = new LinkedHashMap<Long, Material>();
    private final Map<UUID, long[]> rows = new HashMap<UUID, long[]>();      // {start, blocks, wasted, best, done}
    private final Map<UUID, List<int[]>> traces = new HashMap<UUID, List<int[]>>();

    @Override public String id() { return "blockin"; }
    @Override public String display() { return "Block In"; }

    @Override public String hint() {
        return "the defence is up with an edge block gone - break back in, then seal the hole behind you";
    }

    @Override
    public void build(PracticeArena a) {
        this.arena = a;
        a.platform(BED_X, BED_Y - 1, BED_Z, 6, Material.SANDSTONE);
        placeBed();
        layoutGaps();
        // stand outside on the attacking side, looking at the hole you have to shut
        this.spawn = a.at(BED_X + 0.5, BED_Y, BED_Z - 4.5, 0f);
    }

    private void placeBed() {
        org.bukkit.block.Block foot = arena.world().getBlockAt(BED_X, BED_Y, BED_Z);
        foot.setType(Material.BED_BLOCK, false);
        foot.setData((byte) 2, false);
        org.bukkit.block.Block head = arena.world().getBlockAt(BED_X + 1, BED_Y, BED_Z);
        head.setType(Material.BED_BLOCK, false);
        head.setData((byte) 10, false);
    }

    /**
     * The second defence from the bed-defence trainer, built as the trainer has it - same cells, same materials, not
     * a shape invented here. A drill that cuts its hole into a made-up wall teaches the wrong wall.
     */
    private void standDefence() {
        for (int x = BED_X - 3; x <= BED_X + 5; x++) {
            for (int z = BED_Z - 3; z <= BED_Z + 3; z++) {
                for (int dy = 0; dy <= 3; dy++) {
                    if (x >= BED_X && x <= BED_X + 1 && z == BED_Z && dy == 0) continue;   // the bed itself
                    arena.world().getBlockAt(x, BED_Y + dy, z).setType(Material.AIR, false);
                }
            }
        }
        List<Object[]> cells = BedDefenceTemplates.get("2");
        if (cells == null || cells.isEmpty()) cells = BedDefenceTemplates.at(2);
        for (Object[] c : cells) {
            arena.world().getBlockAt(BED_X + (Integer) c[0], BED_Y + (Integer) c[1], BED_Z + (Integer) c[2])
                    .setType((Material) c[3], false);
        }
        placeBed();
    }

    /** Cut this level's holes into the standing defence. */
    private void layoutGaps() {
        gaps.clear();
        standDefence();                                                       // undoes the last level's sealing
        int[][] set = GAPS[Math.min(GAPS.length, Math.max(1, level)) - 1];
        for (int[] g : set) {
            int x = BED_X + g[0], y = BED_Y + g[1], z = BED_Z + g[2];
            arena.world().getBlockAt(x, y, z).setType(Material.AIR, false);
            gaps.put(key(x, y, z), Material.WOOL);
        }
    }

    @Override public Location spawn() { return spawn; }
    @Override public Location spectatorSpawn() { return arena.at(BED_X + 0.5, BED_Y + 5, BED_Z + 7.5, 0f); }

    @Override
    public void kit(Player p) {
        p.getInventory().setItem(0, new ItemStack(Material.WOOL, 32));
        p.getInventory().setItem(1, new ItemStack(Material.WOOD, 32));
    }

    @Override
    public void start(PracticeGame g) { level = 1; layoutGaps(); }

    @Override public void onJoin(PracticeGame g, Player p) { row(p); }

    @Override
    public void tick(PracticeGame g) {
        for (Player p : g.alivePlayers()) {
            if (g.isFollowing(p)) continue;
            long[] r = row(p);
            boolean complete = true;
            for (Long k : gaps.keySet()) {
                if (arena.world().getBlockAt(unkeyX(k), unkeyY(k), unkeyZ(k)).getType() == Material.AIR) {
                    complete = false;
                    break;
                }
            }
            if (!complete) continue;
            long ms = r[0] > 0 ? System.currentTimeMillis() - r[0] : 0;
            r[4]++;
            if (r[3] == 0 || ms < r[3]) r[3] = ms;
            p.sendMessage(Messages.get("practice.blockindrill.sealed", "p1", ms, "p2", r[1]));
            p.playSound(p.getLocation(), Sound.LEVEL_UP, 0.7f, 1.6f);
            RunLog.record(p, level, ms, (int) r[1], (int) r[2], gaps.size(), trace(p));
            r[0] = 0; r[1] = 0; r[2] = 0;
            trace(p).clear();
            level = level % LEVELS + 1;
            layoutGaps();
            g.reset(p);
            break;
        }
    }

    @Override
    public void onPlace(PracticeGame g, Player p, Block b) {
        long[] r = row(p);
        if (gaps.containsKey(key(b.getX(), b.getY(), b.getZ()))) {
            r[1]++;
            if (r[0] == 0) r[0] = System.currentTimeMillis();
            trace(p).add(new int[]{(int) (System.currentTimeMillis() - r[0]), b.getX(), b.getY(), b.getZ()});
        } else if (Math.abs(b.getX() - BED_X) <= 5 && Math.abs(b.getZ() - BED_Z) <= 5
                && b.getY() >= BED_Y - 1 && b.getY() <= BED_Y + 2) {
            r[2]++;                                  // on the defence but not in the hole
        }
    }

    @Override
    public String hud(PracticeGame g, Player p) {
        long[] r = row(p);
        int open = 0;
        for (Long k : gaps.keySet()) {
            if (arena.world().getBlockAt(unkeyX(k), unkeyY(k), unkeyZ(k)).getType() == Material.AIR) open++;
        }
        return ChatColor.AQUA + "Block in " + ChatColor.WHITE + level + ChatColor.DARK_GRAY + "/" + LEVELS
                + ChatColor.DARK_GRAY + " | " + ChatColor.WHITE + (gaps.size() - open) + ChatColor.GRAY + "/"
                + gaps.size() + " sealed"
                + ChatColor.DARK_GRAY + " | " + ChatColor.YELLOW + "Best " + ChatColor.WHITE
                + (r[3] == 0 ? "-" : r[3] + "ms")
                + ChatColor.DARK_GRAY + " | " + ChatColor.GREEN + "Done " + ChatColor.WHITE + r[4];
    }

    /** The cells a bot brain should fill: the holes, in the order they were cut. */
    public List<int[]> gapCells() {
        List<int[]> out = new ArrayList<int[]>();
        for (Long k : gaps.keySet()) out.add(new int[]{unkeyX(k), unkeyY(k), unkeyZ(k)});
        return out;
    }

    public int bedX() { return BED_X; }
    public int bedY() { return BED_Y; }
    public int bedZ() { return BED_Z; }

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

    @Override public String level(Player p) { return String.valueOf(level); }
    @Override public String levels() { return String.valueOf(LEVELS); }
    @Override public String blocksToPlace(Player p) { return String.valueOf(gaps.size()); }
    @Override public String best(Player p) { long[] r = row(p); return r[3] == 0 ? "-" : r[3] + "ms"; }
    @Override public String completed(Player p) { return String.valueOf(row(p)[4]); }
    @Override public long bestMillis(Player p) { return row(p)[3]; }
}
