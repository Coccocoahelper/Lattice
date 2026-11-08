package net.h2020.bots.ai.brain.bedwars;

import net.h2020.bots.ai.brain.BedwarsBrain;
import net.h2020.bots.ai.nav.NavOptions;
import net.h2020.bots.ai.perception.Sighting;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Hunting a team whose bed is gone: there is nothing left to break, so the win is in the players. The bot walks the
 * last known position of the target (or their base when it has no idea), fights what it finds, and gives up after a
 * while so it does not spend the rest of the game chasing somebody across the map.
 * <p>
 * A team with one player left is the priority: that is a final kill, i.e. a team out of the game.
 */
public final class HuntTask extends BwTask {
    private Player target;
    private long lastSeen;
    private final long lifeTicks;

    public HuntTask(BedwarsBrain brain, Player target) {
        super(brain);
        this.target = target;
        this.lastSeen = ctx.tick;
        this.lifeTicks = 20L * 40;
    }

    @Override public String name() { return "hunt"; }

    public Player target() { return target; }

    @Override public org.bukkit.Location goal() { return target == null ? null : target.getLocation(); }

    @Override
    public boolean tick() {
        if (age() > lifeTicks) return false;
        if (target == null || !target.isOnline() || target.isDead() || target.getWorld() != ctx.player.getWorld()) {
            // that one is gone: pick the next player of the same team, or give up
            Player next = brain.nextHuntTarget(target);
            if (next == null) return false;
            target = next;
            lastSeen = ctx.tick;
            return true;
        }
        Sighting s = ctx.perception.get(target);
        Location goal;
        if (s != null && s.lastPos != null) {
            goal = s.estimate(ctx.tick);
            if (ctx.tick - s.lastSeenTick < 40) lastSeen = ctx.tick;
        } else {
            goal = brain.baseOf(target);
        }
        if (goal == null) return false;
        // if we have lost it for a while, sweep its base instead of standing on the old spot
        if (ctx.tick - lastSeen > 20 * 6) goal = brain.baseOf(target);
        double d = ctx.pos().distance(goal);
        if (d < 3.5) {
            // at the spot: look around for it, the fight itself is Combat's job
            ctx.nav.stop();
            ctx.motor.aimPoint(net.h2020.bots.ai.motor.Motor.P_LOOK, goal.clone().add(0, 1.2, 0),
                    net.h2020.api.bot.AimIntent.GLANCE, 2.0);
            return true;
        }
        NavOptions o = NavOptions.bridging(brain.blocks());
        o.maxNodes = 12000;
        o.breakBlocks = true;
        o.breakable = RushTask.DEFENSE;
        o.avoidEnemies = false;
        ctx.nav.goTo(goal, 2.5, o);
        ctx.nav.tick();
        return !ctx.nav.failed();
    }
}
