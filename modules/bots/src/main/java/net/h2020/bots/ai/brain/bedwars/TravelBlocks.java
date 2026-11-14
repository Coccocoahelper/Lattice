package net.h2020.bots.ai.brain.bedwars;

import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.brain.BedwarsBrain;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.motor.BlockPlacer;
import net.h2020.bots.ai.world.Shape;
import net.h2020.bots.ai.world.WorldView;

import java.util.List;
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
    private final BedwarsBrain brain;

    private static final int NONE = 0, HEAD = 1, REPAIR = 2;
    private int mode = NONE;
    private long started;
    private long lastHead = -10000, lastRepair = -10000;
    private long jumpUntil = -1;

    public TravelBlocks(BotContext ctx) { this(ctx, null); }

    public TravelBlocks(BotContext ctx, BedwarsBrain brain) {
        this.ctx = ctx;
        this.brain = brain;
        this.placer = new BlockPlacer(ctx);
    }

    /**
     * Scareshow #28: "place additional wool blocks on weak areas of your defence any time you pass by". Walks the
     * defence layers for an open cell near the bot and fills it, so a partial defence is topped up on every trip home
     * rather than only during a defence task.
     */
    private boolean repairDefence() {
        if (brain == null || ctx.tick - lastRepair < 40) return false;
        BedDefense d = brain.defense();
        if (d == null || !brain.bedAliveNow()) return false;
        Location me = ctx.pos();
        WorldView wv = ctx.world();
        if (me.distance(d.bed[0] == null ? me : new Location(me.getWorld(), d.bed[0][0], d.bed[0][1], d.bed[0][2])) > 9) return false;
        // Batch: all the open cells in reach go into the placer at once, so the wall goes up as one build instead of
        // one decision per block. The first starts now, the rest follow the moment each one lands.
        List<int[]> batch = new java.util.ArrayList<int[]>();
        for (List<int[]> layer : new List[]{d.layer1, d.layer2}) {
            if (layer == null) continue;
            for (int[] c : d.open(layer, wv)) {
                if (batch.size() >= 8) break;
                if (me.distanceSquared(new Location(me.getWorld(), c[0] + 0.5, c[1], c[2] + 0.5)) > 36) continue;
                if (BlockPlacer.findSupport(wv, c[0], c[1], c[2], ctx.eye(), 4.4, true) == null) continue;
                batch.add(c);
            }
        }
        if (batch.isEmpty()) return false;
        lastRepair = ctx.tick;
        started = ctx.tick;
        placer.setNeedSight(true);
        int[] first = batch.get(0);
        placer.start(first[0], first[1], first[2], Inv.BLOCK);
        for (int i = 1; i < batch.size(); i++) placer.queue(batch.get(i)[0], batch.get(i)[1], batch.get(i)[2]);
        mode = REPAIR;
        net.h2020.api.stats.CombatStats.inc("defence.topup");
        net.h2020.api.stats.CombatStats.inc("defence.batch." + batch.size());
        return true;
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
        // Scareshow #28: top the bed defence up on any pass, not only when a defence task is running. Checked before
        // the bridge-line cases because a hole in the defence is worth more than a hole in a bridge.
        if (repairDefence()) return true;
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
