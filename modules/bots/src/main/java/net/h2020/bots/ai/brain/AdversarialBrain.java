package net.h2020.bots.ai.brain;

import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.BotContext;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * A duel opponent that punishes a bad placement, so the placement policy can learn what a bad placement is.
 *
 * <p>The arena's ordinary brain fights, but it does not teach: measured over a full training run, 631 tactics started,
 * and only 11 produced a hit while 426 placements resolved to nothing. That is not because the placements were good -
 * it is because the opponent never reacted to them. A block is only "wrong" if someone makes you pay for it: a ramp
 * that leaves you on low ground gets you comboed, a wall that stops your own retreat gets you cornered, a stomp placed
 * at the wrong distance does nothing at all. An opponent that trades hits regardless of geometry teaches the policy that
 * every placement is worth about the same.
 *
 * <p>This brain is deliberately narrow and adversarial in three specific ways, each mapped to a placement mistake:
 *
 * <ul>
 *   <li><b>Punish height.</b> If the opponent is standing on a block above us, close and hit - high ground is the one
 *       advantage a ramp or a pillar buys, so losing it must cost.</li>
 *   <li><b>Punish a wall in the face.</b> If the opponent is behind a block it placed itself within two blocks, step
 *       around and hit within the opening, rather than politely waiting.</li>
 *   <li><b>Punish a retreat.</b> If the opponent backs off after being hit, chase and keep hitting for a short window,
 *       which is what makes a badly-placed backstep worse than not retreating at all.</li>
 * </ul>
 *
 * <p>Everything else is the same combat stack the arena already uses, so the policy is trained against the real motor
 * and the real aim model, not a simulation of them.
 */
public final class AdversarialBrain extends BaseBrain implements EmergencyBrain {

    /** How close the punisher wants to be before it commits. */
    private static final double REACH = 3.6;
    private static final double CHASE_RANGE = 18.0;

    private long nextScan;
    private Player target;
    private long repathAt;
    private Location chaseTo;

    /** Last time the opponent was above us, so a single tick of jumping does not reset the punish. */
    private long highGroundSeen = -1;
    private long punishUntil;
    private long lastHitTick;

    @Override
    protected void onActive() {
        highGroundSeen = -1;
        punishUntil = 0;
    }

    @Override
    protected void think() {
        if (ctx.tick >= nextScan || target == null || !target.isOnline() || target.isDead()) {
            nextScan = ctx.tick + 8;
            target = nearest();
        }
        if (target == null) {
            ctx.combat.disengage();
            ctx.nav.stop();
            return;
        }
        Location tl = target.getLocation();
        if (tl.getWorld() != ctx.player.getWorld()) { ctx.combat.disengage(); ctx.nav.stop(); return; }
        double d = ctx.pos().distance(tl);

        // ---------------------------------------------------------------- read the geometry we are being offered
        boolean above = tl.getY() - ctx.pos().getY() > 0.75;
        if (above) highGroundSeen = ctx.tick;
        // the opponent placed something between us that is still there: it is not a wall unless it is in the way
        boolean shielded = shielded(target, tl, d);
        boolean fleeing = target.getVelocity().lengthSquared() > 0.02
                && awayFromMe(target, tl);

        if (above || shielded || fleeing) punishUntil = Math.max(punishUntil, ctx.tick + 30);
        // Press always. The geometry decides how to press - flank a wall, sprint a retreat, straight-line a height
        // disadvantage - not whether to. Measured before this change: adv.punish.chase 0 against adv.punish.close 8,
        // i.e. the punisher only ever acted at melee range and the trainee was never made to pay for a bad block.
        boolean pressing = true;
        boolean edge = ctx.tick < punishUntil;

        if (d < CHASE_RANGE) {
            if (ctx.combat.target() != target) ctx.combat.engage(target);
        } else {
            ctx.combat.disengage();
        }

        // ---------------------------------------------------------------- act
        if (d <= REACH) {
            ctx.nav.stop();
            ctx.combat.tick();
            if (edge) CombatStats.inc("adv.punish.close");
            return;
        }
        // too far to hit: close, and close harder when there is something to punish. A shield is walked around rather
        // than through: the point is to be in reach from the side the block does not cover.
        ctx.combat.tick();
        Location aim = shielded ? flank(target, tl) : tl;
        // a punishing opponent sprints and re-paths twice as often, so an opening it spots is an opening it takes
        net.h2020.bots.ai.nav.NavOptions opts = net.h2020.bots.ai.nav.NavOptions.walk();
        opts.sprint = true;
        if (ctx.tick >= repathAt || chaseTo == null || chaseTo.distanceSquared(aim) > 6.0) {
            repathAt = ctx.tick + (pressing ? 6 : 14);
            chaseTo = aim.clone();
            ctx.nav.goTo(chaseTo, 1.2, opts);
            CombatStats.inc(edge ? "adv.punish.chase.edge" : "adv.punish.chase");
        }
        ctx.nav.tick();
    }

    /** Something solid between the two of us at chest height within reach: the opponent just boxed itself in or walled. */
    private boolean shielded(Player foe, Location tl, double d) {
        if (d > 5.0) return false;
        Location me = ctx.pos();
        double dx = tl.getX() - me.getX(), dz = tl.getZ() - me.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) return false;
        dx /= len; dz /= len;
        int y = (int) Math.floor(Math.min(me.getY(), tl.getY()) + 1.0);
        for (double step : new double[]{1.0, 1.8}) {
            int bx = (int) Math.floor(me.getX() + dx * step);
            int bz = (int) Math.floor(me.getZ() + dz * step);
            if (ctx.world().solid(bx, y, bz) || ctx.world().solid(bx, y + 1, bz)) return true;
        }
        return false;
    }

    /** A point beside the opponent, so the approach is around the block rather than into it. */
    private Location flank(Player foe, Location tl) {
        Location me = ctx.pos();
        double dx = tl.getX() - me.getX(), dz = tl.getZ() - me.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) return tl.clone();
        // perpendicular, on whichever side the bot is already leaning towards
        double px = -dz / len, pz = dx / len;
        double side = (me.getX() - tl.getX()) * px + (me.getZ() - tl.getZ()) * pz;
        if (side < 0) { px = -px; pz = -pz; }
        return new Location(tl.getWorld(), tl.getX() + px * 2.2, tl.getY(), tl.getZ() + pz * 2.2);
    }

    private boolean awayFromMe(Player foe, Location tl) {
        org.bukkit.util.Vector v = foe.getVelocity();
        Location me = ctx.pos();
        org.bukkit.util.Vector away = tl.toVector().subtract(me.toVector());
        away.setY(0);
        if (away.lengthSquared() < 1e-6) return false;
        org.bukkit.util.Vector flat = v.clone();
        flat.setY(0);
        return flat.dot(away.normalize()) > 0.08;
    }

    private Player nearest() {
        Player best = null;
        double bd = CHASE_RANGE * CHASE_RANGE;
        org.bukkit.World mine = ctx.player.getWorld();
        for (Player p : mine.getPlayers()) {
            if (p == ctx.player || p.isDead() || !p.isOnline()) continue;
            Location l = p.getLocation();
            if (l.getWorld() != mine) continue;      // cross-world distance throws
            double d = ctx.pos().distanceSquared(l);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }
}
