package net.h2020.bots.ai.brain.bedwars;

import net.h2020.bots.ai.util.AIMath;
import net.h2020.bots.ai.world.Shape;
import net.h2020.bots.ai.world.WorldView;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Geometry of the classic Hypixel bed defenses: layer 1 hugs the two bed blocks (4 sides + 2 ends + 2 on top,
 * usually wool), layer 2 wraps layer 1 (end stone / more wool). Cells are ordered the way players place them:
 * inner before outer, bottom before top, so every block has something to be placed against.
 */
public final class BedDefense {
    public final int[][] bed;          // foot, head
    public final List<int[]> layer1;
    public final List<int[]> layer2;
    public final Set<Long> allCells = new HashSet<>();
    public final World world;

    private BedDefense(World w, int[][] bed, List<int[]> l1, List<int[]> l2) {
        this.world = w; this.bed = bed; this.layer1 = l1; this.layer2 = l2;
        for (int[] c : l1) allCells.add(AIMath.pack(c[0], c[1], c[2]));
        for (int[] c : l2) allCells.add(AIMath.pack(c[0], c[1], c[2]));
    }

    public static int[] head(World w, Location foot) {
        int x = foot.getBlockX(), y = foot.getBlockY(), z = foot.getBlockZ();
        int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] o : d) if (w.getBlockAt(x + o[0], y, z + o[1]).getType() == Material.BED_BLOCK) return new int[]{x + o[0], y, z + o[1]};
        return new int[]{x + 1, y, z};
    }

    public static BedDefense of(Location foot) {
        World w = foot.getWorld();
        int[] f = {foot.getBlockX(), foot.getBlockY(), foot.getBlockZ()};
        int[] h = head(w, foot);
        int[][] bed = {f, h};
        Set<Long> bedKeys = new HashSet<>();
        bedKeys.add(AIMath.pack(f[0], f[1], f[2]));
        bedKeys.add(AIMath.pack(h[0], h[1], h[2]));
        int by = f[1];
        int[][] nb = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}};
        LinkedHashSet<Long> l1k = new LinkedHashSet<>();
        List<int[]> l1sides = new ArrayList<>(), l1top = new ArrayList<>();
        for (int[] b : bed) for (int[] o : nb) {
            int x = b[0] + o[0], y = b[1] + o[1], z = b[2] + o[2];
            long k = AIMath.pack(x, y, z);
            if (bedKeys.contains(k) || !l1k.add(k)) continue;
            (y > by ? l1top : l1sides).add(new int[]{x, y, z});
        }
        List<int[]> l1 = new ArrayList<>(l1sides);
        l1.addAll(l1top);
        LinkedHashSet<Long> l2k = new LinkedHashSet<>();
        List<int[]> l2 = new ArrayList<>();
        for (int level = 0; level <= 2; level++) {
            for (int[] c : l1) for (int[] o : nb) {
                int x = c[0] + o[0], y = c[1] + o[1], z = c[2] + o[2];
                if (y != by + level) continue;
                long k = AIMath.pack(x, y, z);
                if (bedKeys.contains(k) || l1k.contains(k) || !l2k.add(k)) continue;
                l2.add(new int[]{x, y, z});
            }
        }
        return new BedDefense(w, bed, l1, l2);
    }

    /** Cells of a layer that are still open (air) — i.e. holes in the defense. */
    public List<int[]> open(List<int[]> layer, WorldView wv) {
        List<int[]> out = new ArrayList<>();
        for (int[] c : layer) if (Shape.passable(wv.shape(c[0], c[1], c[2])) && wv.shape(c[0], c[1], c[2]) != Shape.WATER) out.add(c);
        return out;
    }

    public double[] center() {
        return new double[]{(bed[0][0] + bed[1][0]) / 2.0 + 0.5, bed[0][1] + 0.5, (bed[0][2] + bed[1][2]) / 2.0 + 0.5};
    }

    /** Fraction of layer-1 still covered (1 = intact). */
    public double integrity(WorldView wv) {
        if (layer1.isEmpty()) return 1;
        return 1.0 - open(layer1, wv).size() / (double) layer1.size();
    }
}
