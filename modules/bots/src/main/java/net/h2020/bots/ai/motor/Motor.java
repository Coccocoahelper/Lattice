package net.h2020.bots.ai.motor;

import net.h2020.api.stats.CombatStats;
import net.h2020.api.bot.AimIntent;
import net.h2020.api.bot.BotController;
import net.h2020.bots.ai.util.AIMath;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * Per-tick input arbitration between AI components. Components write desired keys and prioritised aim requests
 * during the brain's tick; flush() applies them once to the BotController. Keys are "held" only while someone keeps
 * asking for them, so no component can leave a stale key pressed. Aim requests are de-duplicated so the controller's
 * human aim model is not restarted every tick by an almost identical target.
 */
public final class Motor {
    public static final int P_IDLE = 10, P_GLANCE = 20, P_NAV = 30, P_LOOK = 35, P_TASK = 40, P_COMBAT = 50, P_FLICK = 60;

    private final BotController c;

    // desired this tick
    private float forward, strafe;
    private boolean jump, sneak, sprint, left, right;
    private double cps;

    // applied state
    private float aForward = Float.NaN, aStrafe = Float.NaN;
    private Boolean aJump, aSneak, aSprint, aLeft, aRight;
    private double aCps = -1;

    // aim request this tick
    private int prio = -1;
    private int kind; // 1 entity, 2 point, 3 rotation
    private Entity ent;
    private double px, py, pz;
    private float ry, rp;
    private AimIntent intent;
    private double tolerance;

    // last applied aim
    private int lKind;
    private Entity lEnt;
    private double lx, ly, lz;
    private float lYaw, lPitch;
    private AimIntent lIntent;
    private int ticksSinceAim;
    private int lastPrio = -1;

    private int sector = 0;

    public Motor(BotController c) { this.c = c; }

    public BotController controller() { return c; }

    public void beginTick() {
        forward = 0; strafe = 0; jump = false; sneak = false; sprint = false; left = false; right = false; cps = 0;
        prio = -1;
        ticksSinceAim++;
    }

    // ---------------------------------------------------------------- keys
    public void move(float f, float s) { forward = f; strafe = s; }
    public float forward() { return forward; }
    public float strafe() { return strafe; }
    public void jump(boolean j) {
        // zest tapping is a grounded combo: the W-tap and the burst only work with both feet on the block, so while
        // one is engaged the jump key is refused no matter which system asked for it
        if (j && net.h2020.bots.ai.combat.ZestTap.grounded) {
            net.h2020.api.stats.CombatStats.inc("zest.nojump");
            return;
        }
        jump = j;
    }
    public void sneak(boolean s) { sneak = s; }
    public void sprint(boolean s) { sprint = s; }
    public boolean sneaking() { return sneak; }
    public void holdLeft(double clicksPerSecond) { left = true; cps = clicksPerSecond; }
    public void holdRight() { right = true; }

    /** Keys for walking in a world direction given the current crosshair yaw (8 key combos, with hysteresis). */
    public void moveToward(double dx, double dz) {
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-4) { move(0, 0); return; }
        float yaw = c.yaw();
        double rad = Math.toRadians(yaw);
        double sin = Math.sin(rad), cos = Math.cos(rad);
        double f = (dx * -sin + dz * cos) / len;
        double s = (dx * cos + dz * sin) / len;
        double ang = Math.toDegrees(Math.atan2(s, f));
        double cur = sector * 45.0;
        if (Math.abs(AIMath.wrap(ang - cur)) > 30.0) {
            int sec = (int) Math.round(ang / 45.0);
            if (sec == -4) sec = 4;
            sector = sec;
        }
        switch (sector) {
            case 0: move(1, 0); break;
            case 1: move(1, 1); break;
            case 2: move(0, 1); break;
            case 3: move(-1, 1); break;
            case 4: case -4: move(-1, 0); break;
            case -1: move(1, -1); break;
            case -2: move(0, -1); break;
            case -3: move(-1, -1); break;
            default: move(1, 0);
        }
    }

    // ---------------------------------------------------------------- aim
    public boolean aimEntity(int p, Entity e, AimIntent i) {
        if (p <= prio || e == null) return false;
        prio = p; kind = 1; ent = e; intent = i;
        return true;
    }

    public boolean aimPoint(int p, Location l, AimIntent i, double tol) {
        if (p <= prio || l == null) return false;
        prio = p; kind = 2; px = l.getX(); py = l.getY(); pz = l.getZ(); intent = i; tolerance = tol;
        return true;
    }

    public boolean aimPoint(int p, Location l, AimIntent i) {
        return aimPoint(p, l, i, i == AimIntent.PRECISE ? 0.12 : 0.6);
    }

    public boolean aimRotation(int p, float yaw, float pitch, AimIntent i, double tolDeg) {
        if (p <= prio) return false;
        prio = p; kind = 3; ry = yaw; rp = AIMath.clamp(pitch, -90f, 90f); intent = i; tolerance = tolDeg;
        return true;
    }

    public int claimedPriority() { return prio; }

    /** Abandon the controller's current aim target (used when idle so the head stops tracking a stale target). */
    public void clearAim() {
        c.clearAim();
        lKind = 0; lEnt = null; lIntent = null; lastPrio = -1;
    }

    public int ticksSinceAim() { return ticksSinceAim; }

    // ---------------------------------------------------------------- apply
    public void flush() {
        if (aForward != forward || aStrafe != strafe) { c.setMovement(forward, strafe); aForward = forward; aStrafe = strafe; }
        if (aJump == null || aJump != jump) { c.setJumping(jump); aJump = jump; }
        if (aSneak == null || aSneak != sneak) { c.setSneaking(sneak); aSneak = sneak; }
        boolean sp = sprint && forward > 0 && !sneak;
        if (aSprint == null || aSprint != sp) { c.setSprinting(sp); aSprint = sp; }
        if (aLeft == null || aLeft != left || (left && Math.abs(aCps - cps) > 0.25)) {
            c.setLeftHeld(left, cps); aLeft = left; aCps = cps;
        }
        if (aRight == null || aRight != right) { c.setRightHeld(right); aRight = right; }
        applyAim();
    }

    private void applyAim() {
        if (prio < 0) return;
        boolean same = lKind == kind && lIntent == intent;
        switch (kind) {
            case 1:
                if (same && lEnt == ent) return;
                c.aimAt(ent, intent);
                lEnt = ent;
                break;
            case 2: {
                if (same) {
                    double dx = px - lx, dy = py - ly, dz = pz - lz;
                    if (dx * dx + dy * dy + dz * dz < tolerance * tolerance) return;
                }
                c.aimAt(new Location(c.position().getWorld(), px, py, pz), intent);
                lx = px; ly = py; lz = pz;
                break;
            }
            case 3:
                if (same && Math.abs(AIMath.wrap(ry - lYaw)) < tolerance && Math.abs(rp - lPitch) < tolerance) return;
                c.aimRotation(ry, rp, intent);
                lYaw = ry; lPitch = rp;
                break;
            default:
                return;
        }
        lKind = kind;
        lIntent = intent;
        lastPrio = prio;
        ticksSinceAim = 0;
    }

    /** Release everything immediately (death, spectating, brain stop). */
    public void halt() {
        beginTick();
        c.setMovement(0, 0); c.setJumping(false); c.setSneaking(false); c.setSprinting(false);
        c.setLeftHeld(false, 0); c.setRightHeld(false);
        aForward = 0; aStrafe = 0; aJump = false; aSneak = false; aSprint = false; aLeft = false; aRight = false;
        clearAim();
    }

    public int lastAppliedPriority() { return lastPrio; }
}
