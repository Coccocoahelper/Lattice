package net.h2020.bots.ai.combat;

import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.world.Shape;
import net.h2020.bots.ai.world.WorldView;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Where the bot is standing, in the two ways that decide a 1.8 fight before a sword is swung:
 * <ul>
 * <li><b>high ground</b>: the opponent is above us by more than a half block and close enough that their jump, fall
 *   and hit all reach - a person on a bridge under somebody standing on the ledge is one hit from the void;</li>
 * <li><b>a bad spot</b>: on a one-wide line with the void at our back and nothing to put behind us, or below them with
 *   no way up.</li>
 * </ul>
 * Used to decline a fight that starts from there, to step back out of their drop before the hit lands, and to know
 * when there is nothing to run to (then fighting in place is the better option).
 */
public final class PositionSense {
    private PositionSense() {}

    /** They are above us and near enough that jump -> fall -> hit lands on us. */
    public static boolean underTheirDrop(Location me, Player enemy) {
        if (enemy == null) return false;
        Location e = enemy.getLocation();
        double dy = e.getY() - me.getY();
        if (dy < 1.2 || dy > 6.0) return false;
        double dx = e.getX() - me.getX(), dz = e.getZ() - me.getZ();
        double h = Math.sqrt(dx * dx + dz * dz);
        return h <= 4.5;
    }

    /** Standing on a one-wide line with the void behind us (the usual bridge trap). */
    public static boolean onExposedLine(BotContext ctx, Location me) {
        WorldView wv = ctx.world();
        int x = floor(me.getX()), y = floor(me.getY() + 0.01), z = floor(me.getZ());
        if (wv.voidSides(x, y, z) < 2) return false;
        return true;
    }

    /**
     * Fighting on a cell with the void on two or more sides, inside their reach. That is a hazard whatever the model
     * thinks the win chance is: one hit and the fight is over, so it is worth a step sideways even at even odds.
     */
    public static boolean exposedHazard(BotContext ctx, Location me, Player enemy) {
        if (enemy == null) return false;
        if (!onExposedLine(ctx, me)) return false;
        double dx = enemy.getLocation().getX() - me.getX(), dz = enemy.getLocation().getZ() - me.getZ();
        return Math.sqrt(dx * dx + dz * dz) <= 6.0;
    }

    /**
     * Should this fight be avoided from where we stand? True when they are above us within drop range, or we are on an
     * exposed line and they are healthy enough to trade, or we are below them with no way up.
     */
    public static boolean badPosition(BotContext ctx, Location me, Player enemy, double pWin) {
        if (enemy == null) return false;
        Location e = enemy.getLocation();
        double up = e.getY() - me.getY();
        if (underTheirDrop(me, enemy) && pWin < 0.62) return true;
        if (up > 2.2 && pWin < 0.5) return true;
        if (onExposedLine(ctx, me) && pWin < 0.45) return true;
        return false;
    }

    /**
     * A cell that turns a bad stance into a good one, rather than a cell to run to. Scores the ring around the bot on
     * three things a player fixes without thinking: get off the void edge, get level with or above them, and stay in
     * reach. Returns null when the current cell already wins, so nothing moves for no reason.
     */
    public static Location correctSpot(BotContext ctx, Location me, Player enemy) {
        if (enemy == null) return null;
        WorldView wv = ctx.world();
        int y = floor(me.getY() + 0.01);
        int curVoid = wv.voidSides(floor(me.getX()), y, floor(me.getZ()));
        double curScore = -curVoid * 2.0 - (enemy.getLocation().getY() - me.getY()) * 0.8;
        Location best = null;
        double bestScore = curScore + 0.35;              // must be meaningfully better than standing still
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (dx == 0 && dz == 0) continue;
                int tx = floor(me.getX()) + dx, tz = floor(me.getZ()) + dz;
                int ty = -1;
                if (wv.standable(tx, y - 1, tz)) ty = y;
                else if (wv.standable(tx, y, tz)) ty = y + 1;
                else if (wv.standable(tx, y - 2, tz)) ty = y - 1;
                if (ty < 0) continue;
                double dEnemy = Math.hypot(tx + 0.5 - enemy.getLocation().getX(), tz + 0.5 - enemy.getLocation().getZ());
                if (dEnemy < 1.4 || dEnemy > 6.0) continue;   // stay in the fight, just not inside them
                int voidSides = wv.voidSides(tx, ty, tz);
                double score = -voidSides * 2.0 - (enemy.getLocation().getY() - ty) * 0.8;
                if (voidSides == 0) score += 0.8;              // solid ground under and around us
                if (ty > y) score += 0.6;                      // height is the fight
                else if (ty < y) score -= 0.5;
                if (dEnemy >= 2.0 && dEnemy <= 3.6) score += 0.5;  // reach without being inside their hitbox
                if (score > bestScore) { bestScore = score; best = new Location(me.getWorld(), tx + 0.5, ty, tz + 0.5); }
            }
        }
        if (best != null) net.h2020.api.stats.CombatStats.inc("pos.correct.found");
        return best;
    }

    /** Somewhere to step to that is not over the void (null when boxed in). */
    public static Location escapeSpot(BotContext ctx, Location me, Player enemy) {
        WorldView wv = ctx.world();
        double dx = me.getX() - enemy.getLocation().getX(), dz = me.getZ() - enemy.getLocation().getZ();
        double l = Math.max(1e-3, Math.sqrt(dx * dx + dz * dz));
        dx /= l; dz /= l;
        int y = floor(me.getY() + 0.01);
        for (double step = 2.0; step <= 4.0; step += 1.0) {
            int tx = floor(me.getX() + dx * step), tz = floor(me.getZ() + dz * step);
            if (wv.standable(tx, y - 1, tz) || wv.standable(tx, y, tz)) {
                return new Location(me.getWorld(), tx + 0.5, y, tz + 0.5);
            }
        }
        return null;
    }

    private static int floor(double d) { int i = (int) d; return d < i ? i - 1 : i; }
}
