package net.h2020.bots.ai.brain.bedwars;

import net.h2020.bots.ai.BotAI;
import net.h2020.bots.ai.brain.BaseBrain;
import net.h2020.bots.ai.brain.BedwarsBrain;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Somebody is coming for the bed.
 *
 * <p>Two things the bots were missing, both visible in one game against a real player: an enemy bridging to an island
 * was only noticed at <b>12 blocks</b> - by which time they are on the island - and the answer to it was to stand near
 * the bed rather than to meet them. The player walked island to island and killed eight bots without ever being
 * contested.
 *
 * <p>So this detects the approach at range, and it asks for a specific number of bodies: the closest bot of the team
 * intercepts, the next closest converges, and everybody else keeps working. A team that all runs home for one intruder
 * loses the rest of the map.
 *
 * <p>Detection is the player's own position - somebody bridging toward your bed is not a perception problem, they are
 * in front of you - plus the direction they are moving, so a player crossing the map sideways on their way somewhere
 * else is not treated as an attack.
 */
public final class Incursion {

    /** How far from the bed an approach is worth answering. */
    public static final double DETECT = 45;
    /** Inside this they are on the island: no direction test needed. */
    public static final double ON_ISLAND = 16;
    /** The second bot joins only if it is this close to the intruder. */
    public static final double SUPPORT_RANGE = 30;

    private Incursion() { }

    /**
     * The enemy who is actually coming for our bed: nearest one that is either already on the island or closing on the
     * bed, or null. Only live, visible, non-teammate players count.
     */
    public static Player threat(BedwarsBrain brain) {
        Location bed = brain.view().bedLocation(brain.color());
        if (bed == null || brain.color() == null) return null;
        Player me = brain.context().player;
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : bed.getWorld().getPlayers()) {
            if (p == me || p.isDead() || !p.isOnline()) continue;
            if (brain.teammates().contains(p)) continue;
            if (brain.view().teamOf(p) == null) continue;
            double d = p.getLocation().distance(bed);
            if (d > DETECT) continue;
            if (d > ON_ISLAND && !closing(p, bed)) continue;
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    /** Full strength in the bed's direction, or close to it: walking past the island does not count. */
    private static boolean closing(Player p, Location bed) {
        Vector v = p.getVelocity();
        double speed = Math.hypot(v.getX(), v.getZ());
        if (speed < 0.03) return false;                       // standing still: not an approach
        double dx = bed.getX() - p.getLocation().getX();
        double dz = bed.getZ() - p.getLocation().getZ();
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) return true;
        double dot = (v.getX() * dx + v.getZ() * dz) / (speed * len);
        return dot > 0.25;                                    // within about 75 degrees of the bed
    }

    /**
     * How many of this bot's team are closer to the intruder than it is. 0 means this bot is the interceptor, 1 means
     * it is the one that converges, and anything higher keeps working.
     */
    public static int rank(BedwarsBrain brain, Player intruder) {
        if (intruder == null) return 99;
        Player me = brain.context().player;
        Location at = intruder.getLocation();
        double mine = me.getWorld() == at.getWorld() ? me.getLocation().distance(at) : Double.MAX_VALUE;
        int closer = 0;
        for (Player mate : brain.teammates()) {
            if (mate == me || mate.isDead() || !mate.isOnline()) continue;
            BaseBrain b = BotAI.brainOf(mate);
            if (!(b instanceof BedwarsBrain) || b.context() == null) continue;
            if (mate.getWorld() != at.getWorld()) continue;
            double d = mate.getLocation().distance(at);
            if (d < mine - 0.5) closer++;                     // half a block of hysteresis, so two bots do not swap
        }
        return closer;
    }

    /** Teammates who are also answering this intruder, for the callout. */
    public static List<Player> responders(BedwarsBrain brain, Player intruder) {
        List<Player> out = new ArrayList<Player>();
        for (Player mate : brain.teammates()) {
            if (mate.isDead() || !mate.isOnline()) continue;
            BaseBrain b = BotAI.brainOf(mate);
            if (b instanceof BedwarsBrain && ((BedwarsBrain) b).task() instanceof InterceptTask) out.add(mate);
        }
        return out;
    }
}
