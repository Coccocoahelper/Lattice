package net.h2020.bots.ai.brain.bedwars;

import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.motor.BlockPlacer;
import net.h2020.bots.ai.world.Shape;
import net.h2020.bots.ai.world.WorldView;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.util.Vector;

/**
 * Blocks while travelling, not fighting: the two things a 2020 player does on a bridge besides walking it.
 * <ul>
 * <li><b>Bridge repair</b>: somebody broke a hole in the line we are crossing (or we are crossing a line our own void
 *   took out). Instead of jumping the gap over the void, put a block back into it - that is the "place blocks to
 *   parkour a blocked bridge" case: the cheapest, safest way across a holed bridge.</li>
 * <li><b>Head hitter</b>: a block two cells above the head (placed from a side support, so only where one exists) on a
 *   bridge with an enemy around. Jumping under it gives the momentum and the reduced knockback that makes crossing a
 *   contested bridge less of a coin flip.</li>
 * </ul>
 * The brain calls {@link #auto()} every tick it is not fighting; when it returns true it has the tick (and the body).
 */
public final class TravelBlocks {
    private final BotContext ctx;
    private final BlockPlacer placer;

    private static final int NONE = 0, HEAD = 1, REPAIR = 2;
    private int mode = NONE;
    private long started;
    private long lastHead = -10000, lastRepair = -10000;
    private long jumpUntil = -1;

    public TravelBlocks(BotContext ctx) {
        this.ctx = ctx;
        this.placer = new BlockPlacer(ctx);
    }

    public boolean busy() { return mode != NONE; }

    public void cancel() {
        if (mode != NONE) placer.cancel();
        mode = NONE;
    }

    /** Tick the placement in progress, or start one. @return true while it drives the inputs. */
    public boolean auto() {
        if (mode != NONE) return tick();
        return consider() && tick();
    }

    /** Jump under a head hitter that is up. */
    public boolean wantsJump() { return ctx.tick < jumpUntil; }

    private boolean tick() {
        if (ctx.tick - started > 50) { cancel(); return false; }
        BlockPlacer.Result r = placer.tick();
        if (r == BlockPlacer.Result.RUNNING) {
            if (mode == HEAD && ctx.c.onGround()) ctx.motor.jump(true);
            return true;
        }
        int was = mode;
        mode = NONE;
        if (was == HEAD && r == BlockPlacer.Result.DONE) jumpUntil = ctx.tick + 40;
        return false;
    }

    private boolean consider() {
        if (ctx.nav.doingAction() || !ctx.c.onGround() || !Inv.has(ctx.player, Inv.BLOCK)) return false;
        Location me = ctx.pos();
        WorldView wv = ctx.world();
        int x = floor(me.getX()), y = floor(me.getY() + 0.01), z = floor(me.getZ());
        if (wv.voidSides(x, y, z) < 2) return false;          // only on a line over the void
        Vector v = ctx.c.velocity();
        double speed = Math.sqrt(v.getX() * v.getX() + v.getZ() * v.getZ());

        // 1) hole in the line ahead: put the floor block back instead of jumping the gap
        if (ctx.tick - lastRepair > 40 && speed < 0.34) {
            int[] dir = heading(v, x, z);
            if (dir != null) {
                for (int step = 2; step <= 4; step++) {
                    int hx = x + dir[0] * step, hz = z + dir[1] * step;
                    if (!wv.passable(hx, y, hz)) break;                       // the line continues: nothing to fix
                    Material floor = wv.type(hx, y - 1, hz);
                    if (Shape.floor(wv.shape(hx, y - 1, hz)) && floor != Material.AIR) continue;
                    if (!wv.voidBelow(hx, y - 1, hz, 30)) continue;           // just a dip, not a hole
                    if (BlockPlacer.findSupport(wv, hx, y - 1, hz, ctx.eye(), 4.4, true) == null) break;
                    lastRepair = ctx.tick;
                    started = ctx.tick;
                    placer.setNeedSight(true);
                    placer.start(hx, y - 1, hz, Inv.BLOCK);
                    mode = REPAIR;
                    return true;
                }
            }
        }
        // 2) head hitter on a contested bridge (needs a side support to place against)
        if (ctx.tick - lastHead > 400 && ctx.persona.skill.overall > 0.45 && ctx.perception.enemiesWithin(30) > 0) {
            int hy = y + 3;
            if (wv.passable(x, hy, z) && BlockPlacer.findSupport(wv, x, hy, z, ctx.eye(), 4.4, true) != null
                    && BlockPlacer.cellFree(new Location(me.getWorld(), x, hy, z))) {
                lastHead = ctx.tick;
                started = ctx.tick;
                placer.setNeedSight(true);
                placer.start(x, hy, z, Inv.BLOCK);
                mode = HEAD;
                return true;
            }
        }
        return false;
    }

    /** Cardinal direction we are travelling in (from velocity, else from where we are looking). */
    private int[] heading(Vector v, int x, int z) {
        double dx = v.getX(), dz = v.getZ();
        if (Math.abs(dx) + Math.abs(dz) < 0.03) {
            double rad = Math.toRadians(ctx.c.yaw());
            dx = -Math.sin(rad); dz = Math.cos(rad);
        }
        if (Math.abs(dx) > Math.abs(dz) * 2) return new int[]{(int) Math.signum(dx), 0};
        if (Math.abs(dz) > Math.abs(dx) * 2) return new int[]{0, (int) Math.signum(dz)};
        return null;   // true diagonal: no line to repair
    }

    private static int floor(double d) { int i = (int) d; return d < i ? i - 1 : i; }
}
