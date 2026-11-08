package net.h2020.skywars.game;

import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * A Hypixel "Default" glass cage around an island spawn. Interior is width x width x 3 tall with the player's feet
 * on the interior floor level; solo cages are 1x1, team cages 2x2. Remembers the blocks it replaced.
 */
final class Cage {
    private final List<BlockState> replaced = new ArrayList<>();
    private final List<Block> blocks = new ArrayList<>();
    private final Location feet;
    private final int width;
    private boolean built;

    Cage(Location feet, int width) {
        this.feet = feet;
        this.width = Math.max(1, width);
    }

    @SuppressWarnings("deprecation")
    void build(Material material) {
        if (built) return;
        built = true;
        World w = feet.getWorld();
        int bx = feet.getBlockX(), by = feet.getBlockY(), bz = feet.getBlockZ();
        for (int x = -1; x <= width; x++) {
            for (int z = -1; z <= width; z++) {
                for (int y = -1; y <= 3; y++) {
                    boolean wall = x == -1 || z == -1 || x == width || z == width;
                    boolean floorOrRoof = y == -1 || y == 3;
                    Block b = w.getBlockAt(bx + x, by + y, bz + z);
                    if (wall || floorOrRoof) {
                        // corners above/below are skipped like Hypixel's cage (cleaner silhouette)
                        boolean corner = (x == -1 || x == width) && (z == -1 || z == width);
                        if (corner && floorOrRoof) continue;
                        replaced.add(b.getState());
                        b.setType(material, false);
                        blocks.add(b);
                    } else if (b.getType() != Material.AIR) {
                        // interior must be free; not restored on open so nobody gets stuck inside a block
                        b.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    boolean contains(Block b) { return blocks.contains(b); }

    /** Break the cage: restore what was there before (usually air), with glass break effect + sound. */
    @SuppressWarnings("deprecation")
    void remove() {
        if (!built) return;
        built = false;
        for (Block b : blocks) {
            Material m = b.getType();
            if (m == Material.GLASS || m == Material.STAINED_GLASS) {
                b.getWorld().playEffect(b.getLocation(), Effect.STEP_SOUND, m.getId());
            }
        }
        for (int i = replaced.size() - 1; i >= 0; i--) replaced.get(i).update(true, false);
        feet.getWorld().playSound(feet, Sound.GLASS, 1f, 1f);
        replaced.clear();
        blocks.clear();
    }

    Location feet() { return feet; }
    int width() { return width; }
}
