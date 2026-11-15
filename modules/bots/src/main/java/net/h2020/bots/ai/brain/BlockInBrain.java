package net.h2020.bots.ai.brain;

import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.motor.BlockBreaker;
import net.h2020.bots.ai.motor.BlockPlacer;

import java.util.List;

/**
 * Repeats a block-in: breaks the hole open itself, then seals it.
 *
 * <p>The bot is given every hole a person has done - each one a set of cells - and works through them in turn, so a
 * block-in it practises is one somebody actually performed rather than one invented here. Opening the hole is part of
 * the run: nothing is cut for the bot, exactly as nothing is cut for a player.
 */
public final class BlockInBrain extends BaseBrain {

    private final List<int[][]> holes;
    private int which;
    private int idx;
    private boolean sealing;                 // false: still opening the hole, true: closing it
    private BlockBreaker breaker;
    private BlockPlacer placer;
    private long nextTry;

    public BlockInBrain(List<int[][]> holes) {
        this.holes = holes;
    }

    @Override
    protected void onStart() {
        breaker = new BlockBreaker(ctx);
        placer = new BlockPlacer(ctx);
    }

    @Override
    protected void think() {
        if (holes == null || holes.isEmpty()) { ctx.motor.move(0, 0); return; }
        if (breaker == null) breaker = new BlockBreaker(ctx);
        if (placer == null) placer = new BlockPlacer(ctx);
        int[][] hole = holes.get(which % holes.size());
        if (hole.length == 0) { which++; return; }

        if (idx >= hole.length) {
            // one full pass done: sealing finished, so take the next hole after a beat
            if (ctx.tick < nextTry) { ctx.motor.move(0, 0); return; }
            nextTry = ctx.tick + 10;
            if (sealing) which++;
            idx = 0;
            sealing = false;
            return;
        }

        int[] c = hole[idx];
        boolean air = net.h2020.bots.ai.world.Shape.passable(ctx.world().shape(c[0], c[1], c[2]));

        if (!sealing) {
            // open it: walk into reach and mine the block out
            if (air) { idx++; return; }
            if (!withinReach(c)) { face(c); return; }
            if (!breaker.active()) { breaker.start(c[0], c[1], c[2], false); CombatStats.inc("drill.blockin.break"); }
            BlockBreaker.Result r = breaker.tick();
            if (r == BlockBreaker.Result.RUNNING) return;
            if (r == BlockBreaker.Result.DONE) {
                if (idx == hole.length - 1) { sealing = true; idx = 0; }
                else idx++;
                return;
            }
            breaker.cancel();
            idx++;
            return;
        }

        // close it: place back into the same cells
        if (!air) { idx++; return; }
        if (!withinReach(c)) { face(c); return; }
        if (!placer.active()) {
            placer.setFast(false);
            placer.setNeedSight(false);
            placer.setAvoidInteractive(true);
            placer.start(c[0], c[1], c[2], Inv.BLOCK);
            CombatStats.inc("drill.blockin.seal");
            return;
        }
        BlockPlacer.Result r = placer.tick();
        if (r == BlockPlacer.Result.RUNNING) return;
        idx++;
    }

    private boolean withinReach(int[] c) {
        double dx = c[0] + 0.5 - ctx.pos().getX();
        double dy = c[1] + 0.5 - ctx.pos().getY();
        double dz = c[2] + 0.5 - ctx.pos().getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= 3.6;
    }

    /** Stand where the block can be reached from: two blocks back along the line to it, on the ground. */
    private void face(int[] c) {
        double dx = c[0] + 0.5 - ctx.pos().getX();
        double dz = c[2] + 0.5 - ctx.pos().getZ();
        double len = Math.max(0.001, Math.hypot(dx, dz));
        double sx = c[0] + 0.5 - dx / len * 2.2;
        double sz = c[2] + 0.5 - dz / len * 2.2;
        ctx.nav.goTo(new org.bukkit.Location(ctx.player.getWorld(), sx, c[1], sz), 0.9);
        ctx.nav.tick();
    }
}
