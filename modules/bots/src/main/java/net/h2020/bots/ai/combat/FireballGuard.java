package net.h2020.bots.ai.combat;

import net.h2020.api.stats.CombatStats;
import net.h2020.api.bot.AimIntent;
import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.motor.Motor;
import net.h2020.bots.ai.world.Shape;
import net.h2020.bots.ai.world.WorldView;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.util.Vector;

/**
 * Fireballs coming at us. A fireball flies straight (no gravity) at 0.8 blocks a tick, so what matters is the closest
 * approach of the line to our body and the time to it:
 * <ul>
 * <li>good players punch it back: they swing at it while it is inside weapon reach (the server bounces a fireball that
 * is hit with a sword - see Gadgets.deflect), which is safer than dodging on a one-wide bridge;</li>
 * <li>everybody else (and when a hit is not realistic: too far, already past the crosshair, no clear line) steps out of
 * the line: sideways if there is ground there, otherwise straight back along the fireball's own direction, and never
 * into the void to do it.</li>
 * </ul>
 * Runs inside Combat before melee/navigation so it can take the tick over.
 */
final class FireballGuard {
    private final BotContext ctx;
    private final Style style;

    private Fireball threat;
    private long threatUntil;
    private int aimTicks;
    private long lastLog = -1000;
    private long cooldownUntil;

    FireballGuard(BotContext ctx, Style style) { this.ctx = ctx; this.style = style; }

    /** A fireball is inbound and close enough to act on. */
    boolean active() { return threat != null && ctx.tick < threatUntil; }

    /**
     * Look at the fireballs around us. @return true when one of them drives our inputs this tick.
     */
    boolean tick() {
        if (!CombatTuning.fireballPlay) { threat = null; return false; }
        Location eye = ctx.eye();
        Fireball best = null;
        double bestScore = Double.MAX_VALUE;
        double vx = 0, vy = 0, vz = 0, perp = 0, tti = 0;
        for (Entity e : ctx.player.getNearbyEntities(16, 10, 16)) {
            if (!(e instanceof Fireball) || e.isDead()) continue;
            Location f = e.getLocation();
            Vector v = e.getVelocity();
            double sp2 = v.lengthSquared();
            if (sp2 < 0.05) continue;
            double rx = f.getX() - eye.getX(), ry = f.getY() - eye.getY(), rz = f.getZ() - eye.getZ();
            double t = -(rx * v.getX() + ry * v.getY() + rz * v.getZ()) / sp2;
            if (t < 0 || t > 22) continue;                       // not coming at us (or already past by > 1s)
            double px = rx + v.getX() * t, py = ry + v.getY() * t, pz = rz + v.getZ() * t;
            double miss = Math.sqrt(px * px + py * py + pz * pz);
            if (miss > 2.6) continue;                            // will pass beside us
            double dist = Math.sqrt(rx * rx + ry * ry + rz * rz);
            double score = t + miss * 2.0;
            if (score < bestScore) {
                bestScore = score;
                best = (Fireball) e;
                vx = v.getX(); vy = v.getY(); vz = v.getZ();
                perp = miss;
                tti = t;
            }
        }
        if (best == null) { threat = null; return false; }
        threat = best;
        threatUntil = ctx.tick + Math.max(3, (long) tti + 4);

        Location f = best.getLocation();
        Vector v = best.getVelocity();
        double dist = eye.distance(f);
        boolean canDeflect = style.fireballDeflect > 0.35 && dist < 3.7 && ctx.tick >= cooldownUntil;
        if (canDeflect) {
            // swing at where it will be when the swing lands (it moves 0.8/tick, our swing has half a ping of lag)
            double lag = 0.5 + ctx.pingMs() / 100.0;
            Location at = f.clone().add(v.clone().multiply(lag));
            ctx.motor.aimPoint(Motor.P_FLICK, at, AimIntent.FLICK, 0.35);
            Entity seen = ctx.c.targetedEntity();
            aimTicks++;
            boolean onIt = seen == best;
            if (onIt || (ctx.c.aimSettled() && aimTicks > 1) || aimTicks > 6) {
                ctx.c.leftClick();
                CombatStats.inc("fireball.deflect.try");
                cooldownUntil = ctx.tick + 10;
                aimTicks = 0;
                if (ctx.tick - lastLog > 40) { lastLog = ctx.tick; CombatStats.log("deflect at " + String.format("%.2f", dist)); }
                return true;
            }
            // mid-flick: stand still so the aim model does not fight the movement keys
            return true;
        }
        aimTicks = 0;
        return dodge(f, vx, vy, vz, perp, tti);
    }

    /** Step out of the line: sideways onto ground if possible, otherwise straight back away from it. */
    private boolean dodge(Location f, double vx, double vy, double vz, double perp, double tti) {
        Location me = ctx.pos();
        double l = Math.sqrt(vx * vx + vz * vz);
        if (l < 1e-4) return false;
        double dx = vx / l, dz = vz / l;         // direction the fireball travels
        WorldView wv = ctx.world();
        // candidate escapes: both perpendiculars (prefer the side with ground), then straight back
        double[][] cands = {
                {-dz, dx}, {dz, -dx}, {-dx, -dz}
        };
        double bestX = 0, bestZ = 0;
        double bestLen = 0;
        for (double[] c : cands) {
            if (len(wv, me, c[0], c[1]) > bestLen) { bestLen = len(wv, me, c[0], c[1]); bestX = c[0]; bestZ = c[1]; }
            if (bestLen >= 2.0) break;
        }
        if (bestLen < 0.5) return false;                          // boxed in: nothing to do but hold the ground
        ctx.motor.moveToward(bestX * 1.0, bestZ * 1.0);
        ctx.motor.sprint(false);
        // on a one-wide bridge never sprint off it: the step is short and deliberate
        // (only a bridge step, where the foot has to stay on the line, is crouched - never an open edge)
        CombatStats.inc("fireball.dodge");
        CombatStats.log(String.format("dodge tti=%.1f perp=%.2f", tti, perp));
        return true;
    }

    /** How far we can walk in world direction (x,z) before there is no ground left. */
    private double len(WorldView wv, Location me, double x, double z) {
        double l = Math.sqrt(x * x + z * z);
        if (l < 1e-4) return 0;
        x /= l; z /= l;
        int y = floor(me.getY() + 0.01);
        double reach = 0;
        for (double r = 1.0; r <= 3.0; r += 1.0) {
            int cx = floor(me.getX() + x * r), cz = floor(me.getZ() + z * r);
            byte s = wv.shape(cx, y, cz);
            if (s == Shape.OPEN || s == Shape.WATER) {
                if (wv.standable(cx, y - 1, cz) || wv.standable(cx, y, cz)) reach = r;
                else if (wv.landing(cx, y - 1, cz, 4) != Integer.MIN_VALUE) reach = r;
                else break;
            } else break;
        }
        return reach;
    }

    private static int floor(double d) { int i = (int) d; return d < i ? i - 1 : i; }
}
