package net.h2020.bedwars.world;

import net.h2020.api.stats.CombatStats;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;

/**
 * The Compact Pop-up Tower: a 3x3 wool box with a ladder shaft and a roofed firing step, built two blocks per tick from
 * where the player stands, exactly like the item does on Hypixel. Placing it instantly would be a wall of block updates
 * in one tick; two a tick is both gentler and the timing players see.
 */
public final class PopUpTower {
    private PopUpTower() {}

    public static void build(final Plugin plugin, final Player owner, final Location at, final byte woolData,
                             final float yaw) {
        final World w = at.getWorld();
        final int bx = at.getBlockX(), by = at.getBlockY(), bz = at.getBlockZ();
        // which side the player is standing on: the roof leaves that side open, the ladder faces out of it
        double rad = Math.toRadians(yaw);
        int dx = (int) Math.round(-Math.sin(rad)), dz = (int) Math.round(Math.cos(rad));
        final List<int[]> cells = new ArrayList<int[]>();
        for (int y = 0; y < 3; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && z == 0) continue;
                    cells.add(new int[]{bx + x, by + y, bz + z, 0});
                }
            }
        }
        // ladder shaft up the middle
        int ladderData = dx > 0 ? 5 : dx < 0 ? 4 : dz > 0 ? 2 : 3;
        for (int y = 0; y < 4; y++) cells.add(new int[]{bx, by + y, bz, ladderData});
        // roof, with the side we are standing on left open so the player can walk up and out
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) continue;
                if (x == dx && z == dz) continue;
                cells.add(new int[]{bx + x, by + 3, bz + z, 0});
            }
        }
        new BukkitRunnable() {
            int i = 0;
            @Override public void run() {
                for (int n = 0; n < 2 && i < cells.size(); n++, i++) {
                    int[] c = cells.get(i);
                    Block b = w.getBlockAt(c[0], c[1], c[2]);
                    if (b.getType() != Material.AIR) continue;
                    if (c[3] != 0) {
                        b.setTypeIdAndData(Material.LADDER.getId(), (byte) c[3], false);
                    } else {
                        b.setTypeIdAndData(Material.WOOL.getId(), woolData, false);
                    }
                    net.h2020.api.stats.CombatStats.inc("tower.blocks");
                }
                if (i >= cells.size()) {
                    net.h2020.api.stats.CombatStats.inc("tower.built");
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }
}
