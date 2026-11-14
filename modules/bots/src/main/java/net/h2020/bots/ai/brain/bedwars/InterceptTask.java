package net.h2020.bots.ai.brain.bedwars;

import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.brain.BedwarsBrain;
import net.h2020.bots.ai.nav.NavOptions;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Go and meet the player who is coming for the bed.
 *
 * <p>The bed-alert used to end in a guard: stand near the bed and wait. That loses to anybody who is willing to fight,
 * because they arrive on their terms. This walks at them instead - the earlier the better, since an enemy half way
 * across the void is the easiest one to knock off - and hands over to the combat layer inside reach.
 */
public final class InterceptTask extends BwTask {

    private static final double REACH = 4.6;
    private static final long TIMEOUT = 20 * 20;

    private final Player intruder;

    public InterceptTask(BedwarsBrain brain, Player intruder) {
        super(brain);
        this.intruder = intruder;
    }

    @Override public String name() { return "intercept"; }

    @Override
    public Location goal() { return intruder == null || intruder.isDead() ? null : intruder.getLocation(); }

    @Override
    public boolean tick() {
        if (intruder == null || intruder.isDead() || !intruder.isOnline()) {
            CombatStats.inc("incursion.done.gone");
            return false;
        }
        if (!brain.view().bedAlive(brain.color())) {                    // the bed is already gone: this is a fight now, not a defence
            CombatStats.inc("incursion.done.bedgone");
            return false;
        }
        if (ctx.tick - started > TIMEOUT) { CombatStats.inc("incursion.done.timeout"); return false; }

        Location me = ctx.pos();
        Location them = intruder.getLocation();
        if (me.getWorld() != them.getWorld()) return false;
        double d = me.distance(them);

        if (d <= REACH) {
            // inside reach: the combat layer takes over, this task just keeps the bot pointed at them
            if (ctx.combat.target() != intruder) {
                ctx.combat.engage(intruder);
                CombatStats.inc("incursion.engage");
            }
            ctx.nav.stop();
            return true;
        }
        // still closing: walk at them, bridging if that is what it takes (they are over the void as often as not)
        NavOptions o = NavOptions.bridging(brain.blocks());
        o.maxNodes = 8000;
        o.avoidEnemies = false;
        ctx.nav.goTo(them, 2.5, o);
        ctx.nav.tick();
        return true;
    }

    @Override
    public void abort() {
        super.abort();
    }

    public Player intruder() { return intruder; }
}
