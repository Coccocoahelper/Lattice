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
    /** Try the other stance for a roof cell when the first one stops making progress. */
    private int stanceMode;
    private long standAt;

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
        if (standFor != idx) { stanceMode = 0; standCache = standFor(c); standFor = idx; standAt = ctx.tick; }
        // A roof stance that the body cannot climb to leaves it walking into the wall for the rest of the run. Give
        // it two seconds, then take the other stance rather than stalling: measured 8 of 9 with one, 7 with the
        // other, and which one the nav manages is not reliable from run to run.
        else if (c[1] == bedY + 1 && ctx.tick - standAt > 40) {
            stanceMode = (stanceMode + 1) % 3;
            standCache = standFor(c);
            standAt = ctx.tick;
        }
        int px = (int) Math.floor(ctx.pos().getX());
        int py = (int) Math.floor(ctx.pos().getY() + 0.01);
        int pz = (int) Math.floor(ctx.pos().getZ());
        boolean onCell = px == c[0] && py == c[1] && pz == c[2];
        // Walk only when the cell is out of reach, or when the body is standing in the cell being filled. Walking
        // whenever the approach point is further than 1.5 away meant a step for every block of every row: measured
        // 78% of the brain's ticks spent in nav, 21% placing.
        // Keep walking to the approach point. Tried placing from wherever the body stood instead (footprint in
        // reach, no step): the drill stalled at 3 of 9 with four thousand clicks and nothing landing, so the short
        // walk is what makes the placement legal - the body has to be square to the face it is filling.
        if (!indoor && (onCell || ctx.pos().distance(standCache) > 1.5)) {
            ctx.nav.goTo(standCache, 1.2);
            ctx.nav.tick();
            return;
        }
        // the bed is a legitimate support for the roof: the placer sneaks, so the click places instead of opening it
        placer.setFast(true);
        placer.setNeedSight(false);
        placer.setAvoidInteractive(c[1] == bedY + 1);
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
        // A roof cell is built from the front, standing on the floor: from the side or the back the top face of the
        // block underneath is at eye level and the click goes out over it. Measured: every y65 cell in, all three
        // roof cells refused, from a stance out on the flank.
        if (c[1] == bedY + 1) {
            // A bed's collision box is nine sixteenths high, so its "top face" is inside its own cell: clicking it
            // places nothing at all, which is what kept the two cells over the bed empty through every attempt.
            // The first roof cell stands on wool and is built from the floor; the two over the bed are built from
            // the top of the front row, against the side of the cell placed before them.
            // The cell nearest the wall's end is built from the top of the front row; the other two from the floor.
            // Measured: floor for all three left the drill at 1 of 9, weil the bot could not get a neighbour's side
            // face into the crosshair from the floor for the middle one; wall-top for all three got 8 of 9, missing
            // only the last, whose nav never climbed. One of each is the combination that reaches all three.
            // Three stances, tried in turn while a roof cell refuses: floor in front, on top of the front row, on top
            // of the back row. A bed's collision box is nine sixteenths high, so a roof cell over one can only be
            // built against a neighbour's side face - which side is visible depends on where the body is standing,
            // and the nav does not always climb when asked.
            switch (stanceMode) {
                case 1: return new org.bukkit.Location(ctx.player.getWorld(), cx, bedY + 1, bedZ + 1.5);
                case 2: return new org.bukkit.Location(ctx.player.getWorld(), cx, bedY + 1, bedZ - 1.5);
                default: return new org.bukkit.Location(ctx.player.getWorld(), cx, bedY, bedZ + 2.2);
            }
        }
        double dx = cx - anchorX, dz = cz - anchorZ;
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) { dx = 0; dz = 1; len = 1; }
        // Stand on the floor for every layer. A cell above the floor line is reached upward: its support is the top
        // face of the block below it, which a body on the ground can see. Standing at the cell's own height needs
        // something to stand on at that height, which a defence still being built does not have - level two (26
        // cells, layers up to y67) never got past one cell.
        double y = bedY;
        double out = c[1] > bedY ? 2.2 : 1.35;
        return new org.bukkit.Location(ctx.player.getWorld(), cx + dx / len * out, y, cz + dz / len * out);
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
