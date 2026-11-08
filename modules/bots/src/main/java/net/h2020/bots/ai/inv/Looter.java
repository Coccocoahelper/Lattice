package net.h2020.bots.ai.inv;

import net.h2020.api.bot.AimIntent;
import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.motor.Motor;
import net.h2020.bots.ai.nav.NavOptions;
import net.h2020.bots.ai.util.AIMath;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Chest looting with GUI pacing: walk up, look precisely at the chest, open it, take a moment to read, then
 * shift-click (or number-key the sword/blocks straight into the hotbar) the best items first with human gaps,
 * leaving junk behind, then close.
 */
public final class Looter {
    public enum Result { RUNNING, DONE, FAILED }

    private enum Phase { APPROACH, AIM, OPENING, READ, TAKE, CLOSE }

    private final BotContext ctx;
    private Block chest;
    private Phase phase;
    private int phaseTicks, wait, takes, maxTakes, opens;
    /** Approach progress: where we last moved from and when (a stuck walk gives the chest up), and the time cap. */
    private Location anchor;
    private int lastProgress;
    private double capTicks, approachRadius;
    private NavOptions nav;
    private boolean active, noRoute;

    public Looter(BotContext ctx) { this.ctx = ctx; }

    public boolean active() { return active; }
    public Block chest() { return chest; }
    /** The last failure was the navigator finding no route (not a stuck walk or a refused open). */
    public boolean noRoute() { return noRoute; }

    public void start(Block chest, NavOptions navOptions) {
        this.chest = chest;
        this.nav = navOptions == null ? NavOptions.walk() : navOptions;
        phase = Phase.APPROACH; phaseTicks = 0; wait = 0; takes = 0; opens = 0; noRoute = false;
        dbgTotal = 0; dbgApproach = 0;
        maxTakes = AIMath.range(ctx.rng, 5, 11);
        anchor = null; lastProgress = 0; capTicks = Double.NaN; approachRadius = 2.8;
        ctx.nav.stop(); // a fresh route: a failure remembered for this chest (e.g. walk-only) must not stick
        active = true;
    }

    public void cancel() {
        if (active && ctx.c.containerOpen()) ctx.c.closeWindow();
        active = false;
    }

    private Location chestTop() {
        return new Location(chest.getWorld(), chest.getX() + 0.5 + (ctx.rng.nextDouble() - 0.5) * 0.3, chest.getY() + 0.8,
                chest.getZ() + 0.5 + (ctx.rng.nextDouble() - 0.5) * 0.3);
    }

    private int dbgTotal, dbgApproach;
    private static final boolean DEBUG = Boolean.getBoolean("h2020.fightdebug");

    public Result tick() {
        if (active) { dbgTotal++; if (phase == Phase.APPROACH) dbgApproach++; }
        Phase before = phase;
        Result r = step();
        if (DEBUG && r != Result.RUNNING && dbgTotal > 0) {
            org.bukkit.Bukkit.getLogger().info(String.format("[lootdebug] %s %s at %s: %.1fs total, %.1fs walking, %d items",
                    ctx.player.getName(), r, before, dbgTotal / 20.0, dbgApproach / 20.0, takes));
            dbgTotal = 0;
        }
        return r;
    }

    private Result step() {
        if (!active) return Result.FAILED;
        phaseTicks++;
        Material t = chest.getType();
        if (t != Material.CHEST && t != Material.TRAPPED_CHEST) { cancel(); return Result.FAILED; }
        Location eye = ctx.eye();
        double d = eye.distance(chest.getLocation().add(0.5, 0.5, 0.5));
        switch (phase) {
            case APPROACH:
                // players open chests from ~4 blocks without walking right up to them
                if (d < 4.1) { ctx.nav.stop(); phase = Phase.AIM; phaseTicks = 0; return Result.RUNNING; }
                ctx.nav.goTo(chest.getLocation().add(0.5, 1, 0.5), approachRadius, nav);
                ctx.nav.tick();
                // the navigator got as close as it can: try from here if it is within reach, else give the chest up
                if (ctx.nav.arrived()) {
                    if (d < 4.5) { ctx.nav.stop(); phase = Phase.AIM; phaseTicks = 0; return Result.RUNNING; }
                    // arrived by the loose radius but still out of reach (chest below/above us): step in closer once
                    if (approachRadius > 1.5) { approachRadius = 1.2; ctx.nav.stop(); return Result.RUNNING; }
                    active = false;
                    return Result.FAILED;
                }
                if (Double.isNaN(capTicks)) capTicks = 120 + 6 * d;
                // stuck = not moving for 3 s (paths may lead away first: down a ladder, round a wall). Waiting for a
                // route or a dig/ladder action in progress is not being stuck; the overall cap still applies.
                Location here = ctx.pos();
                if (anchor == null || anchor.getWorld() != here.getWorld() || anchor.distanceSquared(here) > 1.0 || ctx.nav.doingAction()
                        || ctx.nav.state() == net.h2020.bots.ai.nav.Navigator.State.PLANNING) {
                    anchor = here; lastProgress = phaseTicks;
                }
                if (ctx.nav.failed() || phaseTicks > capTicks || phaseTicks - lastProgress > 60) {
                    noRoute = ctx.nav.failed();
                    active = false;
                    return Result.FAILED;
                }
                return Result.RUNNING;
            case AIM: {
                ctx.motor.aimPoint(Motor.P_TASK, chestTop(), AimIntent.PRECISE, 0.3);
                Block tb = ctx.c.targetedBlock();
                if (tb != null && tb.equals(chest) && phaseTicks > 2) {
                    ctx.c.openContainer(chest);
                    phase = Phase.OPENING; phaseTicks = 0;
                } else if (phaseTicks > 14) {
                    if (d > 4.5) { phase = Phase.APPROACH; phaseTicks = 0; }
                    else { ctx.c.openContainer(chest); phase = Phase.OPENING; phaseTicks = 0; }
                }
                return Result.RUNNING;
            }
            case OPENING:
                if (ctx.c.containerOpen()) {
                    phase = Phase.READ; phaseTicks = 0;
                    wait = AIMath.msToTicks(ctx.rng, 260 - 120 * ctx.persona.skill.overall);
                } else if (phaseTicks > 20) {
                    // the server keeps refusing (out of reach / no line of sight): give this chest up
                    if (++opens >= 3) { active = false; return Result.FAILED; }
                    phase = Phase.AIM; phaseTicks = 0;
                }
                return Result.RUNNING;
            case READ:
                if (!ctx.c.containerOpen()) { active = false; return Result.DONE; }
                if (phaseTicks >= wait) { phase = Phase.TAKE; phaseTicks = 0; wait = 0; }
                return Result.RUNNING;
            case TAKE: {
                if (!ctx.c.containerOpen()) { active = false; return Result.DONE; }
                if (phaseTicks < wait) return Result.RUNNING;
                if (takes >= maxTakes || !takeOne()) {
                    phase = Phase.CLOSE; phaseTicks = 0; wait = AIMath.range(ctx.rng, 1, 4);
                    return Result.RUNNING;
                }
                takes++;
                phaseTicks = 0;
                double gapMs = AIMath.gauss(ctx.rng, 160 - 85 * ctx.persona.skill.overall, 35, 50, 360);
                wait = Math.max(1, (int) Math.round(gapMs / 50.0));
                return Result.RUNNING;
            }
            case CLOSE:
                if (phaseTicks >= wait) {
                    if (ctx.c.containerOpen()) ctx.c.closeWindow();
                    active = false;
                    return Result.DONE;
                }
                return Result.RUNNING;
            default:
                return Result.FAILED;
        }
    }

    /** Click the most valuable item; false when nothing worth taking is left (or inventory full). */
    private boolean takeOne() {
        Inventory top = ctx.player.getOpenInventory().getTopInventory();
        int size = top.getSize();
        double bestMelee = Inv.bestMeleeValue(ctx.player);
        double[] worn = Inv.wornArmor(ctx.player);
        int blocks = Inv.count(ctx.player, Inv.BLOCK);
        boolean hasBow = Inv.has(ctx.player, Inv.BOW);
        int best = -1;
        double bv = 9.5 + (ctx.rng.nextDouble() < 0.25 ? -8 : 0); // sometimes they grab everything
        for (int i = 0; i < size; i++) {
            ItemStack it = top.getItem(i);
            if (Items.empty(it)) continue;
            double v = Items.lootValue(it, bestMelee, worn, blocks, hasBow) + ctx.rng.nextDouble() * 6;
            if (v > bv) { bv = v; best = i; }
        }
        if (best < 0) return false;
        if (ctx.player.getInventory().firstEmpty() < 0) return false;
        ItemStack it = top.getItem(best);
        // number-key the sword/blocks straight into the hotbar like experienced players
        if (ctx.persona.skill.overall > 0.45 && ctx.rng.nextDouble() < 0.6) {
            if (Items.isSword(it) && Items.meleeValue(it) > bestMelee) { ctx.c.clickWindow(best, 0, 2); return true; }
            if (Items.isBuildBlock(it) && Items.empty(ctx.player.getInventory().getItem(1))) { ctx.c.clickWindow(best, 1, 2); return true; }
        }
        ctx.c.clickWindow(best, 0, 1);
        return true;
    }
}
