package net.h2020.bots.ai.brain;

import net.h2020.api.bot.Bot;
import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.motor.Bridger;
import net.h2020.bots.ai.world.Shape;
import org.bukkit.Location;

/**
 * Bridges in one direction until the blocks run out. Nothing else: no combat, no navigation, no decisions about where
 * to go. That is the point - a drill wants to measure the bridging, and every other part of the brain is noise on the
 * measurement.
 *
 * <p>It walks to the edge by itself (the engine turns and backs up), restarts after a fall because the drill puts the
 * bot back on the start platform, and takes the technique it was given so two bots on the same arena can be compared
 * as methods rather than as players.
 */
public final class BridgeBrain extends BaseBrain {

    private final int dx, dz;
    private final int maxBlocks;
    private Bridger.Tech tech;
    private int cooldown;
    private int runs;

    public BridgeBrain(int dx, int dz, int maxBlocks, Bridger.Tech tech) {
        this.dx = dx == 0 && dz == 0 ? 1 : Integer.signum(dx);
        this.dz = dx == 0 && dz == 0 ? 0 : Integer.signum(dz);
        this.maxBlocks = Math.max(2, maxBlocks);
        this.tech = tech;
    }

    @Override
    protected void onStart() {
        if (tech != null) ctx.nav.bridger().forceTech(tech);
    }

    @Override
    protected void think() {
        Bridger bridger = ctx.nav.bridger();
        if (cooldown > 0) { cooldown--; ctx.motor.halt(); return; }

        if (bridger.active()) {
            Bridger.Result r = bridger.tick();
            if (r != Bridger.Result.RUNNING) {
                if (r == Bridger.Result.DONE) {
                    runs++;
                    CombatStats.inc("practice.bridge.run");
                }
                // a step back onto the block and a beat to settle, then the next run: a bot that instantly restarts
                // reads as a machine and, worse, starts the next bridge from the lip
                cooldown = 20;
                ctx.motor.sneak(true);
                ctx.motor.move(0, 0);
            }
            return;
        }

        // a run only starts from solid ground with blocks in hand
        Location p = ctx.pos();
        if (!ctx.c.onGround()) { ctx.motor.halt(); return; }
        if (!Inv.has(ctx.player, Inv.BLOCK)) {
            // out of blocks: stand still rather than walk off the end of the bridge looking for some
            ctx.motor.halt();
            CombatStats.inc("practice.bridge.out");
            return;
        }
        int fx = (int) Math.floor(p.getX());
        int fy = (int) Math.floor(p.getY() + 0.01);
        int fz = (int) Math.floor(p.getZ());
        if (!Shape.floor(ctx.world().shape(fx, fy - 1, fz))) { ctx.motor.halt(); return; }
        bridger.start(dx, dz, fx, fy, fz, maxBlocks);
        CombatStats.inc("practice.bridge.start");
    }

    @Override
    protected void onInactive() { }

    /** How many runs this bot has finished, for the drill's per-bot line. */
    public int runs() { return runs; }
}
