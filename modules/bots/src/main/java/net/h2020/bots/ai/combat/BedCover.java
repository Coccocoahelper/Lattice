package net.h2020.bots.ai.combat;

import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.brain.bedwars.BedDefense;
import net.h2020.bots.ai.motor.BlockPlacer;
import net.h2020.bots.ai.world.WorldView;
import org.bukkit.Location;

/**
 * Geometry shared by everything that digs a bed: where the hole is, and which cells have to be closed so a defender
 * cannot reach around the hole while the bed is being broken (the "block in" of the guide, done from the middle of
 * the face rather than a corner).
 */
public final class BedCover {
    private BedCover() {}

    /** Centre of the bed (both halves). */
    public static Location centre(Location foot) {
        int[] head = BedDefense.head(foot.getWorld(), foot);
        return new Location(foot.getWorld(),
                (foot.getBlockX() + head[0]) / 2.0 + 0.5,
                foot.getBlockY() + 0.5,
                (foot.getBlockZ() + head[2]) / 2.0 + 0.5);
    }

    /**
     * A free cell beside the tunnel mouth (one block to the side of the line between us and the bed) that a defender
     * could otherwise hit us through, or null when both sides are already blocked / unusable.
     */
    public static int[] sideCell(BotContext ctx, Location bed, Location me) {
        WorldView wv = ctx.world();
        Location c = centre(bed);
        double dx = me.getX() - c.getX(), dz = me.getZ() - c.getZ();
        double l = Math.max(1e-3, Math.sqrt(dx * dx + dz * dz));
        dx /= l; dz /= l;
        int fy = floor(me.getY() + 0.01);
        int[] best = null;
        double bestD = Double.MAX_VALUE;
        for (int sign = -1; sign <= 1; sign += 2) {
            int fx = floor(me.getX() - dz * sign), fz = floor(me.getZ() + dx * sign);
            if (!wv.passable(fx, fy, fz)) continue;
            if (!BlockPlacer.cellFree(new Location(me.getWorld(), fx, fy, fz))) continue;
            double d = Math.abs(fx + 0.5 - me.getX()) + Math.abs(fz + 0.5 - me.getZ());
            if (d < bestD) { bestD = d; best = new int[]{fx, fy, fz}; }
        }
        return best;
    }

    private static int floor(double d) { int i = (int) d; return d < i ? i - 1 : i; }
}
