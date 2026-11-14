package net.h2020.bots.ai.brain;

import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.motor.BlockPlacer;

/**
 * Fills a given set of cells with blocks: the bed-defence drill's job, handed to a bot.
 *
 * <p>It places the cells in the order it was given and starts again when the set is finished, because the drill clears
 * the defence and raises the level after every completed run. No pathing decisions and no fighting: the drill measures
 * building, so the brain does nothing else.
 */
public final class DefenceBrain extends BaseBrain {

    private final int[][] cells;
    private BlockPlacer placer;
    private int idx;
    private int retries;

    public DefenceBrain(int[][] cells) {
        this.cells = cells == null ? new int[0][] : cells;
    }

    @Override
    protected void onStart() {
        placer = new BlockPlacer(ctx);
    }

    @Override
    protected void think() {
        if (cells.length == 0) { ctx.motor.halt(); return; }
        if (!Inv.has(ctx.player, Inv.BLOCK)) { ctx.motor.halt(); return; }

        if (placer == null) placer = new BlockPlacer(ctx);
        if (placer.active()) {
            BlockPlacer.Result r = placer.tick();
            if (r == BlockPlacer.Result.FAILED) {
                // one failed attempt is not a verdict: the support face may be behind the body's own arm for a tick
                if (++retries < 3) { placer.start(cells[Math.max(0, idx - 1)][0], cells[Math.max(0, idx - 1)][1], cells[Math.max(0, idx - 1)][2], Inv.BLOCK); }
                else { retries = 0; idx++; CombatStats.inc("drill.defence.skip"); }
            }
            return;
        }
        if (idx >= cells.length) { idx = 0; CombatStats.inc("drill.defence.sweep"); }
        int[] c = cells[idx];
        // the cells are around a bed the bot starts six blocks away from, so most of the drill is walking to the wall.
        // Placement only works inside reach; without this the brain asked to place from out of range and skipped
        // every cell in turn.
        org.bukkit.Location at = new org.bukkit.Location(ctx.player.getWorld(), c[0] + 0.5, c[1], c[2] + 0.5);
        if (ctx.pos().getWorld() == at.getWorld() && ctx.pos().distance(at) > 3.2) {
            ctx.nav.goTo(at, 2.4);
            ctx.nav.tick();
            return;
        }
        // if it is already there, skip it: the drill leaves a finished wall up until the level changes
        if (!net.h2020.bots.ai.world.Shape.passable(ctx.world().shape(c[0], c[1], c[2]))) { idx++; return; }
        placer.setFast(true);
        placer.setNeedSight(false);
        placer.start(c[0], c[1], c[2], Inv.BLOCK);
        CombatStats.inc("drill.defence.try");
        idx++;
    }

    @Override protected void onInactive() { }
}
