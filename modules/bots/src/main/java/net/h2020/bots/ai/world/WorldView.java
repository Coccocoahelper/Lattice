package net.h2020.bots.ai.world;

import net.h2020.bots.ai.util.AIMath;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Arrays;

/**
 * Read-only voxel queries for the walkability model. Optionally caches classifications (one cache per path search so
 * a search sees a consistent snapshot without hammering chunk lookups). Never loads chunks: unloaded = UNKNOWN wall.
 */
public final class WorldView {
    public static final double NO_STAND = Double.NaN;

    private final World world;
    private final boolean caching;
    // open addressing long -> byte (shape) + double (low-floor height) cache
    private long[] keys;
    private byte[] vals;
    private float[] heights;
    private int size;
    private int lastChunkX = Integer.MIN_VALUE, lastChunkZ = Integer.MIN_VALUE;
    private boolean lastChunkLoaded;

    public WorldView(World world, boolean caching) {
        this.world = world;
        this.caching = caching;
        if (caching) {
            keys = new long[1 << 14];
            vals = new byte[1 << 14];
            heights = new float[1 << 14];
            Arrays.fill(keys, Long.MIN_VALUE);
        }
    }

    public World world() { return world; }

    private boolean loaded(int x, int z) {
        int cx = x >> 4, cz = z >> 4;
        if (cx != lastChunkX || cz != lastChunkZ) {
            lastChunkX = cx; lastChunkZ = cz;
            lastChunkLoaded = world.isChunkLoaded(cx, cz);
        }
        return lastChunkLoaded;
    }

    @SuppressWarnings("deprecation")
    private void compute(int x, int y, int z, byte[] outShape, float[] outHeight) {
        if (y < 0) { outShape[0] = Shape.OPEN; outHeight[0] = 0f; return; }
        if (y > 255) { outShape[0] = Shape.OPEN; outHeight[0] = 0f; return; }
        if (!loaded(x, z)) { outShape[0] = Shape.UNKNOWN; outHeight[0] = 1f; return; }
        Block b = world.getBlockAt(x, y, z);
        Material m = b.getType();
        int data = b.getData();
        byte s = Shape.classify(m, data);
        outShape[0] = s;
        outHeight[0] = (float) Shape.topHeight(m, data);
        if ((net.h2020.bots.ai.nav.NavStats.fix & net.h2020.bots.ai.nav.NavStats.FIX_BOXES) != 0
                && Shape.fromBoxes(s, m) && !m.isOccluding()) boxShape(x, y, z, outShape, outHeight);
    }

    // ------------------------------------------------------------------ exact collision boxes

    private net.minecraft.server.v1_8_R3.World nms;
    private final java.util.List<net.minecraft.server.v1_8_R3.AxisAlignedBB> boxes = new java.util.ArrayList<>();

    /** The real (NMS) collision boxes of one block, the same ones the bot physics collides with. */
    private java.util.List<net.minecraft.server.v1_8_R3.AxisAlignedBB> boxes(int x, int y, int z) {
        if (nms == null) nms = ((org.bukkit.craftbukkit.v1_8_R3.CraftWorld) world).getHandle();
        boxes.clear();
        net.minecraft.server.v1_8_R3.BlockPosition bp = new net.minecraft.server.v1_8_R3.BlockPosition(x, y, z);
        net.minecraft.server.v1_8_R3.IBlockData st = nms.getType(bp);
        st.getBlock().a(nms, bp, st, new net.minecraft.server.v1_8_R3.AxisAlignedBB(x, y, z, x + 1, y + 2, z + 1), boxes, null);
        return boxes;
    }

    /**
     * Classify a non-cube solid from its real collision boxes instead of the material table: snow layers (layers-1)/8,
     * farmland (a full cube in 1.8), flower pots/skulls (low), panes/iron bars (standable top), stairs and slabs in all
     * orientations, brewing stands, hoppers... What matters for walking is the top under a body centred in the cell
     * (x/z 0.2..0.8) and whether anything taller stands at the cell's edges.
     */
    private void boxShape(int x, int y, int z, byte[] outShape, float[] outHeight) {
        java.util.List<net.minecraft.server.v1_8_R3.AxisAlignedBB> list;
        try { list = boxes(x, y, z); } catch (Throwable t) { return; }
        if (list.isEmpty()) { outShape[0] = Shape.OPEN; outHeight[0] = 0f; return; }
        double all = 0, centre = 0;
        for (net.minecraft.server.v1_8_R3.AxisAlignedBB bb : list) {
            double top = bb.e - y;
            all = Math.max(all, top);
            if (bb.d > x + 0.2 && bb.a < x + 0.8 && bb.f > z + 0.2 && bb.c < z + 0.8) centre = Math.max(centre, top);
        }
        if (all > 1.01) { outShape[0] = Shape.TALL; outHeight[0] = 1.5f; }
        else if (all >= 0.99) { outShape[0] = centre >= 0.99 ? Shape.FULL : Shape.THIN; outHeight[0] = 1f; }
        else if (all > 0.6) { outShape[0] = Shape.HIGH; outHeight[0] = (float) all; }
        else { outShape[0] = Shape.LOW; outHeight[0] = (float) all; }
    }

    /**
     * Top (relative to the cell) of the half of a solid cell that a body walking in along (dx,dz) reaches first,
     * across the body's width. A stair entered from its low side is 0.5 there: two 0.5 steps, no jump needed.
     * NaN when unknown.
     */
    public double entryTop(int x, int y, int z, int dx, int dz) {
        if (y < 0 || y > 255 || !loaded(x, z)) return Double.NaN;
        java.util.List<net.minecraft.server.v1_8_R3.AxisAlignedBB> list;
        try { list = boxes(x, y, z); } catch (Throwable t) { return Double.NaN; }
        // near half: entering moving +x means x in [0, 0.5), etc.; lateral extent: the body's 0.6 width at the centre
        double x0 = x, x1 = x + 1, z0 = z, z1 = z + 1;
        if (dx > 0) x1 = x + 0.5; else if (dx < 0) x0 = x + 0.5; else { x0 = x + 0.2; x1 = x + 0.8; }
        if (dz > 0) z1 = z + 0.5; else if (dz < 0) z0 = z + 0.5; else { z0 = z + 0.2; z1 = z + 0.8; }
        double top = 0;
        for (net.minecraft.server.v1_8_R3.AxisAlignedBB bb : list) {
            if (bb.d > x0 && bb.a < x1 && bb.f > z0 && bb.c < z1) top = Math.max(top, bb.e - y);
        }
        return top;
    }

    private final byte[] tmpS = new byte[1];
    private final float[] tmpH = new float[1];

    private int slot(int x, int y, int z) {
        long k = AIMath.pack(x, y, z);
        int mask = keys.length - 1;
        int i = (int) (mix(k) & mask);
        while (true) {
            long kk = keys[i];
            if (kk == k) return i;
            if (kk == Long.MIN_VALUE) {
                if (size * 2 >= keys.length) { grow(); return slot(x, y, z); }
                keys[i] = k;
                compute(x, y, z, tmpS, tmpH);
                vals[i] = tmpS[0];
                heights[i] = tmpH[0];
                size++;
                return i;
            }
            i = (i + 1) & mask;
        }
    }

    private static long mix(long k) {
        k ^= (k >>> 33);
        k *= 0xff51afd7ed558ccdL;
        k ^= (k >>> 33);
        return k;
    }

    private void grow() {
        long[] ok = keys; byte[] ov = vals; float[] oh = heights;
        keys = new long[ok.length * 2];
        vals = new byte[ok.length * 2];
        heights = new float[ok.length * 2];
        Arrays.fill(keys, Long.MIN_VALUE);
        int mask = keys.length - 1;
        for (int j = 0; j < ok.length; j++) {
            if (ok[j] == Long.MIN_VALUE) continue;
            int i = (int) (mix(ok[j]) & mask);
            while (keys[i] != Long.MIN_VALUE) i = (i + 1) & mask;
            keys[i] = ok[j]; vals[i] = ov[j]; heights[i] = oh[j];
        }
    }

    public byte shape(int x, int y, int z) {
        // slot() may grow (replace) the arrays, so resolve the index before reading the field
        if (caching) { int i = slot(x, y, z); return vals[i]; }
        compute(x, y, z, tmpS, tmpH);
        return tmpS[0];
    }

    public double height(int x, int y, int z) {
        if (caching) { int i = slot(x, y, z); return heights[i]; }
        compute(x, y, z, tmpS, tmpH);
        return tmpH[0];
    }

    public boolean passable(int x, int y, int z) { return Shape.passable(shape(x, y, z)); }

    public boolean solid(int x, int y, int z) { return Shape.solid(shape(x, y, z)); }

    /** A block that can be placed against / stood on (also for block placing support). */
    public boolean supportive(int x, int y, int z) {
        byte s = shape(x, y, z);
        return s == Shape.FULL || s == Shape.HIGH;
    }

    /**
     * Standing height if a player's feet can be in cell (x,y,z), else NaN.
     * Handles low floors inside the cell (slabs, beds, carpets), floors from the cell below, ladders and water.
     */
    public double stand(int x, int y, int z) {
        byte feet = shape(x, y, z);
        if (feet == Shape.LOW) {
            double h = height(x, y, z);
            if (!passable(x, y + 1, z)) return NO_STAND;
            if (h > 0.2 && !passable(x, y + 2, z)) return NO_STAND;
            return y + h;
        }
        if (!Shape.passable(feet)) return NO_STAND;
        if (!passable(x, y + 1, z)) return NO_STAND;
        if (feet == Shape.CLIMB || feet == Shape.WATER) return y;
        byte below = shape(x, y - 1, z);
        if (below == Shape.FULL) return y;
        if (below == Shape.HIGH) return y - 1 + height(x, y - 1, z);
        return NO_STAND;
    }

    public boolean standable(int x, int y, int z) { return !Double.isNaN(stand(x, y, z)); }

    /** Nothing to land on in the next `depth` blocks below the feet cell (void / huge fall). */
    public boolean voidBelow(int x, int y, int z, int depth) {
        // a path search asks this (voidSides, depth 24) for every neighbour of every node it links: memoize
        if (caching && depth == 24) {
            long k = AIMath.pack(x, y, z);
            Boolean c = voidCache.get(k);
            if (c != null) return c;
            boolean r = voidBelowScan(x, y, z, depth);
            voidCache.put(k, r);
            return r;
        }
        return voidBelowScan(x, y, z, depth);
    }

    private final java.util.HashMap<Long, Boolean> voidCache = new java.util.HashMap<>();
    private final java.util.HashMap<Long, Boolean> lavaCache = new java.util.HashMap<>();

    private boolean voidBelowScan(int x, int y, int z, int depth) {
        int min = Math.max(0, y - depth);
        for (int yy = y - 1; yy >= min; yy--) {
            byte s = shape(x, yy, z);
            if (s != Shape.OPEN && s != Shape.CLIMB) return false;
        }
        return true;
    }

    /** First cell (feet y) at or below startY where one can stand, scanning down maxScan cells. Integer.MIN_VALUE if none. */
    public int landing(int x, int startY, int z, int maxScan) {
        // drop/bridge expansion asks this (depth 32) for every open neighbour over the void: memoize per search
        if (caching && maxScan == 32) {
            long k = AIMath.pack(x, startY, z);
            Integer c = landingCache.get(k);
            if (c != null) return c;
            int r = landingScan(x, startY, z, maxScan);
            landingCache.put(k, r);
            return r;
        }
        return landingScan(x, startY, z, maxScan);
    }

    private final java.util.HashMap<Long, Integer> landingCache = new java.util.HashMap<>();

    private int landingScan(int x, int startY, int z, int maxScan) {
        for (int yy = startY; yy >= Math.max(0, startY - maxScan); yy--) {
            byte s = shape(x, yy, z);
            if (!Shape.passable(s) && s != Shape.LOW) {
                // hit something solid: feet would be on top of it
                if (standable(x, yy + 1, z)) return yy + 1;
                return Integer.MIN_VALUE;
            }
            if (s == Shape.WATER && standable(x, yy, z)) return yy;
            if (s == Shape.LOW && standable(x, yy, z)) return yy;
        }
        return Integer.MIN_VALUE;
    }

    /** Count of the 4 horizontal neighbours at feet level that drop into the void (edge danger). */
    public int voidSides(int x, int y, int z) {
        int n = 0;
        if (passable(x + 1, y, z) && voidBelow(x + 1, y, z, 24)) n++;
        if (passable(x - 1, y, z) && voidBelow(x - 1, y, z, 24)) n++;
        if (passable(x, y, z + 1) && voidBelow(x, y, z + 1, 24)) n++;
        if (passable(x, y, z - 1) && voidBelow(x, y, z - 1, 24)) n++;
        return n;
    }

    public boolean lavaNear(int x, int y, int z) {
        if (caching) {
            long k = AIMath.pack(x, y, z);
            Boolean c = lavaCache.get(k);
            if (c != null) return c;
            boolean r = lavaNearScan(x, y, z);
            lavaCache.put(k, r);
            return r;
        }
        return lavaNearScan(x, y, z);
    }

    private boolean lavaNearScan(int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                for (int dy = -1; dy <= 1; dy++)
                    if (shape(x + dx, y + dy, z + dz) == Shape.LAVA) return true;
        return false;
    }

    /** Opaque for vision raycasts. */
    @SuppressWarnings("deprecation")
    public boolean occludes(int x, int y, int z) {
        if (y < 0 || y > 255 || !loaded(x, z)) return false;
        Material m = world.getBlockAt(x, y, z).getType();
        return m.isOccluding();
    }

    public Material type(int x, int y, int z) {
        if (y < 0 || y > 255 || !loaded(x, z)) return Material.AIR;
        return world.getBlockAt(x, y, z).getType();
    }
}
