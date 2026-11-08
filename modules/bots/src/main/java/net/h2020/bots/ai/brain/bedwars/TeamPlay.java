package net.h2020.bots.ai.brain.bedwars;

import net.h2020.api.game.TeamColor;
import net.h2020.bots.ai.BotAI;
import net.h2020.bots.ai.brain.BaseBrain;
import net.h2020.bots.ai.brain.BedwarsBrain;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What the bots know about each other. Two things a real team does that a bag of independent bots does not:
 * <ul>
 * <li><b>drafting</b>: only one of them bridges. A bot heading for a bed with a teammate already ahead of it on the
 *   same route walks behind their bridge instead of building a second one - the teammate bridged faster, so the
 *   second bot follows the line (and is not caught mid-block when a fight starts).</li>
 * <li><b>hand-off</b>: two bots on one enemy with the fight already won by the model do not both stand there. The
 *   weaker one peels off and rushes that enemy's bed while the other keeps fighting, so the pressure does not go
 *   away - the reason teams win the 2v1 in the first place.</li>
 * </ul>
 * All of it is coordination the server can legitimately know: teammates share a team, and their tasks are visible to
 * each other the way a player can see who is bridging in front of him.
 */
public final class TeamPlay {
    /** enemy -> {peeler uuid, deadline ms}: one hand-off per enemy at a time. */
    private static final Map<UUID, Object[]> CLAIMS = new HashMap<UUID, Object[]>();
    /** world|team|bed -> {leader uuid, deadline ms}: exactly one bridger per bridge, kept for a while. */
    private static final Map<String, Object[]> BRIDGERS = new HashMap<String, Object[]>();

    /** Who bridges on this push. */
    public enum Role { LEAD, FOLLOW, SOLO }

    public static final class Assignment {
        public final Role role;
        public final Player leader;
        Assignment(Role role, Player leader) { this.role = role; this.leader = leader; }
    }

    /**
     * The bridging decision, made once and honoured: one bot per <b>target</b> leads, everybody heading to the same
     * place and close to it follows. The target is whatever the task's goal is - a bed, a diamond generator, the
     * emeralds at mid, an island - so a team pushing mid has one bridger for mid and another for the bed if they are
     * going different ways. The leader is the one closest to the goal, ties broken by name so two bots never both
     * think they are it; the choice is sticky for 20 s so a respawn or a shuffle cannot hand the role back and forth
     * in the middle of a bridge.
     */
    public static Assignment bridging(BedwarsBrain brain, Location goal) {
        if (brain == null || goal == null || brain.color() == null) return new Assignment(Role.SOLO, null);
        Player me = brain.context().player;
        java.util.List<Player> crewPlayers = new java.util.ArrayList<Player>();
        crewPlayers.add(me);
        for (Player p : brain.teammates()) {
            if (p == me || p.isDead() || p.getWorld() != me.getWorld()) continue;
            BaseBrain b = BotAI.brainOf(p);
            if (!(b instanceof BedwarsBrain) || b.context() == null) continue;
            BedwarsBrain mate = (BedwarsBrain) b;
            BwTask t = mate.task();
            Location mg = t == null ? null : t.goal();
            // same destination, within a few blocks: that is the same bridge
            if (mg == null || mg.getWorld() != goal.getWorld() || mg.distance(goal) > 6) continue;
            crewPlayers.add(p);
        }
        if (crewPlayers.size() < 2) return new Assignment(Role.SOLO, null);

        String key = goal.getWorld().getName() + "|" + (goal.getBlockX() >> 2) + "," + (goal.getBlockY() >> 2) + ","
                + (goal.getBlockZ() >> 2);
        long now = System.currentTimeMillis();
        Player leader = null;
        Object[] sticky = BRIDGERS.get(key);
        if (sticky != null && (Long) sticky[1] > now) {
            Player p = org.bukkit.Bukkit.getPlayer((UUID) sticky[0]);
            if (p != null && !p.isDead() && p.getWorld() == me.getWorld() && p.getLocation().distance(goal) < 48) leader = p;
        }
        if (leader == null) {
            double best = Double.MAX_VALUE;
            for (Player p : crewPlayers) {
                double d = p.getLocation().distance(goal);
                if (d < best - 0.01 || (Math.abs(d - best) <= 0.01 && leader != null && p.getName().compareTo(leader.getName()) < 0)) {
                    best = d;
                    leader = p;
                }
            }
            if (leader != null) BRIDGERS.put(key, new Object[]{leader.getUniqueId(), now + 20000});
        }
        if (leader == null) return new Assignment(Role.SOLO, null);
        if (leader == me) return new Assignment(Role.LEAD, leader);
        double gap = me.getLocation().distance(leader.getLocation());
        boolean ahead = leader.getLocation().distance(goal) < me.getLocation().distance(goal);
        if (gap < 18 && ahead) return new Assignment(Role.FOLLOW, leader);
        return new Assignment(Role.SOLO, leader);
    }

    /** The same decision for a bed (rush targets). */
    public static Assignment bridging(BedwarsBrain brain, TeamColor bed) {
        return bridging(brain, brain == null || bed == null ? null : brain.view().bedLocation(bed));
    }

    /** Forget a bridge's leader (bed gone, game over). */
    public static void clearBridgers() { BRIDGERS.clear(); }

    private TeamPlay() {}

    /** A teammate ahead of us on the same bridge, currently building it (or just ahead on it). */
    public static Player bridgeLeader(BedwarsBrain brain, TeamColor target) {
        if (brain == null || target == null || brain.color() == null) return null;
        Location me = brain.context().pos();
        Location bed = brain.view().bedLocation(target);
        if (bed == null || bed.getWorld() != me.getWorld()) return null;
        double mine = me.distance(bed);
        Player best = null;
        double bd = 15;
        for (Player p : brain.teammates()) {
            if (p == brain.context().player || p.isDead() || p.getWorld() != me.getWorld()) continue;
            BaseBrain b = BotAI.brainOf(p);
            if (!(b instanceof BedwarsBrain) || b.context() == null) continue;
            BedwarsBrain mate = (BedwarsBrain) b;
            if (!(mate.task() instanceof RushTask)) continue;
            RushTask rt = (RushTask) mate.task();
            if (rt.target != target) continue;                       // not the same bed
            double theirs = p.getLocation().distance(bed);
            if (theirs >= mine - 2) continue;                         // not actually ahead of us
            double d = me.distance(p.getLocation());
            if (d < 1.6 || d > bd) continue;
            boolean building = mate.context().nav.bridging() || mate.context().nav.doingAction();
            // either he is laying blocks right now, or he is a good way ahead on the line already
            if (!building && theirs > mine - 5) continue;
            bd = d;
            best = p;
        }
        return best;
    }

    /** The bed a teammate is rushing right now (null when nobody is). */
    public static TeamColor rushTargetOfTeam(BedwarsBrain brain) {
        if (brain == null) return null;
        for (Player p : brain.teammates()) {
            if (p == brain.context().player) continue;
            BaseBrain b = BotAI.brainOf(p);
            if (!(b instanceof BedwarsBrain) || b.context() == null) continue;
            BedwarsBrain mate = (BedwarsBrain) b;
            if (b.context().player.isDead()) continue;
            if (!(mate.task() instanceof RushTask)) continue;
            TeamColor t = ((RushTask) mate.task()).target;
            if (t != null && brain.view().bedAlive(t)) return t;
        }
        return null;
    }

    /** How many of our team's bots are currently fighting this enemy (not counting ourselves). */
    public static int teammatesFighting(BedwarsBrain brain, Player enemy) {
        int n = 0;
        if (brain == null || enemy == null) return 0;
        for (Player p : brain.teammates()) {
            if (p == brain.context().player) continue;
            BaseBrain b = BotAI.brainOf(p);
            if (!(b instanceof BedwarsBrain) || b.context() == null) continue;
            if (b.context().player.isDead()) continue;
            if (b.context().combat.target() == enemy) n++;
        }
        return n;
    }

    /**
     * The hand-off rule: a teammate is on the same enemy, the learned model says the fight is won, and this bot is
     * the weaker of the two - so it leaves the fight to the teammate and goes for that enemy's bed. Returns the team
     * whose bed to rush, or null when the bot should stay in the fight.
     */
    public static TeamColor peelOff(BedwarsBrain brain, Player enemy, double pWin, int blocks) {
        if (brain == null || enemy == null || blocks < 4) return null;
        if (teammatesFighting(brain, enemy) < 1) return null;
        if (pWin < 0.62) return null;                       // not a fight we are sure to win without him
        TeamColor enemyTeam = brain.teamColorOf(enemy);
        if (enemyTeam == null || !brain.view().bedAlive(enemyTeam)) return null;   // nothing to trade for
        Player mate = weakestPartner(brain, enemy);
        if (mate == null) return null;
        // the healthier fighter stays: he is the one who finishes the kill cleanly
        if (brain.context().health() > mate.getHealth() + 0.5) return null;
        if (brain.context().health() == mate.getHealth()
                && brain.context().player.getName().compareTo(mate.getName()) < 0) return null;  // tie-break, one side only
        if (!claim(enemy, brain.context().player)) return null;
        return enemyTeam;
    }

    private static Player weakestPartner(BedwarsBrain brain, Player enemy) {
        Player best = null;
        for (Player p : brain.teammates()) {
            if (p == brain.context().player) continue;
            BaseBrain b = BotAI.brainOf(p);
            if (!(b instanceof BedwarsBrain) || b.context() == null) continue;
            if (b.context().player.isDead()) continue;
            if (b.context().combat.target() != enemy) continue;
            if (best == null || p.getHealth() < best.getHealth()) best = p;
        }
        return best;
    }

    /** Claim the hand-off for this enemy so the other bot does not do the same thing. */
    private static boolean claim(Player enemy, Player peeler) {
        long now = System.currentTimeMillis();
        Object[] c = CLAIMS.get(enemy.getUniqueId());
        if (c != null) {
            long until = (Long) c[1];
            if (until > now && !peeler.getUniqueId().equals(c[0])) return false;
        }
        CLAIMS.put(enemy.getUniqueId(), new Object[]{peeler.getUniqueId(), now + 10000});
        if (CLAIMS.size() > 64) {
            for (java.util.Iterator<Map.Entry<UUID, Object[]>> it = CLAIMS.entrySet().iterator(); it.hasNext(); ) {
                if ((Long) it.next().getValue()[1] < now) it.remove();
            }
        }
        return true;
    }

    public static void clear() { CLAIMS.clear(); }
}
