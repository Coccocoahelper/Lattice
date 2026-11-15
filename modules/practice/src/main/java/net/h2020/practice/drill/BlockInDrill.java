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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Block-in: seal yourself into a pocket before the fight starts.
 *
 * <p>The move is small and it wins fights: two blocks on the side the attacker is coming from stops the knockback
 * that would have thrown you off the island, and it costs less than a second if it is done without thinking. What
 * makes it slow in practice is deciding which side, then turning - so the drill measures the same thing a real fight
 * does, the clock from the first block to a closed pocket.
 *
 * <p>Widths step up: four blocks (two sides, both heights), five (two sides and a shoulder), then six, seven and the
 * full eight around the body. A pocket counts when the ring around the player is solid, whichever block they used -
 * wool, wood or end stone - because the mechanic is the sealing, not the material.
 */
public final class BlockInDrill implements Drill {

    private static final int LEVELS = 5;
    /** Narrow pockets first: 4, 5, 6, 7, then the whole ring of 8. */
    private static final int[] REQUIRED = {4, 5, 6, 7, 8};
    /** Level, and the ring around the body: feet and head on each of the four sides. */
    private static final int[][ ] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private PracticeArena arena;
    private Location spawn;
    private int level = 1;
    private final Map<UUID, long[]> rows = new HashMap<UUID, long[]>();   // {start, blocks, wasted, best, done}
    private final Map<UUID, List<int[]>> traces = new HashMap<UUID, List<int[]>>();

    @Override public String id() { return "blockin"; }
    @Override public String display() { return "Block In"; }

    @Override public String hint() {
        return "seal yourself in - the pocket has to be closed before the attacker reaches you";
    }

    @Override
    public void build(PracticeArena a) {
        this.arena = a;
        a.platform(0, 64, 0, 3, Material.SANDSTONE);
        // a stone marker on the side the attacker comes from, so there is a side to seal first
        a.marker(0, 65, -4, Material.OBSIDIAN);
        a.marker(-4, 65, 0, Material.SMOOTH_BRICK);
        a.marker(4, 65, 0, Material.SMOOTH_BRICK);
        this.spawn = a.at(0.5, 65, 0.5, 0f);
    }

    @Override public Location spawn() { return spawn; }
    @Override public Location spectatorSpawn() { return arena.at(0.5, 70, 7.5, 0f); }

    @Override
    public void kit(Player p) {
        p.getInventory().setItem(0, new ItemStack(Material.WOOL, 64));
        p.getInventory().setItem(1, new ItemStack(Material.WOOD, 64));
        p.getInventory().setItem(8, new ItemStack(Material.WOOD_PICKAXE));
    }

    @Override public void start(PracticeGame g) { level = 1; }

    @Override public void onJoin(PracticeGame g, Player p) { row(p); }

    @Override
    public void tick(PracticeGame g) {
        for (Player p : g.alivePlayers()) {
            if (g.isFollowing(p)) continue;
            long[] r = row(p);
            int bx = p.getLocation().getBlockX();
            int by = p.getLocation().getBlockY();
            int bz = p.getLocation().getBlockZ();
            int sealed = sealed(bx, by, bz);
            if (sealed < required()) continue;
            long ms = r[0] > 0 ? System.currentTimeMillis() - r[0] : 0;
            r[4]++;
            if (r[3] == 0 || ms < r[3]) r[3] = ms;
            p.sendMessage(Messages.get("practice.blockindrill.sealed", "p1", ms, "p2", r[1]));
            p.playSound(p.getLocation(), Sound.LEVEL_UP, 0.7f, 1.6f);
            RunLog.record(p, level, ms, (int) r[1], (int) r[2], required(), trace(p));
            clearRing(bx, by, bz);
            r[0] = 0; r[1] = 0; r[2] = 0;
            trace(p).clear();
            level = level % LEVELS + 1;
            g.reset(p);
            break;
        }
    }

    @Override
    public void onPlace(PracticeGame g, Player p, Block b) {
        long[] r = row(p);
        int dx = b.getX() - p.getLocation().getBlockX();
        int dz = b.getZ() - p.getLocation().getBlockZ();
        int dy = b.getY() - p.getLocation().getBlockY();
        boolean inRing = (Math.abs(dx) + Math.abs(dz)) == 1 && (dy == 0 || dy == 1);
        if (inRing) {
            r[1]++;
            if (r[0] == 0) r[0] = System.currentTimeMillis();
            long t0 = r[0];
            trace(p).add(new int[]{(int) (System.currentTimeMillis() - t0), b.getX(), b.getY(), b.getZ()});
        } else if (dy >= 0 && dy <= 2 && Math.abs(dx) <= 4 && Math.abs(dz) <= 4) {
            r[2]++;                                  // in the work area but outside the pocket
        }
    }

    @Override
    public String hud(PracticeGame g, Player p) {
        long[] r = row(p);
        int bx = p.getLocation().getBlockX();
        int by = p.getLocation().getBlockY();
        int bz = p.getLocation().getBlockZ();
        return ChatColor.AQUA + "Block in " + ChatColor.WHITE + level + ChatColor.DARK_GRAY + "/" + LEVELS
                + ChatColor.DARK_GRAY + " | " + ChatColor.WHITE + sealed(bx, by, bz) + ChatColor.GRAY + "/"
                + required() + " sealed"
                + ChatColor.DARK_GRAY + " | " + ChatColor.YELLOW + "Best " + ChatColor.WHITE
                + (r[3] == 0 ? "-" : r[3] + "ms")
                + ChatColor.DARK_GRAY + " | " + ChatColor.GREEN + "Done " + ChatColor.WHITE + r[4];
    }

    /** How many of the eight ring cells around the body are solid. */
    private int sealed(int bx, int by, int bz) {
        int n = 0;
        for (int[] s : SIDES) {
            for (int dy = 0; dy <= 1; dy++) {
                Material m = arena.world().getBlockAt(bx + s[0], by + dy, bz + s[1]).getType();
                if (m != Material.AIR && m.isSolid()) n++;
            }
        }
        return n;
    }

    private int required() { return REQUIRED[Math.min(REQUIRED.length, Math.max(1, level)) - 1]; }

    private void clearRing(int bx, int by, int bz) {
        for (int[] s : SIDES) {
            for (int dy = 0; dy <= 1; dy++) {
                arena.world().getBlockAt(bx + s[0], by + dy, bz + s[1]).setType(Material.AIR, false);
            }
        }
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

    @Override public String level(Player p) { return String.valueOf(level); }
    @Override public String levels() { return String.valueOf(LEVELS); }
    @Override public String blocksToPlace(Player p) { return String.valueOf(required()); }
    @Override public String best(Player p) { long[] r = row(p); return r[3] == 0 ? "-" : r[3] + "ms"; }
    @Override public String completed(Player p) { return String.valueOf(row(p)[4]); }
    @Override public long bestMillis(Player p) { return row(p)[3]; }
}
