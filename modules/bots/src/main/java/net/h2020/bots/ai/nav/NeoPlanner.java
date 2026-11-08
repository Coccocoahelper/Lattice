package net.h2020.bots.ai.nav;

import net.h2020.bots.ai.BotContext;
import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.motor.Motor;
import net.h2020.bots.ai.util.AIMath;
import net.h2020.bots.ai.world.Shape;
import net.h2020.bots.ai.world.WorldView;
import org.bukkit.Location;

/**
 * Neo jumps: getting around a wall instead of over it.
 * <p>
 * The parkour wiki's definition is exact - a neo is a jump <i>around</i> a wall, named by the wall's length (a "2b neo"
 * is a two block wall), with variants for the landing (nix), the wall (winged), panes (butterfly) and corners (cross).
 * The mechanics that make it possible are all 1.8 vanilla:
 * <ul>
 * <li>jump height 1.249 blocks and a flat jump lasting 12 ticks, 9 for a +1 jump, 3 under a 2-block ceiling;</li>
 * <li>45 degree strafe - turning 45 degrees while strafing the matching direction - which adds up to 2% distance and,
 *   more importantly, makes the mid-air turn consistent;</li>
 * <li>enough run-up ("1 block of momentum" is 1.6m of travel) at full sprint before the jump.</li>
 * </ul>
 * This planner does exactly that: back up for runway if there is none, sprint, jump at the last moment that still
 * clears the wall, strafe 45 degrees through the turn, then re-aim at the landing and ride it in. It fails loudly
 * (returns FAILED) rather than walking into the wall, so the navigator can fall back to breaking or re-pathing.
 */
public final class NeoPlanner {
    public enum Result { RUNNING, DONE, FAILED }

    private enum Phase { BACKUP, SPRINT, AIR, SETTLE }

    /** Sprint-jump horizontal reach, and the extra a 45 degree strafe buys (the wiki measures ~2%). */
    private static final double JUMP_REACH = 4.0;
    private static final double STRAFE_BONUS = 0.02;

    private final BotContext ctx;
    private Phase phase = Phase.BACKUP;
    private int ticks, phaseTicks;
    private double sx, sz;                 // start of the run-up
    private double wallX, wallZ;           // centre of the wall we are going around
    private double lx, lz, ly;             // landing
    private boolean strafeLeft;
    private boolean active;

    public NeoPlanner(BotContext ctx) { this.ctx = ctx; }

    public boolean active() { return active; }

    /**
     * @param from    where the jump should take off from (the cell the bot is walking through)
     * @param wall    the blocking block (its centre)
     * @param landing the cell the bot should end on
     */
    public void start(Location from, Location wall, Location landing) {
        if (active) return;                       // one neo at a time: restarting every tick never gets off the ground
        this.sx = from.getX();
        this.sz = from.getZ();
        this.wallX = wall.getX();
        this.wallZ = wall.getZ();
        this.lx = landing.getX();
        this.lz = landing.getZ();
        this.ly = landing.getY();
        this.phase = Phase.BACKUP;
        this.ticks = 0;
        this.phaseTicks = 0;
        this.active = true;
        // the side to strafe is whichever puts the wall on the inside of the turn
        double dx = lx - sx, dz = lz - sz;
        double wx = wallX - sx, wz = wallZ - sz;
        strafeLeft = (dx * wz - dz * wx) > 0;
        CombatStats.inc("neo.start");
    }

    public void cancel() { if (!active) return; active = false; CombatStats.inc("neo.cancel"); }

    public Result tick() {
        if (!active) return Result.FAILED;
        ticks++;
        phaseTicks++;
        if (ticks > 60) { active = false; CombatStats.inc("neo.timeout"); return Result.FAILED; }
        WorldView wv = ctx.world();
        Location me = ctx.pos();

        // fell or went the wrong way: give up so the navigator can re-plan
        if (me.getY() < ly - 2.0) { active = false; CombatStats.inc("neo.fell"); return Result.FAILED; }

        switch (phase) {
            case BACKUP: {
                // runway: a sprint jump needs speed at take-off. If there is no room behind us, take what there is.
                double bx = sx - me.getX(), bz = sz - me.getZ();
                double back = Math.sqrt(bx * bx + bz * bz);
                if (back > 0.15) { ctx.motor.moveToward(bx, bz); ctx.motor.sprint(true); }
                if (back > 1.2 || phaseTicks > 8) { phase = Phase.SPRINT; phaseTicks = 0; }
                return Result.RUNNING;
            }
            case SPRINT: {
                double dx = lx - me.getX(), dz = lz - me.getZ();
                double dist = Math.sqrt(dx * dx + dz * dz);
                ctx.motor.moveToward(dx, dz);
                ctx.motor.sprint(true);
                double speed = Math.sqrt(ctx.c.velocity().getX() * ctx.c.velocity().getX()
                        + ctx.c.velocity().getZ() * ctx.c.velocity().getZ());
                // jump when sprinting and close enough that the arc still clears the wall
                if ((speed > 0.26 || phaseTicks > 10) && dist < JUMP_REACH + 0.6 && ctx.c.onGround()) {
                    ctx.motor.jump(true);
                    phase = Phase.AIR;
                    phaseTicks = 0;
                    CombatStats.inc("neo.jump");
                }
                if (phaseTicks > 20) { active = false; CombatStats.inc("neo.nospeed"); return Result.FAILED; }
                return Result.RUNNING;
            }
            case AIR: {
                // 45 degree strafe through the turn: aim at the landing, strafe the inside of the corner
                double dx = lx - me.getX(), dz = lz - me.getZ();
                ctx.motor.moveToward(dx, dz);
                ctx.motor.move(1, strafeLeft ? -1 : 1);
                ctx.motor.sprint(true);
                if (ctx.c.onGround() && phaseTicks > 2) { phase = Phase.SETTLE; phaseTicks = 0; }
                return Result.RUNNING;
            }
            case SETTLE:
            default: {
                double dx = lx - me.getX(), dz = lz - me.getZ();
                if (Math.sqrt(dx * dx + dz * dz) > 0.6) ctx.motor.moveToward(dx, dz);
                else ctx.motor.move(0, 0);
                if (ctx.c.onGround() && Math.abs(me.getY() - ly) < 1.4 && phaseTicks > 1) {
                    active = false;
                    CombatStats.inc("neo.ok");
                    return Result.DONE;
                }
                if (phaseTicks > 12) { active = false; CombatStats.inc("neo.miss"); return Result.FAILED; }
                return Result.RUNNING;
            }
        }
    }

    private static boolean landingOk(WorldView wv, int x, int y, int z) {
        return Shape.passable(wv.shape(x, y, z)) && Shape.passable(wv.shape(x, y + 1, z))
                && Shape.floor(wv.shape(x, y - 1, z));
    }

    /**
     * Is there a neo worth attempting? True when a direct step toward `to` is blocked by a wall we can go around:
     * one or two blocks tall, with a passable landing cell within sprint-jump reach on the far side.
     */
    public static boolean candidate(BotContext ctx, Location from, Location to) {
        WorldView wv = ctx.world();
        int fx = (int) Math.floor(from.getX()), fy = (int) Math.floor(from.getY()), fz = (int) Math.floor(from.getZ());
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.5) return false;
        dx /= len; dz /= len;
        int wx = (int) Math.floor(from.getX() + dx), wz = (int) Math.floor(from.getZ() + dz);
        if (Shape.passable(wv.shape(wx, fy, wz))) return false;                 // nothing in the way
        if (!Shape.passable(wv.shape(wx, fy + 2, wz))) return false;            // wall is too tall to jump around
        // (a) straight past it: 2-4 blocks beyond, clear at feet and head, with ground under it
        for (double reach = 2.0; reach <= JUMP_REACH; reach += 0.5) {
            int tx = (int) Math.floor(from.getX() + dx * (1 + reach));
            int tz = (int) Math.floor(from.getZ() + dz * (1 + reach));
            if (landingOk(wv, tx, fy, tz)) return true;
        }
        // (b) around its end: the actual neo - the corner cell and the landing sit to one side of the travel line,
        // which is what makes the turn happen in the air rather than on top of the wall
        double nx = -dz, nz = dx;
        for (int side : new int[]{1, -1}) {
            for (double fwd = 1.0; fwd <= 3.0; fwd += 1.0) {
                for (double out = 1.0; out <= 2.5; out += 0.5) {
                    int cx = (int) Math.floor(from.getX() + dx * (1 + fwd) + nx * side * out);
                    int cz = (int) Math.floor(from.getZ() + dz * (1 + fwd) + nz * side * out);
                    if (landingOk(wv, cx, fy, cz)) return true;
                }
            }
        }
        return false;
    }
}
