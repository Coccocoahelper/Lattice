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
    /** Tick before which the cell list is not restarted after a pass that did not finish the drill. */
    private long nextTry;
    /** The bed these cells surround, worked out once: the ring's average position. */
    private double anchorX, anchorZ;

    public DefenceBrain(int[][] cells) {
        this.cells = cells == null ? new int[0][] : cells;
        // Build the shape the way the best recorded human run did (12 blocks, 3.3 s): the front row right to left,
        // then the back row right to left, then the flanks, then the cells over the bed. A person sweeps a row and
        // moves once; taking the template's own order walked back and forth across the same wall.
        double sx = 0, sz = 0;
        for (int[] c : this.cells) { sx += c[0]; sz += c[2]; }
        if (this.cells.length > 0) { anchorX = sx / this.cells.length + 0.5; anchorZ = sz / this.cells.length + 0.5; }
        java.util.Arrays.sort(this.cells, new java.util.Comparator<int[]>() {
            @Override public int compare(int[] a, int[] b) {
                int la = layer(a[2]), lb = layer(b[2]);
                if (la != lb) return la - lb;
                if (a[2] != b[2]) return b[2] - a[2];        // far side first within a layer
                return b[0] - a[0];                          // then right to left
            }
        });
    }

    /** 0 = in front of the bed, 1 = behind it, 2 = the flanks and the cells over it. */
    private static int layer(int dz) { return dz > 0 ? 0 : (dz < 0 ? 1 : 2); }

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
        if (idx >= cells.length) {
            // a finished list that did not complete the drill means a cell refused. Re-run it, but not sixty thousand
            // times a second: measured at 60,370 sweeps in ninety seconds before this pause.
            idx = 0;
            if (ctx.tick < nextTry) { ctx.motor.halt(); return; }
            nextTry = ctx.tick + 10;
            CombatStats.inc("drill.defence.sweep");
        }
        int[] c = cells[idx];
        // the cells are around a bed the bot starts six blocks away from, so most of the drill is walking to the wall.
        // Placement only works inside reach; without this the brain asked to place from out of range and skipped
        // every cell in turn.
        // Stand beside the cell, never on it: walking to the cell's own centre put the body inside the block it was
        // about to place, so the placement was refused as occupied and the list walked on for ever.
        org.bukkit.Location at = new org.bukkit.Location(ctx.player.getWorld(), c[0] + 0.5, c[1], c[2] + 0.5);
        double dx = at.getX() - bedX(), dz = at.getZ() - bedZ();
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) { dx = 0; dz = 1; len = 1; }
        org.bukkit.Location stand = new org.bukkit.Location(ctx.player.getWorld(),
                at.getX() + dx / len * 1.7, c[1], at.getZ() + dz / len * 1.7);
        // Never place from inside a cell of the wall: standing on the block you are about to fill makes it occupied,
        // and the placer then refuses the click and the pass walks on. Measured: bots parked at (1,65,1), a target
        // cell, with nine blocks "to place" and none of them going down.
        boolean standingOnTarget = isTarget((int) Math.floor(ctx.pos().getX()),
                (int) Math.floor(ctx.pos().getY() + 0.01), (int) Math.floor(ctx.pos().getZ()));
        if (ctx.pos().getWorld() == at.getWorld() && (standingOnTarget || ctx.pos().distance(at) > 3.4)) {
            ctx.nav.goTo(stand, 1.0);
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
        // the rest of this row goes in behind it: four blocks within reach of the one just started, which the placer
        // then lays back to back without another decision or another step
        for (int j = 0; j < 4 && idx < cells.length; j++, idx++) {
            int[] n = cells[idx];
            if (Math.abs(n[0] - c[0]) > 3 || Math.abs(n[2] - c[2]) > 1 || n[1] != c[1]) { idx--; break; }
            placer.queue(n[0], n[1], n[2]);
        }
    }

    /** Is this cell part of the wall? */
    private boolean isTarget(int x, int y, int z) {
        for (int[] c : cells) if (c[0] == x && c[1] == y && c[2] == z) return true;
        return false;
    }

    /** The bed's centre, from the first cells the drill handed us: they are all offsets around it. */
    private double bedX() { return anchorX; }
    private double bedZ() { return anchorZ; }

    @Override protected void onInactive() { }
}
