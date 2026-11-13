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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bridging: a gap, a stack of blocks, and a clock.
 *
 * <p>Straight or diagonal, and the gap grows by two blocks after every crossing, so the drill measures the thing that
 * actually separates a good bridger from a fast one - holding the same speed when the gap is long. A run is timed from
 * the moment the player leaves the start platform to the moment they land on the far one, and counts the blocks they
 * used, because a bridge that takes 14 blocks for a 10 block gap is a bridge with a hole in it.
 *
 * <p>Bots run the same drill with the same rules, which makes the numbers directly comparable.
 */
public final class BridgeDrill implements Drill {

    public enum Variant { STRAIGHT, DIAGONAL }

    private final Variant variant;
    private PracticeArena arena;
    private Location spawn;
    private int length = 10;
    private int startX, endX;
    private final Map<UUID, long[]> run = new HashMap<UUID, long[]>();      // {startTick, blocks, bestMs, runs, falls}

    public BridgeDrill() { this(Variant.STRAIGHT); }
    public BridgeDrill(Variant v) { this.variant = v; }

    @Override public String id() { return variant == Variant.DIAGONAL ? "bridge-diagonal" : "bridge"; }
    @Override public String display() { return "Bridging (" + (variant == Variant.DIAGONAL ? "diagonal" : "straight") + ")"; }

    @Override public String hint() {
        return "bridge the gap - it grows by two blocks every time you cross";
    }

    @Override
    public void build(PracticeArena a) {
        this.arena = a;
        this.startX = 0;
        rebuild(a);
        this.spawn = a.at(0.5, 65, 0.5, 0f);
    }

    /**
     * Start pad, gap and finish platform for the current length.
     *
     * <p>The pad runs from behind the spawn up to the spawn block, so the block the player stands on is the last one
     * before the void. That matters for the bots: a bridging run starts from the support under the feet, and if there
     * is platform in front of it the first cells are already solid, nothing can be placed and the run stalls with a
     * handful of blocks. Measured exactly that before this was fixed.
     */
    private void rebuild(PracticeArena a) {
        clear();
        a.box(startX - 3, 64, -1, startX, 64, 1, Material.SANDSTONE);
        a.box(startX - 3, 63, -1, startX, 63, 1, Material.SANDSTONE);
        a.rim(startX - 3, 64, 0, 1, 1, Material.SMOOTH_BRICK);        // a lip behind, not in front
        int fz = variant == Variant.DIAGONAL ? length : 0;
        int ex = startX + length + 1;
        // the far side: three wide so a diagonal bridge actually has somewhere to land
        a.platform(ex, 64, fz, 2, Material.SANDSTONE);
        a.platform(ex, 63, fz, 2, Material.SANDSTONE);
        a.marker(ex, 65, fz, Material.GLOWSTONE);
        this.endX = ex;
    }

    /** Remove the previous build out to the old far side, so a longer gap is not laid over the old one. */
    private void clear() {
        for (int x = startX - 4; x <= startX + 40; x++)
            for (int z = -3; z <= 40; z++)
                for (int y = 62; y <= 67; y++)
                    arena.world().getBlockAt(x, y, z).setType(Material.AIR, false);
    }

    @Override public Location spawn() { return spawn; }
    @Override public Location spectatorSpawn() { return arena.at(-3.5, 70, 3.5, 0f); }

    @Override
    public void kit(Player p) {
        p.getInventory().setItem(0, new ItemStack(Material.SANDSTONE, 128));
        p.getInventory().setItem(1, new ItemStack(Material.WOOD_PICKAXE));
    }

    @Override public void onJoin(PracticeGame g, Player p) { row(p); }

    @Override
    public void tick(PracticeGame g) {
        for (Player p : g.alivePlayers()) {
            if (g.isFollowing(p)) continue;      // watching a bot is not a run
            long[] r = row(p);
            Location l = p.getLocation();
            boolean onStart = l.getBlockX() <= startX + 2;
            boolean onFinish = nearFinish(l);
            if (r[0] == 0 && !onStart && p.getGameMode() == org.bukkit.GameMode.SURVIVAL) {
                r[0] = System.currentTimeMillis();      // left the start platform: the clock starts
            }
            if (r[0] > 0 && onFinish) {
                long ms = System.currentTimeMillis() - r[0];
                r[0] = 0;
                r[3]++;
                if (r[2] == 0 || ms < r[2]) r[2] = ms;
                p.playSound(p.getLocation(), Sound.LEVEL_UP, 0.7f, 1.5f);
                p.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "Crossed" + ChatColor.RESET
                        + ChatColor.GRAY + "  " + ChatColor.WHITE + ms + " ms"
                        + ChatColor.GRAY + " with " + ChatColor.WHITE + r[1] + ChatColor.GRAY + " blocks"
                        + ChatColor.DARK_GRAY + "  (gap " + length + ")");
                // the gap grows: the next crossing is the one that tests the technique
                length = Math.min(22, length + 2);
                rebuild(arena);
                r[1] = 0;
                g.reset(p);
            }
        }
    }

    private boolean nearFinish(Location l) {
        int fz = variant == Variant.DIAGONAL ? length : 0;
        return Math.abs(l.getBlockX() - endX) <= 2 && Math.abs(l.getBlockZ() - fz) <= 2 && l.getBlockY() >= 64;
    }

    @Override
    public void onPlace(PracticeGame g, Player p, Block b) {
        long[] r = row(p);
        if (r[0] > 0) r[1]++;       // only blocks used during a run count
    }

    @Override
    public void onFall(PracticeGame g, Player p) {
        long[] r = row(p);
        if (r[0] > 0) r[4]++;
        r[0] = 0;
        r[1] = 0;
    }

    @Override
    public String hud(PracticeGame g, Player p) {
        long[] r = row(p);
        return ChatColor.AQUA + "Gap " + ChatColor.WHITE + length
                + ChatColor.DARK_GRAY + " | " + ChatColor.GREEN + "Crossed " + ChatColor.WHITE + r[3]
                + ChatColor.DARK_GRAY + " | " + ChatColor.YELLOW + "Best " + ChatColor.WHITE
                + (r[2] == 0 ? "-" : r[2] + "ms")
                + ChatColor.DARK_GRAY + " | " + ChatColor.RED + "Falls " + ChatColor.WHITE + r[4];
    }

    @Override
    public List<String> stats(PracticeGame g, Player p) {
        long[] r = row(p);
        List<String> out = new ArrayList<String>();
        out.add("crossings " + r[3] + ", falls " + r[4]);
        out.add("best time " + (r[2] == 0 ? "-" : r[2] + " ms"));
        return out;
    }

    /** The variant a session is running, for the command output. */
    public Variant variant() { return variant; }

    private long[] row(Player p) {
        long[] r = run.get(p.getUniqueId());
        if (r == null) { r = new long[5]; run.put(p.getUniqueId(), r); }
        return r;
    }
}
