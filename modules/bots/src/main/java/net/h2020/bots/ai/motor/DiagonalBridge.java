package net.h2020.bots.ai.motor;

import net.h2020.api.bot.AimIntent;
import net.h2020.bots.ai.BotContext;
import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.util.AIMath;
import net.h2020.bots.ai.world.Shape;
import net.h2020.bots.ai.world.WorldView;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

/**
 * Diagonal bridging, as its own movement rather than a special case of the straight bridge.
 * <p>
 * The stance is the one the movement guides use: the bot faces <i>back down the bridge</i> and travels with the
 * <b>S</b> key, so its body backs off the lip before each click. That matters for two reasons the straight bridger
 * cannot reproduce:
 * <ul>
 * <li>the eye is already out over the void when the click happens, which is exactly the geometry that puts the
 *   support's travel-side face in the ray - standing on top of the new block puts its far face out of reach instead;</li>
 * <li>because the stance is correct at the lip, a <b>second</b> block can go down from the same stance on the
 *   perpendicular face, so a diagonal advances two cells per stop instead of one.</li>
 * </ul>
 * Placement is still one click at a time through the client, with the same 79-89 degree pitch window as the straight
 * bridge: a flatter ray misses the face.
 */
public final class DiagonalBridge {
    public enum Result { RUNNING, DONE, FAILED }

    private enum Phase { TURN, EDGE, CONFIRM, PAIR, SETTLE }

    private static final double LIP = 0.95;   // far enough past the corner that an 86 degree ray meets the side face          // a crouched body stops about a third of a block past centre

    private final BotContext ctx;

    private int vx, vz;                              // travel direction, both non-zero
    private int sx, sy, sz;                          // support block (the one the next block is placed against)
    private int stepX, stepZ;                        // the axis the current cell advances on
    private int placed, needed;
    private int startY;
    private Phase phase = Phase.TURN;
    private int ticks, phaseTicks, clickWait;
    private long lastClick = -100;
    private float yawBack, pitch;
    private boolean active;
    private boolean pairPending;

    public DiagonalBridge(BotContext ctx) { this.ctx = ctx; }

    public boolean active() { return active; }
    public int placed() { return placed; }

    public void start(int dx, int dz, int fx, int fy, int fz, int count) {
        if (dx == 0 || dz == 0) return;               // this class is only for true diagonals
        this.vx = Integer.signum(dx);
        this.vz = Integer.signum(dz);
        this.sx = fx;
        this.sy = fy - 1;                             // the block under the takeoff cell is the first support
        this.sz = fz;
        this.stepX = vx;
        this.stepZ = 0;
        this.needed = Math.max(2, count);
        this.startY = fy;
        this.placed = 0;
        this.ticks = 0;
        this.phaseTicks = 0;
        this.clickWait = 0;
        this.pairPending = false;
        this.phase = Phase.TURN;
        this.active = true;
        // facing back down the bridge: the body backs off the lip with S while the head looks at the support
        this.yawBack = AIMath.wrap(AIMath.yawOf(-vx, -vz));
        this.pitch = 86.5f;
        CombatStats.inc("diag2.start");
    }

    public void cancel() { active = false; CombatStats.inc("diag2.cancel"); }

    public Result tick() {
        if (!active) return Result.FAILED;
        ticks++;
        phaseTicks++;
        if (ticks > 20 * 60) { active = false; CombatStats.inc("diag2.timeout"); return Result.FAILED; }
        // a chest left open freezes keys and mouse: close it before doing anything else
        if (ctx.c.containerOpen()) { ctx.c.closeWindow(); return Result.RUNNING; }
        Location pos = ctx.pos();
        if (pos.getY() < startY - 3) { active = false; CombatStats.inc("diag2.fell"); return Result.FAILED; }
        if (!Inv.holding(ctx.player, Inv.BLOCK) && Inv.findHotbar(ctx.player, Inv.BLOCK) < 0) {
            active = false;
            CombatStats.inc("diag2.noblocks");
            return Result.FAILED;
        }
        if (!Inv.holding(ctx.player, Inv.BLOCK)) Inv.equip(ctx.player, ctx.c, Inv.BLOCK, 1);

        // the cell being filled, and the face of the support that fills it
        int nx = sx + stepX, nz = sz + stepZ;
        BlockFace want = faceOf(stepX, stepZ);
        double along = along(pos, sx, sz);

        switch (phase) {
            case TURN: {
                ctx.motor.sneak(true);
                ctx.motor.aimRotation(Motor.P_TASK, yawBack, pitch, AimIntent.PRECISE, 0.5);
                if (phaseTicks >= 4 || ctx.c.aimSettled()) { phase = Phase.EDGE; phaseTicks = 0; }
                return Result.RUNNING;
            }
            case EDGE: {
                // back off the lip with S, crouched: the eye has to be out over the void before the click
                ctx.motor.sneak(true);
                ctx.motor.aimRotation(Motor.P_TASK, yawBack, pitch, AimIntent.PRECISE, 0.5);
                if (along < LIP) ctx.motor.move(-1, 0);   // S: travel forward while facing back, crouched
                else ctx.motor.move(0, 0);
                Block tb = ctx.c.targetedBlock();
                BlockFace tf = ctx.c.targetedFace();
                if (phaseTicks % 20 == 0) {
                    org.bukkit.Bukkit.getLogger().info("[Bots] diag2 probe bot=" + ctx.player.getName()
                            + " along=" + String.format("%.2f", along) + " support=" + sx + "," + sy + "," + sz
                            + " ray=" + (tb == null ? "null" : tb.getX() + "," + tb.getY() + "," + tb.getZ()) + "/" + tf
                            + " want=" + want + " eye=" + String.format("%.2f,%.2f,%.2f", pos.getX(), pos.getY(), pos.getZ()));
                }
                boolean rayOn = tb != null && tb.getX() == sx && tb.getY() == sy && tb.getZ() == sz && tf == want;
                if (rayOn && ctx.tick - lastClick > 3 && ctx.world().passable(nx, sy, nz)) {
                    if (clickWait > 0) { clickWait--; }
                    else {
                        ctx.c.rightClick();
                        lastClick = ctx.tick;
                        clickWait = 0;
                        phase = Phase.CONFIRM;
                        phaseTicks = 0;
                        return Result.RUNNING;
                    }
                } else if (clickWait == 0) {
                    clickWait = AIMath.range(ctx.rng, 0, 1);
                }
                if (phaseTicks > 60) { active = false; CombatStats.inc("diag2.stuck"); return Result.FAILED; }
                return Result.RUNNING;
            }
            case CONFIRM: {
                // did the block actually go down? the server has the last word
                ctx.motor.sneak(true);
                if (Shape.floor(ctx.world().shape(nx, sy, nz))) {
                    sx = nx;
                    sz = nz;
                    placed++;
                    CombatStats.inc("diag2.place");
                    // flip to the other axis for the diagonal's next cell
                    if (stepX != 0) { stepX = 0; stepZ = vz; } else { stepX = vx; stepZ = 0; }
                    pairPending = true;
                    phase = Phase.PAIR;
                    phaseTicks = 0;
                    return Result.RUNNING;
                }
                if (phaseTicks > 10) {
                    active = false;
                    CombatStats.inc("diag2.rejected");
                    return Result.FAILED;
                }
                return Result.RUNNING;
            }
            case PAIR: {
                // second cell of the pair, same stance: the block just placed is now the support
                ctx.motor.sneak(true);
                int px = sx + stepX, pz = sz + stepZ;
                BlockFace want2 = faceOf(stepX, stepZ);
                float yaw2 = AIMath.wrap(AIMath.yawOf(-stepX, -stepZ));
                ctx.motor.aimRotation(Motor.P_TASK, yaw2, pitch, AimIntent.PRECISE, 0.7);
                Block tb = ctx.c.targetedBlock();
                BlockFace tf = ctx.c.targetedFace();
                boolean rayOn = tb != null && tb.getX() == sx && tb.getY() == sy && tb.getZ() == sz && tf == want2;
                if (rayOn && ctx.world().passable(px, sy, pz) && ctx.tick - lastClick > 3) {
                    ctx.c.rightClick();
                    lastClick = ctx.tick;
                    if (Shape.floor(ctx.world().shape(px, sy, pz))) {
                        sx = px;
                        sz = pz;
                        placed++;
                        CombatStats.inc("diag2.pair");
                    } else {
                        CombatStats.inc("diag2.pair.late");
                    }
                    if (phaseTicks > 10) { pairPending = false; phase = Phase.SETTLE; phaseTicks = 0; }
                    return Result.RUNNING;
                }
                if (phaseTicks > 12) {
                    pairPending = false;
                    CombatStats.inc("diag2.pair.miss");
                    phase = Phase.SETTLE;
                    phaseTicks = 0;
                }
                return Result.RUNNING;
            }
            case SETTLE:
            default: {
                ctx.motor.sneak(true);
                ctx.motor.move(-1, 0);                    // walk onto what was just placed
                if (ctx.c.onGround() && Shape.floor(ctx.world().shape(
                        (int) Math.floor(pos.getX()), (int) Math.floor(pos.getY() - 0.2), (int) Math.floor(pos.getZ())))) {
                    // the support is the block under the feet now
                    sx = (int) Math.floor(pos.getX());
                    sy = (int) Math.floor(pos.getY() - 0.05);
                    sz = (int) Math.floor(pos.getZ());
                    if (placed >= needed) { active = false; CombatStats.inc("diag2.done"); return Result.DONE; }
                    phase = Phase.EDGE;
                    phaseTicks = 0;
                }
                if (phaseTicks > 30) { phase = Phase.EDGE; phaseTicks = 0; }
                return Result.RUNNING;
            }
        }
    }

    /** How far the eye is past the support centre along the travel direction. */
    private double along(Location eye, int bx, int bz) {
        double ex = eye.getX() - (bx + 0.5), ez = eye.getZ() - (bz + 0.5);
        double len = Math.sqrt(vx * vx + vz * vz);
        return (ex * vx + ez * vz) / len;
    }

    /** The face of the support that the next cell is placed against. */
    private static BlockFace faceOf(int dx, int dz) {
        if (dx > 0) return BlockFace.EAST;
        if (dx < 0) return BlockFace.WEST;
        if (dz > 0) return BlockFace.SOUTH;
        return BlockFace.NORTH;
    }
}
