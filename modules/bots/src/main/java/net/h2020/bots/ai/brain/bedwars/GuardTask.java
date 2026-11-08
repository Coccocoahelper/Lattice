package net.h2020.bots.ai.brain.bedwars;

import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.brain.BedwarsBrain;
import net.h2020.bots.ai.nav.NavOptions;
import net.h2020.bots.ai.util.AIMath;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Hang around the bed/base: stand near the bed, wander a few blocks, watch the bridges (the gaze and perception do
 * the watching; combat interrupts handle intruders). Also the "go home, someone is at our bed" response.
 */
public final class GuardTask extends BwTask {
    private final long duration;
    private Location spot;
    private long nextMove;

    public GuardTask(BedwarsBrain brain, long durationTicks) {
        super(brain);
        this.duration = durationTicks;
    }

    @Override public String name() { return "guard"; }

    @Override
    public boolean tick() {
        if (age() > duration) return false;
        Location bed = brain.color() == null ? null : view.bedLocation(brain.color());
        if (bed == null) return false;
        // somebody staircasing above the base turns this spot into a grave: step out from under him instead of
        // dying in the corner (the classic "sitting duck under a bridge" death)
        for (Player p : bed.getWorld().getPlayers()) {
            if (p == ctx.player || p.isDead() || brain.teammates().contains(p)) continue;
            if (p.getLocation().distance(bed) > 16) continue;
            if (p.getLocation().getY() > ctx.pos().getY() + 1.5) {
                Location out = bed.clone();
                Vector dir = ctx.pos().toVector().subtract(bed.toVector()).setY(0);
                if (dir.lengthSquared() < 0.01) dir = new Vector(1, 0, 0);
                out = bed.clone().add(dir.normalize().multiply(9));
                int land = ctx.world().landing(out.getBlockX(), out.getBlockY() + 3, out.getBlockZ(), 12);
                if (land != Integer.MIN_VALUE) {
                    out.setY(land);
                    if (ctx.pos().distance(out) > 1.5) {
                        ctx.nav.goTo(out, 1.2, NavOptions.walk());
                        ctx.nav.tick();
                        net.h2020.api.stats.CombatStats.inc("guard.steppedout");
                        return true;
                    }
                }
            }
        }
        if (spot == null || ctx.tick >= nextMove) {
            double a = ctx.rng.nextDouble() * Math.PI * 2, r = AIMath.range(ctx.rng, 2.0, 5.0);
            spot = bed.clone().add(0.5 + Math.cos(a) * r, 0, 0.5 + Math.sin(a) * r);
            int land = ctx.world().landing(spot.getBlockX(), spot.getBlockY() + 2, spot.getBlockZ(), 5);
            if (land == Integer.MIN_VALUE || ctx.world().voidSides(spot.getBlockX(), land, spot.getBlockZ()) > 0) { spot = bed.clone().add(0.5, 0, 0.5); nextMove = ctx.tick + 40; }
            else { spot.setY(land); nextMove = ctx.tick + AIMath.range(ctx.rng, 30, 90); net.h2020.api.stats.CombatStats.inc("guard.patrol"); }
        }
        if (ctx.pos().distance(spot) > 1.2) {
            NavOptions o = NavOptions.walk();
            o.sprint = ctx.pos().distance(spot) > 8;
            BedDefense def = brain.defense();
            if (def != null) o.forbidden = def.allCells;
            ctx.nav.goTo(spot, 1.0, o);
            ctx.nav.tick();
            if (ctx.nav.failed()) { spot = null; ctx.nav.stop(); }
        } else ctx.nav.stop();
        return true;
    }
}
