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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Block clutching: falling, no ground, and a stack of blocks.
 *
 * <p>The clutch is what a player does when the bridge under them is gone - place a block on the way down, land on it,
 * place another, and walk back to something solid. Nobody survives it by being careful; they survive it by placing
 * on the way past, which is what this measures: a save counts when you come to rest on a block you placed after the
 * fall started.
 */
public final class BlockClutchDrill implements Drill {

    private static final int SPAWN_Y = 92;          // high enough that the fall is real
    private static final int FLOOR_Y = 60;          // below this is a miss, not a save

    private PracticeArena arena;
    private Location spawn;
    /** Incremented on every re-drop, so a save can be told from standing about on the platform. */
    private final Map<UUID, long[]> rows = new HashMap<UUID, long[]>();     // {dropped, saves, misses, best, done}
    private final Map<UUID, Long> droppedAt = new HashMap<UUID, Long>();
    private final Map<UUID, Boolean> placedSinceDrop = new HashMap<UUID, Boolean>();

    @Override public String id() { return "blockclutch"; }
    @Override public String display() { return "Block Clutching"; }

    @Override public String hint() {
        return "the ground is gone - place under yourself on the way down and land on it";
    }

    @Override
    public void build(PracticeArena a) {
        this.arena = a;
        // one small island to aim for, and nothing underneath the drop
        a.platform(0, 64, 0, 2, Material.SANDSTONE);
        a.rim(0, 64, 0, 3, 1, Material.SMOOTH_BRICK);
        this.spawn = a.at(0.5, SPAWN_Y, 0.5, 0f);
    }

    @Override public Location spawn() { return spawn; }
    @Override public Location spectatorSpawn() { return arena.at(0.5, 70, 9.5, 0f); }

    @Override
    public void kit(Player p) {
        p.getInventory().setItem(0, new ItemStack(Material.WOOL, 32));
        p.getInventory().setItem(1, new ItemStack(Material.SANDSTONE, 32));
    }

    @Override public void onJoin(PracticeGame g, Player p) { row(p); drop(p); }

    /** A block placed after the fall began is what makes a save a save. */
    @Override
    public void onPlace(PracticeGame g, Player p, Block b) {
        if (droppedAt.containsKey(p.getUniqueId())) placedSinceDrop.put(p.getUniqueId(), true);
    }

    @Override
    public void tick(PracticeGame g) {
        for (Player p : g.alivePlayers()) {
            if (g.isFollowing(p)) continue;
            long[] r = row(p);
            Long at = droppedAt.get(p.getUniqueId());
            if (at == null) continue;
            double y = p.getLocation().getY();
            if (y < FLOOR_Y) {                       // fell out: a miss, and back up for another go
                r[2]++;
                p.sendMessage(Messages.get("practice.blockclutchdrill.missed", "p1", r[2]));
                drop(p);
                p.teleport(spawn);
                continue;
            }
            if (p.isOnGround() && y > FLOOR_Y && Boolean.TRUE.equals(placedSinceDrop.get(p.getUniqueId()))) {
                long ms = System.currentTimeMillis() - at;
                r[1]++;
                r[4]++;
                if (r[3] == 0 || ms < r[3]) r[3] = ms;
                p.sendMessage(Messages.get("practice.blockclutchdrill.saved", "p1", ms, "p2", r[1]));
                p.playSound(p.getLocation(), Sound.LEVEL_UP, 0.7f, 1.6f);
                drop(p);
                p.teleport(spawn);
            }
        }
    }

    private void drop(Player p) {
        droppedAt.put(p.getUniqueId(), System.currentTimeMillis());
        placedSinceDrop.put(p.getUniqueId(), false);
        long[] r = row(p);
        if (r[0] == 0) r[0] = 1;
    }

    @Override
    public String hud(PracticeGame g, Player p) {
        long[] r = row(p);
        return ChatColor.AQUA + "Clutch" + ChatColor.DARK_GRAY + " | " + ChatColor.WHITE + r[1]
                + ChatColor.GRAY + " saved" + ChatColor.DARK_GRAY + ", " + ChatColor.WHITE + r[2] + " missed"
                + ChatColor.DARK_GRAY + " | " + ChatColor.YELLOW + "Best " + ChatColor.WHITE
                + (r[3] == 0 ? "-" : r[3] + "ms")
                + ChatColor.DARK_GRAY + " | " + ChatColor.GREEN + "Done " + ChatColor.WHITE + r[4];
    }

    private long[] row(Player p) {
        long[] r = rows.get(p.getUniqueId());
        if (r == null) { r = new long[5]; rows.put(p.getUniqueId(), r); }
        return r;
    }

    @Override public String best(Player p) { long[] r = row(p); return r[3] == 0 ? "-" : r[3] + "ms"; }
    @Override public String completed(Player p) { return String.valueOf(row(p)[4]); }
    @Override public long bestMillis(Player p) { return row(p)[3]; }
    @Override public String blocksToPlace(Player p) { return "-"; }
}
