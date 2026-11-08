package net.h2020.bots.ai.nav;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Everything a search needs. Built on the main thread, consumed by the (time-sliced) PathFinder. */
public final class PathRequest {
    public final World world;
    public final int sx, sy, sz;
    public final double gx, gy, gz;
    /** Horizontal radius around the goal that counts as arrived. */
    public double goalRadius = 1.0;
    public double goalVertical = 1.6;
    /** Baritone's GoalXZ: any floor inside the radius counts, the Y is not part of the goal. */
    public boolean anyY = false;

    // ---- abilities ----
    public int maxBridgeBlocks = 0;
    public double bridgeCost = 3.5;
    public boolean pillar = false;
    public int maxParkourGap = 1;
    public boolean breakBlocks = false;
    /** Tools in the inventory: digging stone by hand takes ~7 s a block, so such routes are a last resort. */
    public boolean hasPickaxe = true, hasAxe = true;
    /** Which block types may be broken (null = any non-unbreakable). */
    public Set<Material> breakable = null;
    /** Only blocks a player placed may be broken (Bed Wars: map blocks are indestructible). */
    public NavOptions.PlacedTest placed = null;

    /** May this cell be dug through? */
    public boolean breakable(int x, int y, int z) {
        if (placed != null) return placed.isPlaced(x, y, z);
        return net.h2020.api.game.PlacedRegistry.isPlaced(world, x, y, z);
    }
    public int maxFall = 4;
    /** Max acceptable fall damage in half hearts (a fall that would do more is forbidden). */
    public double maxFallDamage = 4;

    // ---- costs ----
    /** Extra cost per void-adjacent side of a node (edge fear). */
    public double voidAversion = 0.8;
    public double lavaAversion = 6.0;
    /** Threat positions (enemies) to keep away from, with weight. */
    public final List<Vector> threats = new ArrayList<>();
    public double threatWeight = 4.0;
    /** Weighted A* factor (> 1 = greedier, faster, slightly less optimal - also more human). */
    public double heuristicWeight = 1.35;
    public int maxNodes = 5000;
    /** Never walk into these cells (e.g. bed-defense plan cells). */
    public Set<Long> forbidden = Collections.emptySet();

    public PathRequest(World world, int sx, int sy, int sz, double gx, double gy, double gz) {
        this.world = world; this.sx = sx; this.sy = sy; this.sz = sz; this.gx = gx; this.gy = gy; this.gz = gz;
    }
}
