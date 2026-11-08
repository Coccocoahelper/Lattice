package net.h2020.bots.ai.combat;

import net.h2020.bots.ai.util.AIMath;
import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.Random;

/**
 * 1.8 projectile flight (position += motion; motion *= drag; motionY -= gravity, per tick) and a solver for the
 * pitch that lands a projectile on a (moving) point. The bot only uses it the way a practised player "knows" the
 * arc: the lead is scaled by skill and a per-throw angular error is added, so nothing is laser-accurate.
 */
public final class Ballistics {
    /** {speed, gravity, drag} */
    public static final double[] THROWABLE = {1.5, 0.03, 0.99};
    public static final double[] PEARL = THROWABLE;
    public static final double[] HOOK = {1.5, 0.04, 0.92};
    public static final double[] SPLASH = {0.5, 0.05, 0.99};
    /** Fireballs: straight, constant speed, no gravity (Hypixel's ~26.9 blocks/s). */
    public static final double[] FIREBALL = {net.h2020.api.game.Projectiles.FIREBALL, 0.0, 1.0};

    private Ballistics() {}

    /** Arrow {speed, gravity, drag} after drawing the bow for the given ticks (ItemBow.onPlayerStoppedUsing). */
    public static double[] arrow(int drawTicks) {
        double f = drawTicks / 20.0;
        f = (f * f + f * 2.0) / 3.0;
        if (f > 1) f = 1;
        return new double[]{3.0 * f, 0.05, 0.99};
    }

    /**
     * Elevation (radians, + up) that hits height dy at horizontal distance h, low arc, plus the flight ticks.
     * {NaN, NaN} when out of range.
     */
    public static double[] solve(double h, double dy, double[] p) {
        double[] r = solve(h, dy, p, 45);
        // short and steep (a pearl back up onto the edge we fell from): allow a near-vertical throw
        if (Double.isNaN(r[0])) r = solve(h, dy, p, 75);
        if (Double.isNaN(r[0]) && h < 3) r = solve(h, dy, p, 88.5);
        return r;
    }

    private static double[] solve(double h, double dy, double[] p, double maxDeg) {
        double lo = Math.toRadians(-70), hi = Math.toRadians(maxDeg);
        double[] rHi = fly(hi, h, p);
        if (Double.isNaN(rHi[0]) || rHi[0] < dy) return new double[]{Double.NaN, Double.NaN};
        double t = rHi[1];
        for (int i = 0; i < 22; i++) {
            double mid = (lo + hi) / 2;
            double[] r = fly(mid, h, p);
            if (Double.isNaN(r[0]) || r[0] < dy) lo = mid; else { hi = mid; t = r[1]; }
        }
        return new double[]{(lo + hi) / 2, t};
    }

    /** {height at horizontal distance h, ticks}; height NaN if it never gets there in 120 ticks. */
    private static double[] fly(double elev, double h, double[] p) {
        double vx = p[0] * Math.cos(elev), vy = p[0] * Math.sin(elev);
        double x = 0, y = 0;
        for (int t = 1; t <= 120; t++) {
            double nx = x + vx, ny = y + vy;
            if (nx >= h) {
                double f = (h - x) / Math.max(1e-6, vx);
                return new double[]{y + vy * f, t - 1 + f};
            }
            x = nx; y = ny;
            vx *= p[2]; vy *= p[2]; vy -= p[1];
            if (vx < 1e-3) break;
        }
        return new double[]{Double.NaN, Double.NaN};
    }

    /**
     * A point to put the crosshair on so the projectile meets the target point moving at vel (blocks/tick).
     * leadSkill 0..1 scales the lead; errDeg is one sigma of angular error (both axes). Null when out of range.
     */
    public static Location aim(Location eye, Location target, Vector vel, double[] p, double leadSkill, double errDeg, Random r) {
        double t = 0;
        double tx = target.getX(), ty = target.getY(), tz = target.getZ();
        double[] sol = null;
        for (int it = 0; it < 3; it++) {
            tx = target.getX() + vel.getX() * t * leadSkill;
            tz = target.getZ() + vel.getZ() * t * leadSkill;
            ty = target.getY() + Math.max(-0.5, Math.min(0.5, vel.getY() * t * 0.5 * leadSkill));
            double h = Math.sqrt(AIMath.hDistSq(eye.getX(), eye.getZ(), tx, tz));
            sol = solve(Math.max(0.3, h), ty - (eye.getY() - 0.1), p);
            if (Double.isNaN(sol[0])) return null;
            t = sol[1];
        }
        float yaw = AIMath.yawTo(eye.getX(), eye.getZ(), tx, tz);
        double pitch = -Math.toDegrees(sol[0]);
        yaw += (float) (r.nextGaussian() * errDeg);
        pitch += r.nextGaussian() * errDeg * 0.8;
        Vector look = AIMath.lookVector(yaw, (float) AIMath.clamp(pitch, -89, 89));
        // a far point along the throw direction: the crosshair angle then barely changes as we move
        return eye.clone().add(look.multiply(40));
    }

    /** Degrees between the bot's crosshair and the direction from its eye to a point. */
    public static double aimErr(net.h2020.bots.ai.BotContext ctx, Location pt) {
        Location eye = ctx.eye();
        float y = AIMath.yawTo(eye.getX(), eye.getZ(), pt.getX(), pt.getZ());
        float pi = AIMath.pitchTo(eye.getX(), eye.getY(), eye.getZ(), pt.getX(), pt.getY(), pt.getZ());
        double dy = AIMath.wrap(y - ctx.c.yaw()), dp = pi - ctx.c.pitch();
        return Math.sqrt(dy * dy + dp * dp);
    }

    /** Flight ticks to a point (for timing), or -1 when unreachable. */
    public static double flightTicks(Location eye, Location target, double[] p) {
        double h = Math.sqrt(AIMath.hDistSq(eye.getX(), eye.getZ(), target.getX(), target.getZ()));
        double[] s = solve(Math.max(0.3, h), target.getY() - eye.getY(), p);
        return Double.isNaN(s[1]) ? -1 : s[1];
    }
}
