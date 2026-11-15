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
    /** A wool block being placed beside a bed-supported cell, so the cell itself can go in through it. */
    /** The bed these cells surround, worked out once: the ring's average position. */
    private double anchorX, anchorZ;
    /** The cell currently being placed, the one the retry re-starts, and the approach point held for it. */
    private int[] curCell = {0, 0, 0};
    private int standFor = -1;
    private org.bukkit.Location standCache;

    private final int bedX, bedY, bedZ;

    /** Build from inside a standing defence: never walk, because every cell is already in reach. */
    private final boolean indoor;

    public DefenceBrain(int[][] cells, int bedX, int bedY, int bedZ) {
        this(cells, bedX, bedY, bedZ, false);
    }

    public DefenceBrain(int[][] cells, int bedX, int bedY, int bedZ, boolean indoor) {
        this.cells = cells == null ? new int[0][] : cells;
        this.bedX = bedX; this.bedY = bedY; this.bedZ = bedZ;
        this.indoor = indoor;
        // Build the shape the way the best recorded human run did (12 blocks, 3.3 s): the front row right to left,
        // then the back row right to left, then the flanks, then the cells over the bed. A person sweeps a row and
        // moves once; taking the template's own order walked back and forth across the same wall.
        anchorX = bedX + 0.5; anchorZ = bedZ + 0.5;
        // Sovacos's route, from 15 recorded runs (the last three are identical to the block):
        //   front row right to left   (2,1) (1,1) (0,1)
        //   back row right to left    (2,-1) (1,-1) (0,-1)
        //   the flanks                (-1,0) then (3,0)
        //   the bed's own edge        (2,0)
        //   over the bed right to left (2,0,66) (1,0,66) (0,0,66)
        // Gaps: 100-150 ms inside a row, 250-750 ms between rows.
        java.util.Arrays.sort(this.cells, new java.util.Comparator<int[]>() {
            @Override public int compare(int[] a, int[] b) {
                int la = layer(a[2]), lb = layer(b[2]);
                if (la != lb) return la - lb;
                if (la != 2) return b[0] - a[0];                  // rows: right to left
                if (a[1] != b[1]) return a[1] - b[1];             // flanks (y0) before the roof (y1)
                if (a[1] == 0) {
                    // the left flank first, then the right side inward: -1, 3, 2
                    boolean al = a[0] < bedX, bl = b[0] < bedX;
                    if (al != bl) return al ? -1 : 1;
                    if (al) return a[0] - b[0];
                    return b[0] - a[0];
                }
                return b[0] - a[0];                               // over the bed: right to left
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
                if (++retries < 3) { placer.start(curCell[0], curCell[1], curCell[2], Inv.BLOCK); }
                else { retries = 0; idx++; CombatStats.inc("drill.defence.skip"); }
            }
            return;
        }
        if (idx >= cells.length) {
            // a finished list that did not complete the drill means a cell refused; re-run it, but not once a tick
            idx = 0;
            if (ctx.tick < nextTry) { ctx.motor.halt(); return; }
            nextTry = ctx.tick + 10;
            CombatStats.inc("drill.defence.sweep");
        }
        int[] c = cells[idx];
        if (!net.h2020.bots.ai.world.Shape.passable(ctx.world().shape(c[0], c[1], c[2]))) { idx++; retries = 0; return; }

        // One approach point per cell, held while that cell is the one being built. Recomputing it every tick had the
        // bot chasing a point it never reached, so it walked in place for the rest of the run: measured four cells
        // attempted out of nine with the body parked three blocks off the wall.
        if (standFor != idx) { standCache = standFor(c); standFor = idx; }
        int px = (int) Math.floor(ctx.pos().getX());
        int py = (int) Math.floor(ctx.pos().getY() + 0.01);
        int pz = (int) Math.floor(ctx.pos().getZ());
        boolean onCell = px == c[0] && py == c[1] && pz == c[2];
        if (!indoor && (onCell || ctx.pos().distance(standCache) > 1.5)) {
            ctx.nav.goTo(standCache, 1.2);
            ctx.nav.tick();
            return;
        }
        // the bed is a legitimate support for the roof: the placer sneaks, so the click places instead of opening it
        placer.setFast(true);
        placer.setNeedSight(false);
        placer.setAvoidInteractive(false);
        curCell = c;
        placer.start(c[0], c[1], c[2], Inv.BLOCK);
        CombatStats.inc("drill.defence.try");
        idx++;
        // the rest of this row goes in behind it: four blocks within reach of the one just started
        for (int q = 0; q < 4 && idx < cells.length; q++, idx++) {
            int[] n = cells[idx];
            if (Math.abs(n[0] - c[0]) > 3 || Math.abs(n[2] - c[2]) > 1 || n[1] != c[1]) { idx--; break; }
            placer.queue(n[0], n[1], n[2]);
        }
    }

    /** Where to stand to build one cell: on the floor, outside the wall, on that cell's own side of the bed. */
    private org.bukkit.Location standFor(int[] c) {
        double cx = c[0] + 0.5, cz = c[2] + 0.5;
        double dx = cx - anchorX, dz = cz - anchorZ;
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) { dx = 0; dz = 1; len = 1; }
        double y = Math.min(c[1], bedY);                 // a roof cell has no floor at its own height
        return new org.bukkit.Location(ctx.player.getWorld(), cx + dx / len * 1.9, y, cz + dz / len * 1.9);
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
