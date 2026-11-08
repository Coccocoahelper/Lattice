package net.h2020.bots.ai.world;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * Voxel line-of-sight (Amanatides-Woo DDA) against opaque blocks. A global per-tick budget keeps the cost bounded
 * no matter how many bots are looking around; when the budget is exhausted callers get {@link #UNKNOWN}.
 */
public final class Raycast {
    public static final int CLEAR = 1, BLOCKED = 0, UNKNOWN = -1;

    private static int budget;
    private static int perTick = 240;

    private Raycast() {}

    public static void resetBudget() { budget = perTick; }

    public static void setPerTick(int n) { perTick = Math.max(16, n); }

    public static int lineOfSight(World w, Location from, Location to) {
        if (budget <= 0) return UNKNOWN;
        budget--;
        return trace(w, from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ()) ? CLEAR : BLOCKED;
    }

    /** True when no occluding block lies on the segment. */
    @SuppressWarnings("deprecation")
    public static boolean trace(World w, double x0, double y0, double z0, double x1, double y1, double z1) {
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) return true;
        dx /= len; dy /= len; dz /= len;
        int x = floor(x0), y = floor(y0), z = floor(z0);
        int ex = floor(x1), ey = floor(y1), ez = floor(z1);
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double tdx = dx == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dx);
        double tdy = dy == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dy);
        double tdz = dz == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dz);
        double tmx = dx == 0 ? Double.MAX_VALUE : ((sx > 0 ? (x + 1 - x0) : (x0 - x)) * tdx);
        double tmy = dy == 0 ? Double.MAX_VALUE : ((sy > 0 ? (y + 1 - y0) : (y0 - y)) * tdy);
        double tmz = dz == 0 ? Double.MAX_VALUE : ((sz > 0 ? (z + 1 - z0) : (z0 - z)) * tdz);
        int steps = 0;
        while (steps++ < 160) {
            if (x == ex && y == ey && z == ez) return true;
            if (tmx < tmy && tmx < tmz) { if (tmx > len) return true; x += sx; tmx += tdx; }
            else if (tmy < tmz) { if (tmy > len) return true; y += sy; tmy += tdy; }
            else { if (tmz > len) return true; z += sz; tmz += tdz; }
            if (x == ex && y == ey && z == ez) return true;
            if (y < 0 || y > 255) continue;
            if (!w.isChunkLoaded(x >> 4, z >> 4)) return false;
            if (w.getBlockAt(x, y, z).getType().isOccluding()) return false;
        }
        return true;
    }

    private static int floor(double d) { int i = (int) d; return d < i ? i - 1 : i; }
}
