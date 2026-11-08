package net.h2020.bots.ai.nav;

import org.bukkit.Material;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** What a brain allows the navigator to do for one goal. */
public final class NavOptions {
    public boolean bridge;
    /** Max blocks to place (bridge + pillar); defaults to blocks carried when bridging. */
    public int maxBlocks = -1;
    public boolean pillar;
    public boolean breakBlocks;
    public Set<Material> breakable;
    public boolean sprint = true;
    /** -1 = from skill. */
    public int parkourGap = -1;
    /** Additional void fear on top of the persona's. */
    public double extraVoidAversion = 0;
    public List<Vector> threats = new ArrayList<>();
    public Set<Long> forbidden = Collections.emptySet();
    public int maxNodes = 5000;
    /** Walk (no sprint) and keep away from edges — e.g. lobby strolling. */
    public boolean stroll;
    /** Allow the look-ahead to weave a bit (natural walking). */
    public boolean weave = true;
    /** Add known enemies (from perception) as path costs. Off when chasing someone. */
    public boolean avoidEnemies = true;
    /** Express the player-placed rule explicitly (defaults to the world's registry). */
    public PlacedTest placed;
    public interface PlacedTest { boolean isPlaced(int x, int y, int z); }

    /** The effective rule for this request in a world. */
    public boolean breakable(org.bukkit.World w, int x, int y, int z) {
        if (placed != null) return placed.isPlaced(x, y, z);
        return net.h2020.api.game.PlacedRegistry.isPlaced(w, x, y, z);
    }

    /** Highest drop (blocks) the route may take; -1 = from skill. Looting an island takes the drop into its cave. */
    public int maxFall = -1;
    /** Fall damage (half hearts) a drop may cost; -1 = default (5, never below 6 hp left). */
    public double maxFallDamage = -1;

    public static NavOptions walk() { return new NavOptions(); }

    public static NavOptions strollOpts() {
        NavOptions o = new NavOptions();
        o.stroll = true; o.sprint = false; o.parkourGap = 0; o.maxNodes = 2500;
        return o;
    }

    public static NavOptions bridging(int maxBlocks) {
        NavOptions o = new NavOptions();
        o.bridge = true; o.maxBlocks = maxBlocks; o.maxNodes = 9000;
        return o;
    }
}
