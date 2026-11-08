package net.h2020.bots.ai.brain.bedwars;

import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.brain.BedwarsBrain;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.motor.BlockPlacer;
import net.h2020.bots.ai.nav.NavOptions;
import net.h2020.bots.ai.util.AIMath;
import net.h2020.bots.ai.world.WorldView;
import org.bukkit.Location;
import org.bukkit.Material;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cover the bed: layer 1 wool, then layer 2 end stone (or more wool for dedicated defenders). Walks to stand spots
 * around the bed from which the remaining cells are in reach, and places them one by one with BlockPlacer
 * (visible support face, precise aim, right-click on the real ray hit). Also used to patch holes later.
 */
public final class DefendTask extends BwTask {
    private final BlockPlacer placer;
    private BedDefense def;
    private final Set<Long> skipped = new HashSet<>();
    private Location stand;
    private int navFails;
    private int placedCount;
    private long firstPlaceTick = -1;
    private long lastPlaceTick = -1;
    private int blocksPlaced;
    /** Cells still to fill from the current stand, in placement order. Recomputing this every block was the cost. */
    private java.util.ArrayDeque<int[]> pending;
    private final boolean secondLayer;
    private int idleTicks;
    /** Stands already used in this pass, and the pass counter (a second pass retries what failed). */
    private final Set<Long> triedStands = new HashSet<>();
    private int pass;
    private final Set<Long> hiddenHere = new HashSet<>();
    private int placeFails;

    public DefendTask(BedwarsBrain brain, boolean secondLayer) {
        super(brain);
        net.h2020.bots.ai.stats.DefenceStats.run();
        this.placer = new BlockPlacer(ctx);
        this.secondLayer = secondLayer;
    }

    @Override public String name() { return "defend"; }

    public int placed() { return placedCount; }

    @Override
    public boolean tick() {
        if (brain.color() == null || !view.bedAlive(brain.color())) return false;
        if (def == null) def = BedDefense.of(view.bedLocation(brain.color()));
        if (age() > 20 * 150) return false;   // long enough to finish a wall in one go, not 40 seconds of it
        WorldView wv = ctx.world();

        if (firstPlaceTick < 0 && placedCount > 0) firstPlaceTick = ctx.tick;
        if (placer.active()) {
            BlockPlacer.Result r = placer.tick();
            driftWhilePlacing();
            if (r == BlockPlacer.Result.DONE) {
                placedCount++;
                if (net.h2020.bots.ai.combat.CombatTuning.fastDefence && startNextFromPlan()) return true;
                net.h2020.api.stats.CombatStats.add("defence.blocks", 1);
                net.h2020.bots.ai.stats.DefenceStats.block(ctx.tick);
                // interval between consecutive blocks: the honest measure of build speed, independent of demand
                if (lastPlaceTick > 0 && ctx.tick - lastPlaceTick <= 40) {
                    net.h2020.api.stats.CombatStats.add("defence.gap", ctx.tick - lastPlaceTick);
                    blocksPlaced++;
                }
                lastPlaceTick = ctx.tick;
                idleTicks = net.h2020.bots.ai.combat.CombatTuning.fastDefence ? 0 : AIMath.range(ctx.rng, 0, 1);
            }
            else if (r == BlockPlacer.Result.FAILED) {
                long k = AIMath.pack(placer.targetX(), placer.targetY(), placer.targetZ());
                // hidden from this spot only (wool we just placed is in the way): try it again from the next stand
                if (++placeFails > 12 || !hiddenHere.add(k)) skipped.add(k);
            }
            return true;
        }
        if (idleTicks > 0) { idleTicks--; return true; }
        // stuck on an unreachable cell with nobody coming: standing at the bed with wool in hand is not defence,
        // it is a bot that should be doing something else
        if (lastPlaceTick > 0 && ctx.tick - lastPlaceTick > 80 && brain.nearestEnemyPlayer(24) == null) {
            net.h2020.api.stats.CombatStats.inc("defence.stalled");
            return false;
        }

        // choose layer + material
        List<int[]> todo = def.open(def.layer1, wv);
        Inv.Pred mat = Inv.type(Material.WOOL);
        removeSkipped(todo);
        if (todo.isEmpty()) {
            if (!secondLayer) {
                if (firstPlaceTick > 0) {
                    net.h2020.api.stats.CombatStats.add("defence.seconds", (ctx.tick - firstPlaceTick) / 20.0);
                    firstPlaceTick = -1;
                }
                return false;
            }
            todo = def.open(def.layer2, wv);
            removeSkipped(todo);
            if (todo.isEmpty()) {
                if (firstPlaceTick > 0) {
                    net.h2020.api.stats.CombatStats.add("defence.seconds", (ctx.tick - firstPlaceTick) / 20.0);
                    firstPlaceTick = -1;
                }
                return false;
            }
            if (Inv.has(ctx.player, Inv.type(Material.ENDER_STONE))) mat = Inv.type(Material.ENDER_STONE);
            else if (Inv.has(ctx.player, Inv.type(Material.STAINED_CLAY))) mat = Inv.type(Material.STAINED_CLAY);
            else if (Inv.has(ctx.player, Inv.type(Material.WOOD))) mat = Inv.type(Material.WOOD);
            else if (Inv.count(ctx.player, Inv.type(Material.WOOL)) < 8) return false;
        }
        if (blocksPlaced > 0 && blocksPlaced % 8 == 0) {
            net.h2020.api.stats.CombatStats.add("defence.avg", 0);   // keep the counter alive for the report
        }
        if (!Inv.has(ctx.player, mat)) {
            if (Inv.has(ctx.player, Inv.BLOCK)) mat = Inv.BLOCK; else return false;
        }

        Location eye = ctx.eye();
        // a reachable cell from where we are?
        for (int[] c : todo) {
            if (hiddenHere.contains(AIMath.pack(c[0], c[1], c[2]))) continue;
            if (BlockPlacer.findSupport(wv, c[0], c[1], c[2], eye, 4.3, true) == null) continue;
            if (!BlockPlacer.cellFree(new Location(eye.getWorld(), c[0], c[1], c[2]))) continue;
            ctx.nav.stop();
            placer.setNeedSight(!net.h2020.bots.ai.combat.CombatTuning.fastDefence);
            placer.setFast(net.h2020.bots.ai.combat.CombatTuning.fastDefence);   // click as soon as the ray lands
            if (net.h2020.bots.ai.combat.CombatTuning.fastDefence) {
                pending = new java.util.ArrayDeque<int[]>();
                for (int[] cc : todo) {
                    if (cc == c || hiddenHere.contains(AIMath.pack(cc[0], cc[1], cc[2]))) continue;
                    // everything the bot can already see from here: it places these before moving a single step
                    if (BlockPlacer.findSupport(wv, cc[0], cc[1], cc[2], eye, 4.3, true) != null) pending.add(cc);
                }
            }
            placer.start(c[0], c[1], c[2], mat);
            return true;
        }
        // move to a better spot. Each stand is tried once per pass; when every stand has been tried, a second pass
        // retries the cells that failed from a different angle instead of leaving the defence full of holes.
        if (stand == null || ctx.pos().distance(stand) < 0.6 || ctx.nav.failed() || navFails > 4) {
            if (ctx.nav.failed()) navFails++;
            hiddenHere.clear();
            Location prev = stand;
            stand = standSpot(todo, wv, triedStands);
            if (stand == null) {
                if (pass == 0) {
                    pass = 1;
                    skipped.clear();
                    triedStands.clear();
                    hiddenHere.clear();
                    placeFails = 0;
                    navFails = 0;
                    return true;
                }
                return false;                                    // two full passes and still nothing placeable
            }
            triedStands.add(AIMath.pack(stand.getBlockX(), stand.getBlockY(), stand.getBlockZ()));
            navFails = 0;
            if (prev != null && prev.distanceSquared(stand) < 0.1 && ctx.pos().distance(stand) < 0.8) {
                if (pass == 0) {
                    pass = 1;
                    skipped.clear();
                    triedStands.clear();
                    hiddenHere.clear();
                    return true;
                }
                return false;
            }
        }
        NavOptions o = NavOptions.walk();
        o.forbidden = def.allCells;
        o.sprint = ctx.pos().distance(stand) > 3;
        ctx.nav.goTo(stand, 0.5, o);
        ctx.nav.tick();
        return true;
    }

    /**
     * Start the next cell of the cached plan without going back through the full scan. Defence speed was dominated by
     * the per-block work - reopening the layer, then a findSupport() visibility test for every remaining cell - not by
     * the click itself.
     */
    /**
     * Keep walking while the placer works. Standing still between blocks is what makes a wall take a minute; a player
     * places and steps along the wall in the same motion, and the placer's ray gate is what decides whether the click
     * is allowed, so moving costs nothing but a retry.
     */
    private void driftWhilePlacing() {
        if (!net.h2020.bots.ai.combat.CombatTuning.moveWhilePlacing) return;
        Location me0 = ctx.pos();
        // never drift while standing on a rim: the wall is worth less than the bot
        if (me0 != null && ctx.world().voidSides(me0.getBlockX(), me0.getBlockY(), me0.getBlockZ()) > 0) return;
        if (pending == null || pending.isEmpty()) return;
        int[] next = pending.peek();
        if (next == null) return;
        Location me = ctx.pos();
        double dx = next[0] + 0.5 - me.getX(), dz = next[2] + 0.5 - me.getZ();
        double d = Math.hypot(dx, dz);
        if (d < 1.1) return;                       // already in reach: let the click land
        // the crosshair is on the cell, so this is the tick the click lands: standing still here is the point of the
        // whole exercise, and it is what the earlier "move and place" attempt got wrong
        if (ctx.c.aimSettled()) return;
        if (d > 2.1) return;                       // a step, not a walk: longer moves belong to the navigator
        if (Math.abs(next[1] - me.getBlockY()) > 1) return;   // same layer only: climbing is the navigator's job
        // the square we are about to step into must have ground and not be a rim
        int ax = (int) Math.floor(me.getX() + dx / d), az = (int) Math.floor(me.getZ() + dz / d);
        if (ctx.world().voidSides(ax, me.getBlockY(), az) > 1) return;
        ctx.motor.moveToward(dx, dz);
        if (next[1] > me.getBlockY() + 0.6) ctx.motor.jump(true);   // the next cell is a step up
        net.h2020.api.stats.CombatStats.inc("defence.move");
    }

    private boolean startNextFromPlan() {
        if (pending == null) return false;
        while (!pending.isEmpty()) {
            int[] c = pending.poll();
            if (!net.h2020.bots.ai.world.Shape.passable(ctx.world().shape(c[0], c[1], c[2]))) continue;
            if (hiddenHere.contains(AIMath.pack(c[0], c[1], c[2]))) continue;
            // no sight test: the plan was built from cells visible at this stand, and the placer's ray is the real gate
            placer.setNeedSight(true);
            placer.setFast(true);
            placer.start(c[0], c[1], c[2], material());
            return true;
        }
        pending = null;
        return false;
    }

    private Inv.Pred material() {
        if (Inv.has(ctx.player, Inv.type(Material.WOOL))) return Inv.type(Material.WOOL);
        if (Inv.has(ctx.player, Inv.type(Material.ENDER_STONE))) return Inv.type(Material.ENDER_STONE);
        return Inv.BLOCK;
    }

    private void removeSkipped(List<int[]> l) {
        for (int i = l.size() - 1; i >= 0; i--) {
            int[] c = l.get(i);
            if (skipped.contains(AIMath.pack(c[0], c[1], c[2]))) l.remove(i);
        }
    }

    private Location standSpot(List<int[]> todo, WorldView wv, Set<Long> exclude) {
        double[] ctr = def.center();
        int cx = (int) Math.floor(ctr[0]), cz = (int) Math.floor(ctr[2]), by = def.bed[0][1];
        Location me = ctx.pos();
        Location best = null;
        double bs = 0.5;
        for (int x = cx - 5; x <= cx + 5; x++) for (int z = cz - 5; z <= cz + 5; z++) for (int y = by - 1; y <= by + 1; y++) {
            if (def.allCells.contains(AIMath.pack(x, y, z))) continue;
            if (exclude != null && exclude.contains(AIMath.pack(x, y, z))) continue;
            if (!wv.standable(x, y, z)) continue;
            double s = wv.stand(x, y, z);
            double hd = Math.sqrt(AIMath.hDistSq(x + 0.5, z + 0.5, ctr[0], ctr[2]));
            if (hd < 1.6 || hd > 4.6) continue;
            // beside the void is fine for a moment (players defend from the edge), just not preferred
            int vs = wv.voidSides(x, y, z);
            if (vs > 1) continue;
            Location eye = new Location(me.getWorld(), x + 0.5, s + 1.62, z + 0.5);
            int reach = 0;
            for (int[] c : todo) {
                double dx = c[0] + 0.5 - eye.getX(), dy = c[1] + 0.5 - eye.getY(), dz = c[2] + 0.5 - eye.getZ();
                if (dx * dx + dy * dy + dz * dz > 4.6 * 4.6) continue;
                // count only cells whose support face is actually visible from there
                if (BlockPlacer.findSupport(wv, c[0], c[1], c[2], eye, 4.3, true) != null) reach++;
            }
            double score = reach - 0.2 * Math.sqrt(AIMath.hDistSq(x + 0.5, z + 0.5, me.getX(), me.getZ())) - (vs > 0 ? 1.2 : 0);
            if (score > bs) { bs = score; best = new Location(me.getWorld(), x + 0.5, s, z + 0.5); }
        }
        return best;
    }

    @Override
    public void abort() {
        super.abort();
        placer.cancel();
    }
}
