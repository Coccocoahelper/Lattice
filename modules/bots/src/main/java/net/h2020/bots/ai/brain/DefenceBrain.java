package net.h2020.bots.ai.brain;

import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.inv.Inv;
import org.bukkit.Material;
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
    /** The cell currently being placed, the one the retry re-starts, and the approach point held for it. */
    private int[] curCell = {0, 0, 0};
    private int standFor = -1;
    private org.bukkit.Location standCache;
    /** Counters and the clock for the five-second state line: a brain that has stopped asking for anything looks
     *  exactly like a brain that is placing nothing, and only one of those is a bug in the aim. */
    private int nullStands, skips, fails;
    /** Tick the current cell became the one being built: a stance that takes longer than six seconds is given up. */
    private long cellSince;
    /** Try the other stance for a roof cell when the first one stops making progress. */
    private int stanceMode;
    private long standAt;
    private long reportAt;
    private String lastFail = "-";

    private final int bedX, bedY, bedZ;

    /** Walking to a stance: the superset of walk that insists on arriving at its height, not near it. */
    private static final net.h2020.bots.ai.nav.NavOptions STANCE = stanceOptions();

    private static net.h2020.bots.ai.nav.NavOptions stanceOptions() {
        net.h2020.bots.ai.nav.NavOptions o = net.h2020.bots.ai.nav.NavOptions.walk();
        o.exactY = true;
        return o;
    }

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
                // The row above the bed is a chain: every block is clicked on the west face of the one placed to its
                // east, so it has to be built east to west, after the rest of its layer.
                boolean ar = isRoofRow(a), br = isRoofRow(b);
                if (ar && br) return b[0] - a[0];
                if (ar != br) return ar ? 1 : -1;
                if (a[1] != b[1]) return a[1] - b[1];             // a row goes up before it goes sideways
                // From the bed outwards. The stance for a cell is a spot further out on the same side, so the cell
                // in front of it must not be there yet: the outer ring went up first and then stood between the bot
                // and every inner cell (0,65,-1 and 1,65,-1 read AIR with the body at their stance and no face in
                // the crosshair, and the left flank was walled in by -2,65,0 the same way).
                int da = la == 2 ? Math.abs(a[0] - bedX) : Math.abs(a[2] - bedZ);
                int db = la == 2 ? Math.abs(b[0] - bedX) : Math.abs(b[2] - bedZ);
                if (da != db) return da - db;
                if (la == 2 && a[1] == 0 && b[1] == 0) {
                    boolean al = a[0] < bedX, bl = b[0] < bedX;
                    if (al != bl) return al ? -1 : 1;
                    if (al) return a[0] - b[0];
                }
                return b[0] - a[0];                               // right to left
            }
        });
    }

    /**
     * The block one cell wants. A drill cell carries its material in the fourth column; a brain handed plain
     * coordinates builds with whatever build block it has, as before.
     */
    private static Inv.Pred blockFor(int[] c) {
        if (c.length < 4) return Inv.BLOCK;
        Material m = Material.getMaterial(c[3]);
        return m == null ? Inv.BLOCK : Inv.type(m);
    }

    /**
     * Is the cell between this one and the bed still empty? Only rows are ordered this way; the roof row is a chain
     * built the other way round and the cells above the bed have no inner cell at all.
     */
    private boolean innerPending(int[] c) {
        int dz = c[2] - bedZ, dx = c[0] - bedX;
        int ix = c[0], iz = c[2];
        if (dz != 0) iz -= Integer.signum(dz);
        else if (dx != 0) ix -= Integer.signum(dx);
        else return false;                                   // beside the bed: nothing in between
        if (!isTarget(ix, c[1], iz)) return false;            // not a cell of this shape
        return net.h2020.bots.ai.world.Shape.passable(ctx.world().shape(ix, c[1], iz));
    }

    /** The row over the bed itself, built as a side-face chain east to west. */
    private boolean isRoofRow(int[] c) { return c[1] == bedY + 1 && c[2] == bedZ; }

    /** 0 = in front of the bed, 1 = behind it, 2 = the flanks and the cells over it. */
    private static int layer(int dz) { return dz > 0 ? 0 : (dz < 0 ? 1 : 2); }

    @Override
    protected void onStart() {
        placer = new BlockPlacer(ctx);
    }

    @Override
    protected void think() {
        if (cells.length == 0) { ctx.motor.halt(); return; }
        // a cell's block has to be in the inventory, not just some build block: level three is wood, level four
        // ender stone, and a bot holding only wool cannot start either
        int[] want = idx < cells.length ? cells[idx] : null;
        if (want != null && !Inv.has(ctx.player, blockFor(want))) { ctx.motor.halt(); return; }
        if (!Inv.has(ctx.player, Inv.BLOCK)) { ctx.motor.halt(); return; }
        if (placer == null) placer = new BlockPlacer(ctx);
        report();

        if (placer.active()) {
            BlockPlacer.Result r = placer.tick();
            if (r == BlockPlacer.Result.FAILED) {
                lastFail = placer.lastFailure();
                fails++;
                if (++retries < 3) { placer.start(curCell[0], curCell[1], curCell[2], blockFor(curCell)); }
                // the next attempt at this cell must choose its stance again: the wall has changed since the last
                // sweep (the outer ring may be standing where the old stance was), and a cached stance chosen
                // before it existed is a spot with no face in view
                else { retries = 0; idx++; skips++; standFor = -1; CombatStats.inc("drill.defence.skip"); }
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
        // Wait for the cell between this one and the bed. Every row is built outwards because the stance for a cell
        // is the spot its outer neighbour will occupy: place the outer block first and it hides the floor the inner
        // cell has to be clicked on, leaving a cell that can never be filled again (0,65,1 sat AIR while all four of
        // its neighbours were solid).
        if (!isRoofRow(c) && innerPending(c)) {
            idx++; retries = 0; standFor = -1; CombatStats.inc("drill.defence.waitinner"); return;
        }

        // One approach point per cell, held while that cell is the one being built. Recomputing it every tick had the
        // bot chasing a point it never reached, so it walked in place for the rest of the run: measured four cells
        // attempted out of nine with the body parked three blocks off the wall.
        boolean newStance = standFor != idx;
        if (newStance) {
            stanceMode = 0; standCache = standFor(c); standFor = idx; standAt = ctx.tick; cellSince = ctx.tick;
            if (standCache == null) { idx++; retries = 0; standFor = -1; nullStands++; CombatStats.inc("drill.defence.nostance"); return; }
        }
        // A roof stance that the body cannot climb to leaves it walking into the wall for the rest of the run. Give
        // it two seconds, then take the other stance rather than stalling: measured 8 of 9 with one, 7 with the
        // other, and which one the nav manages is not reliable from run to run.
        else if (c[1] == bedY + 1 && c[2] == bedZ && (c[0] == bedX || c[0] == bedX + 1) && ctx.tick - standAt > 40) {
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
        // "Close enough to build" is the navigator's arrival, not a fixed 1.5 of its own. The nav stops at
        // radius + 1.5, so a body parked 1.6 blocks from its stance was too far for the brain and already there for
        // the nav: the brain asked for a walk, the nav declined (ARRIVED, within its own tolerance) and the cell was
        // never attempted. Measured: level one sat on 1 of 9 for a minute and a half, body at distance 1.52, with
        // two placement attempts and no sweep in between - not an aim problem, a deadlock between two thresholds.
        double gap = ctx.pos().distance(standCache);
        // Standing at the stance means standing at its height. The old test accepted the navigator's arrival, and the
        // navigator's arrival allows two blocks of vertical error: a body on the floor called itself arrived at a
        // wall-top stance, the placer looked for a face from the wrong eye height, found none, and the cell was
        // dropped. Measured: the body parked at 2.49,65,1.19 for ninety seconds with nav=ARRIVED, gap=1.01, every
        // cell on the sweep failing nosupport.
        // Geometrically: are we standing at the stance? Not "did the navigator say it arrived" - that state belongs to
        // whatever goal it was last given, and the brain reads it while the body is six blocks away (measured body
        // -1.38,2.82 with nav=ARRIVED for a stance at 3.5,-1.5).
        boolean reached = gap <= 0.75;
        // The body is 0.6 wide and a block is 1.0: a bot that has drifted to within 0.8 of the cell's centre is
        // standing in the cell it is trying to fill, and neither the client nor the server will place a block inside
        // a player. This is not an aim problem and no number of clicks fixes it (measured: 90 clicks on the correct
        // face of -1,65,1 from a body whose edge was 0.17 inside -1,65,0, every one refused).
        boolean bodyInCell = Math.abs(ctx.pos().getX() - cxOf(c)) < 0.8
                && Math.abs(ctx.pos().getZ() - czOf(c)) < 0.8
                && Math.abs(ctx.pos().getY() - c[1]) < 0.99;
        if (!indoor && (onCell || !reached || bodyInCell)) {
            // A stance the body cannot get to must not eat the run. Six seconds without arriving and the cell goes
            // back into the queue for the next sweep, which re-picks a stance; the alternative is a brain asking to
            // walk to the same unreachable spot until the drill is closed.
            if (ctx.tick - cellSince > 120) {
                idx++; retries = 0; standFor = -1; CombatStats.inc("drill.defence.unreached");
                return;
            }
            ctx.nav.goTo(standCache, 0.6, STANCE);
            ctx.nav.tick();
            return;
        }
        // the bed is a legitimate support for the roof: the placer sneaks, so the click places instead of opening it
        placer.setFast(true);
        // Only faces the eye can see. Without this the placer picks the top of the floor block under a cell even
        // when the row in front of it hides that floor from the stance: the crosshair then lands on the neighbour's
        // side face, nothing fills the cell, and the placer re-picks the same hidden face until it times out.
        // Measured: 1,65,-1 with sup=SANDSTONE@1,64,-1:UP aimed at 1.43,65.00,-0.74 and hit 1,65,0 NORTH.
        placer.setNeedSight(true);
        placer.setAvoidInteractive(overBed(c));
        curCell = c;
        placer.start(c[0], c[1], c[2], blockFor(c));
        CombatStats.inc("drill.defence.try");
        idx++;
        // the rest of this row goes in behind it: four blocks within reach of the one just started
        for (int q = 0; q < 4 && idx < cells.length; q++, idx++) {
            int[] n = cells[idx];
            // only the same block in the same row: a queued cell is placed with this cell's material
            if (Math.abs(n[0] - c[0]) > 3 || Math.abs(n[2] - c[2]) > 1 || n[1] != c[1]
                    || (n.length >= 4 && c.length >= 4 && n[3] != c[3]) || n.length != c.length) { idx--; break; }
            placer.queue(n[0], n[1], n[2]);
        }
    }

    /**
     * One line every five seconds: the cell the brain is on, the spot it means to stand on, and how many cells it has
     * skipped for want of a stance or given up on. Placement logs alone cannot separate "the aim is missing" from
     * "the brain stopped asking", and the difference is the whole diagnosis.
     */
    private void report() {
        if (ctx.tick < reportAt) return;
        reportAt = ctx.tick + 100;
        int[] c = idx < cells.length ? cells[idx] : null;
        String cell = c == null ? "-" : c[0] + "," + c[1] + "," + c[2];
        String stand = standCache == null ? "-" : (int) Math.floor(standCache.getX()) + ","
                + (int) Math.floor(standCache.getY()) + "," + (int) Math.floor(standCache.getZ());
        org.bukkit.Bukkit.getLogger().info("[defence] cells=" + cells.length + " idx=" + idx + " cell=" + cell
                + " stand=" + stand + " noStance=" + nullStands + " skipped=" + skips + " fails=" + fails
                + " lastFail=" + lastFail + " retries=" + retries
                + " body=" + String.format("%.2f,%.2f,%.2f", ctx.pos().getX(), ctx.pos().getY(), ctx.pos().getZ())
                + " nav=" + ctx.nav.describe() + " gap=" + String.format("%.2f", standCache == null ? -1
                        : ctx.pos().distance(standCache))
                + (c == null ? "" : fresh(c)));
    }

    /**
     * Where to stand to build one cell. A stance is only usable if the body fits there and a face that fills the
     * cell is in view from its eye - anything else is a spot the bot walks to and then cannot place from, which
     * reads in the log as a cell that times out with no visible reason.
     *
     * <p>Candidates are tried in the order the recorded human runs use: straight out from the bed on the floor,
     * then further out, then a neighbouring column, then the same ring again with the feet one level up, which is
     * how the row above the bed is chained (each block is clicked on the side of the one placed before it).
     */
    private org.bukkit.Location standFor(int[] c) {
        org.bukkit.World w = ctx.player.getWorld();
        double cx = c[0] + 0.5, cz = c[2] + 0.5;
        double dx = cx - anchorX, dz = cz - anchorZ;
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) { dx = 0; dz = 1; len = 1; }
        double ux = dx / len, uz = dz / len;
        java.util.List<org.bukkit.Location> cands = new java.util.ArrayList<org.bukkit.Location>();
        for (int feet : new int[]{c[1] - 1, c[1], c[1] + 1}) {
            // 1.75 rather than 1.35: the stance has to stay clear of the cell by more than the walking tolerance,
        // or a normal arrival leaves the shoulder inside the block's space and the placement is refused.
        for (double d : new double[]{1.75, 2.4, 3.0}) {
                cands.add(new org.bukkit.Location(w, cx + ux * d, feet, cz + uz * d));
            }
            for (int ox = -2; ox <= 2; ox++) {
                for (int oz = -2; oz <= 2; oz++) {
                    if (ox == 0 && oz == 0) continue;
                    cands.add(new org.bukkit.Location(w, c[0] + ox + 0.5, feet, c[2] + oz + 0.5));
                }
            }
        }
        org.bukkit.Location fallback = null;
        for (org.bukkit.Location st : cands) {
            int sx = (int) Math.floor(st.getX()), sy = (int) Math.floor(st.getY()), sz = (int) Math.floor(st.getZ());
            boolean fits = net.h2020.bots.ai.world.Shape.passable(ctx.world().shape(sx, sy, sz))
                    && net.h2020.bots.ai.world.Shape.passable(ctx.world().shape(sx, sy + 1, sz));
            boolean floor = !net.h2020.bots.ai.world.Shape.passable(ctx.world().shape(sx, sy - 1, sz));
            if (!fits || !floor) continue;
            if (fallback == null) fallback = st;
            if (seesSupport(c, st)) return st;
        }
        return fallback;
    }

    private static double cxOf(int[] c) { return c[0] + 0.5; }
    private static double czOf(int[] c) { return c[2] + 0.5; }

    /** What the stance chooser would pick for this cell right now, and the face it can see from there. */
    private String fresh(int[] c) {
        org.bukkit.Location st = standFor(c);
        if (st == null) return " pick=-";
        org.bukkit.Location eye = new org.bukkit.Location(st.getWorld(), st.getX(), st.getY() + 1.62, st.getZ());
        Object[] sup = BlockPlacer.findSupport(ctx.world(), c[0], c[1], c[2], eye, 4.4, true, null, true);
        String face = sup == null ? "-" : ((org.bukkit.block.Block) sup[0]).getX() + "," + ((org.bukkit.block.Block) sup[0]).getY()
                + "," + ((org.bukkit.block.Block) sup[0]).getZ() + ":" + sup[1];
        return " pick=" + String.format("%.2f,%.2f,%.2f", st.getX(), st.getY(), st.getZ()) + " sees=" + face;
    }

    /** Can a block in this cell be placed from that stance at all: is some face of a neighbour in view from its eye? */
    private boolean seesSupport(int[] c, org.bukkit.Location stance) {
        org.bukkit.Location eye = new org.bukkit.Location(stance.getWorld(), stance.getX(), stance.getY() + 1.62,
                stance.getZ());
        // Not a bed: clicking one places nothing (its collision box sits inside its own cell), so a stance whose
        // only view is a bed is a stance that cannot build this cell.
        return BlockPlacer.findSupport(ctx.world(), c[0], c[1], c[2], eye, 4.4, true, null, true) != null;
    }


    /** The row one level above the bed, wherever it reaches: built as a chain against side faces. */
    private boolean roofRow(int[] c) { return c[1] == bedY + 1 && c[2] == bedZ; }

    /** A cell directly above a bed block: the only kind whose support is a bed. */
    private boolean overBed(int[] c) {
        return c[1] == bedY + 1 && c[2] == bedZ && (c[0] == bedX || c[0] == bedX + 1);
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
