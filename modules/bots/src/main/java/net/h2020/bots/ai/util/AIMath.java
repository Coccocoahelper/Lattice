package net.h2020.bots.ai.util;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.Random;

/** Small math helpers shared by the AI. Minecraft convention: yaw 0 = +Z (south), 90 = -X (west); pitch +90 = down. */
public final class AIMath {
    private AIMath() {}

    public static float wrap(float deg) {
        deg %= 360f;
        if (deg >= 180f) deg -= 360f;
        if (deg < -180f) deg += 360f;
        return deg;
    }

    public static double wrap(double deg) {
        deg %= 360.0;
        if (deg >= 180.0) deg -= 360.0;
        if (deg < -180.0) deg += 360.0;
        return deg;
    }

    /** Signed smallest difference a-b in degrees. */
    public static float angleDiff(float a, float b) { return wrap(a - b); }

    public static float yawTo(double fromX, double fromZ, double toX, double toZ) {
        double dx = toX - fromX, dz = toZ - fromZ;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    public static float yawOf(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    public static float pitchTo(double fx, double fy, double fz, double tx, double ty, double tz) {
        double dx = tx - fx, dy = ty - fy, dz = tz - fz;
        double h = Math.sqrt(dx * dx + dz * dz);
        return (float) -Math.toDegrees(Math.atan2(dy, h));
    }

    public static float yawTo(Location from, Location to) { return yawTo(from.getX(), from.getZ(), to.getX(), to.getZ()); }

    /** Unit horizontal direction for a yaw. */
    public static double dirX(float yaw) { return -Math.sin(Math.toRadians(yaw)); }
    public static double dirZ(float yaw) { return Math.cos(Math.toRadians(yaw)); }

    public static Vector lookVector(float yaw, float pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        double cp = Math.cos(p);
        return new Vector(-Math.sin(y) * cp, -Math.sin(p), Math.cos(y) * cp);
    }

    public static double hDist(Location a, Location b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    public static double hDistSq(double ax, double az, double bx, double bz) {
        double dx = ax - bx, dz = az - bz;
        return dx * dx + dz * dz;
    }

    public static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }
    public static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
    public static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    public static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    /** Gaussian with mean/sd clamped to [lo,hi]. */
    public static double gauss(Random r, double mean, double sd, double lo, double hi) {
        return clamp(mean + r.nextGaussian() * sd, lo, hi);
    }

    /** Random integer tick count from a millisecond mean with ~25% sd, at least 1. */
    public static int msToTicks(Random r, double meanMs) {
        double ms = gauss(r, meanMs, meanMs * 0.25, meanMs * 0.4, meanMs * 2.2);
        return Math.max(1, (int) Math.round(ms / 50.0));
    }

    public static int range(Random r, int lo, int hiInclusive) {
        if (hiInclusive <= lo) return lo;
        return lo + r.nextInt(hiInclusive - lo + 1);
    }

    public static double range(Random r, double lo, double hi) { return lo + r.nextDouble() * (hi - lo); }

    public static boolean chance(Random r, double p) { return r.nextDouble() < p; }

    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** Smooth 1D noise in [-1,1] from a phase-shifted sum of sines (cheap "perlin-ish" weaving). */
    public static double weave(double t, double phase) {
        return (Math.sin(t * 0.9 + phase) * 0.55 + Math.sin(t * 2.3 + phase * 1.7) * 0.3 + Math.sin(t * 5.1 + phase * 0.6) * 0.15);
    }
}
