package net.h2020.bots.ai.brain.bedwars;

import net.h2020.bots.ai.brain.BedwarsBrain;
import net.h2020.bots.ai.nav.NavOptions;
import net.h2020.bots.ai.world.WorldView;
import org.bukkit.Location;

/**
 * Getting rid of yourself on purpose, the way a team asks for it with "void": walk to the nearest edge and step into
 * the void. With the bed alive that is a free trip home (faster than walking, and it denies the kill); with the bed
 * gone it would be the final death, so the brain never starts this then.
 */
public final class VoidTask extends BwTask {
    private Location edge;
    private long looked;

    public VoidTask(BedwarsBrain brain) { super(brain); }

    @Override public String name() { return "void"; }

    @Override
    public boolean tick() {
        if (age() > 20 * 12) return false;
        Location me = ctx.pos();
        if (me.getY() < 8) return false;                     // already falling: done
        if (edge == null || ctx.tick - looked > 40) {
            edge = findEdge(ctx, me);
            looked = ctx.tick;
        }
        if (edge == null) return false;
        double d = me.distance(edge);
        if (d > 1.4) {
            NavOptions o = NavOptions.walk();
            o.bridge = false;
            o.pillar = false;
            o.parkourGap = 0;                                 // do not bridge the very gap we are aiming for
            o.avoidEnemies = false;
            ctx.nav.goTo(edge, 1.0, o);
            ctx.nav.tick();
            if (ctx.nav.failed()) { edge = null; return true; }
            return true;
        }
        // at the lip: walk off it
        ctx.nav.stop();
        ctx.motor.moveToward(edge.getX() - me.getX(), edge.getZ() - me.getZ());
        ctx.motor.sprint(true);
        ctx.motor.jump(true);
        return true;
    }

    /** The closest standable cell next to a hole with nothing under it. */
    static Location findEdge(net.h2020.bots.ai.BotContext ctx, Location me) {
        WorldView wv = ctx.world();
        int x = (int) Math.floor(me.getX()), y = (int) Math.floor(me.getY() + 0.01), z = (int) Math.floor(me.getZ());
        Location best = null;
        double bd = Double.MAX_VALUE;
        for (int r = 1; r <= 9; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;      // ring of radius r
                    int fx = x + dx, fz = z + dz;
                    if (!wv.passable(fx, y, fz) || !wv.voidBelow(fx, y + 1, fz, 40)) continue;
                    if (!(wv.standable(x + dx + Integer.signum(dx), y - 1, z + dz + Integer.signum(dz))
                            || wv.standable(x + dx + Integer.signum(dx), y, z + dz + Integer.signum(dz)))) continue;
                    double d = Math.abs(dx) + Math.abs(dz);
                    if (d < bd) { bd = d; best = new Location(me.getWorld(), fx + 0.5, y, fz + 0.5); }
                }
            }
            if (best != null) return best;
        }
        return null;
    }
}
