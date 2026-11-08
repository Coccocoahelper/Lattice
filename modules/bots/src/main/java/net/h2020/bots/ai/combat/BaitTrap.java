package net.h2020.bots.ai.combat;

import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.motor.BlockPlacer;
import net.h2020.bots.ai.world.Shape;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Bait into the trap instead of only building it.
 * <p>
 * A one-wide bridge or a ledge is a trap for whoever is standing on it, and the way a player uses it is to give ground:
 * step back so the opponent follows onto the narrow part, then put a block down behind them so the only way out is
 * through you or into the void. The bot only starts this with a way out of its own - the cell behind it must still be
 * standable - so it never bails itself into the hole it just made.
 */
public final class BaitTrap {
    /**
     * Off by default: the give-ground half works, the cut never places (every run: cut started, zero
     * trap.bait.placed), so enabling it today would only teach bots to retreat for free. Flip to true once the
     * cut confirms.
     */
    public static boolean enabled = false;

    private final BotContext ctx;
    private final BlockPlacer placer;
    private int phase;                 // 0 idle, 1 giving ground, 2 cutting
    private long until;
    private boolean cut;
    private java.util.UUID baited;
    private int cutRetries;
    private long cutTick = -1000;

    public BaitTrap(BotContext ctx) {
        this.ctx = ctx;
        this.placer = new BlockPlacer(ctx);
    }

    public boolean active() { return phase != 0; }

    /** The baited player died: the trap paid, if it was quick enough to be the cause. */
    public void noteKill(Player victim) {
        if (victim == null || baited == null) return;
        if (baited.equals(victim.getUniqueId()) && ctx.tick - cutTick <= 100) CombatStats.inc("trap.win");
        baited = null;
    }

    public void reset() {
        phase = 0;
        cut = false;
        if (placer.active()) placer.cancel();
    }

    /** @return true when the bait owns this tick (movement or placement). */
    public boolean tick(Player target, double d) {
        if (!enabled || target == null) { reset(); return false; }
        switch (phase) {
            case 0: return start(target, d);
            case 1: return giveGround(target, d);
            case 2: return placeCut(target);
            default: reset(); return false;
        }
    }

    private boolean start(Player target, double d) {
        if (d > 4.5 || d < 1.8) { CombatStats.inc("trap.reject.range"); return false; }
        if (!chasing(target, d)) { CombatStats.inc("trap.reject.chase"); return false; }
        if (ctx.health() < 12) { CombatStats.inc("trap.reject.hp"); return false; }
        if (!Inv.has(ctx.player, Inv.BLOCK)) { CombatStats.inc("trap.reject.blocks"); return false; }
        // committing to a trap means holding blocks, not a sword: swap now, and if the swap needs its own tick,
        // come back next tick rather than refusing the play
        if (!Inv.holding(ctx.player, Inv.BLOCK)) {
            if (Inv.equip(ctx.player, ctx.c, Inv.BLOCK, 1)) return true;   // owns this tick; the hotbar is ready
            CombatStats.inc("trap.bait.swap");
            return true;
        }
        Location me = ctx.pos();
        // never bait without an escape of our own: the cell behind us has to be standable
        if (!exitBehind(me, target)) { CombatStats.inc("trap.reject.exit"); return false; }
        // and there has to be narrow ground near them: on it already, or one step away - the bait is to walk them
        // onto the edge, not to wait until they volunteer
        // narrow ground near them, or we are the ones standing on the narrow part (a bridge fight): both are baitable
        if (!narrowNear(target.getLocation(), 2) && !narrow(me)) { CombatStats.inc("trap.reject.ground"); return false; }
        phase = 1;
        until = ctx.tick + 40;
        CombatStats.inc("trap.bait.start");
        return true;
    }

    /** Walk backwards, away from them, so they step onto the narrow ground after us. */
    private boolean giveGround(Player target, double d) {
        Location me = ctx.pos();
        double dx = me.getX() - target.getLocation().getX(), dz = me.getZ() - target.getLocation().getZ();
        double l = Math.max(1e-3, Math.hypot(dx, dz));
        if (ctx.tick > until) { reset(); CombatStats.inc("trap.bait.abort"); return false; }
        if (narrowNear(target.getLocation(), 1) && d <= 4.5) {
            Location behind = cutCell(target);
            if (behind != null) {
                cutRetries = 0;
                phase = 2;
                until = ctx.tick + 20;
                placer.setFast(false);             // let the aim settle: the cut is one block, accuracy beats speed
                placer.setNeedSight(true);
                placer.start(behind.getBlockX(), behind.getBlockY(), behind.getBlockZ(), Inv.ANY_BLOCK);
                baited = target.getUniqueId();
                cutTick = ctx.tick;
                CombatStats.inc(cut ? "trap.bait.cut" : "trap.bait.headcut");
                return true;
            }
        }
        ctx.motor.moveToward(dx / l, dz / l);      // back off, facing them
        ctx.motor.sprint(false);
        return true;
    }

    private boolean placeCut(Player target) {
        ctx.motor.move(0, 0);                      // stand still for the click: a moving body never settles the ray
        BlockPlacer.Result r = placer.tick();
        if (r == BlockPlacer.Result.DONE) { CombatStats.inc("trap.bait.placed"); reset(); return true; }
        if (r == BlockPlacer.Result.FAILED || ctx.tick > until) {
            // the cut cell was out of reach or hidden: try the other one before giving up on the trap
            if (cutRetries++ < 1) {
                Location alt = cut ? openCell((int) Math.floor(target.getLocation().getX()),
                        (int) Math.floor(target.getLocation().getY() + 0.01) + 1,
                        (int) Math.floor(target.getLocation().getZ()), ctx.pos()) : null;
                if (alt == null) {
                    Location me = ctx.pos(), e = target.getLocation();
                    double dx = e.getX() - me.getX(), dz = e.getZ() - me.getZ();
                    double l = Math.max(1e-3, Math.hypot(dx, dz));
                    alt = openCell((int) Math.floor(e.getX() + dx / l), (int) Math.floor(e.getY() + 0.01),
                            (int) Math.floor(e.getZ() + dz / l), me);
                }
                if (alt != null) {
                    cut = !cut;
                    until = ctx.tick + 20;
                    placer.setFast(true);
                    placer.setNeedSight(false);       // second try: let the client's ray decide, not the sight test
                    placer.start(alt.getBlockX(), alt.getBlockY(), alt.getBlockZ(), Inv.ANY_BLOCK);
                    CombatStats.inc("trap.bait.retry");
                    return true;
                }
            }
            CombatStats.inc("trap.bait.fail");
            // why the cut never landed: the placer's state at the moment it gave up
            java.util.logging.Logger.getLogger("BaitTrap").info("[trap] cut failed holding="
                    + (ctx.player.getItemInHand() == null ? "-" : ctx.player.getItemInHand().getType())
                    + " blocks=" + Inv.count(ctx.player, Inv.BLOCK)
                    + " target=" + placer.targetX() + "," + placer.targetY() + "," + placer.targetZ()
                    + " active=" + placer.active() + " aimed=" + ctx.c.aimSettled()
                    + " d=" + Math.round(ctx.pos().distance(target.getLocation())));
            reset();
            return false;
        }
        return true;
    }

    /**
     * Are they actually coming? Giving ground to somebody who is backing off just walks the bot away from a fight.
     * Velocity towards us is the direct test; when they are on top of us it does not matter.
     */
    private boolean chasing(Player target, double d) {
        if (d < 3.0) return true;
        org.bukkit.util.Vector v = target.getVelocity();
        Location me = ctx.pos(), e = target.getLocation();
        double dx = me.getX() - e.getX(), dz = me.getZ() - e.getZ();
        double l = Math.max(1e-3, Math.hypot(dx, dz));
        double closing = (v.getX() * dx + v.getZ() * dz) / l;
        return closing > 0.02 || target.isSprinting();
    }

    /** Narrow ground within `r` of them (the cell they are on counts double). */
    private boolean narrowNear(Location at, int r) {
        if (narrow(at)) return true;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                Location l = at.clone().add(dx, 0, dz);
                if (narrow(l)) return true;
            }
        }
        return false;
    }

    /** They are standing on a cell with the void on at least two sides. */
    private boolean narrow(Location at) {
        return ctx.world().voidSides(at.getBlockX(), (int) Math.floor(at.getY() + 0.01), at.getBlockZ()) >= 2;
    }

    /**
     * Where the cut goes. First choice is the cell on their far side - the step back they would need. When that is out
     * of reach (the bait just walked away from them), the cell above their head still closes the fight: they cannot
     * jump out of it, and it is always inside reach at four blocks.
     */
    private Location cutCell(Player target) {
        Location me = ctx.pos(), e = target.getLocation();
        double dx = e.getX() - me.getX(), dz = e.getZ() - me.getZ();
        double l = Math.max(1e-3, Math.hypot(dx, dz));
        int ey = (int) Math.floor(e.getY() + 0.01);
        Location back = openCell((int) Math.floor(e.getX() + dx / l), ey, (int) Math.floor(e.getZ() + dz / l), me);
        if (back != null) { cut = true; return back; }
        Location over = openCell((int) Math.floor(e.getX()), ey + 1, (int) Math.floor(e.getZ()), me);
        if (over != null) { cut = false; return over; }
        return null;
    }

    private Location openCell(int x, int y, int z, Location me) {
        if (ctx.world().shape(x, y, z) != Shape.OPEN || ctx.world().shape(x, y + 1, z) != Shape.OPEN) return null;
        Location at = new Location(me.getWorld(), x, y, z);
        if (me.distance(at) > 4.2) return null;
        return at;
    }

    /** Is there ground behind us, away from them, that we could still step to? */
    private boolean exitBehind(Location me, Player target) {
        double dx = me.getX() - target.getLocation().getX(), dz = me.getZ() - target.getLocation().getZ();
        double l = Math.max(1e-3, Math.hypot(dx, dz));
        int x = (int) Math.floor(me.getX() + dx / l), z = (int) Math.floor(me.getZ() + dz / l);
        int y = (int) Math.floor(me.getY() + 0.01);
        return ctx.world().standable(x, y - 1, z) || ctx.world().standable(x, y, z);
    }
}
