package net.h2020.bots.ai.ml;

import net.h2020.bots.ai.BotAI;
import net.h2020.bots.ai.perception.PlayerStats;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fights that involve a real player, used to train the same models the bots train from their own fights: an
 * engagement is sampled when somebody hits somebody (from the attacker's point of view), and settled when one of them
 * dies - the attacker's sample is a win, a fresh one from the victim's side is a loss. Human play is the best data
 * there is for "who wins this fight", so the bots learn from the people they are supposed to imitate.
 * <p>
 * Only fights where at least one side is not one of our bots are recorded here; bot-vs-bot fights are already fed by
 * their own brains and would be counted twice.
 */
public final class HumanFights {
    private static final long EXPIRE = 20 * 20;

    private static final class Sample {
        final Player attacker, victim;
        final double[] x;
        final long tick;
        Sample(Player a, Player v, double[] x, long tick) { this.attacker = a; this.victim = v; this.x = x; this.tick = tick; }
    }

    private static final Map<UUID, Sample> OPEN = new HashMap<UUID, Sample>();
    private static long tick;
    private static long learnt;

    private HumanFights() {}

    public static void tick() {
        tick++;
        if ((tick & 63) == 0) {
            for (Iterator<Map.Entry<UUID, Sample>> it = OPEN.entrySet().iterator(); it.hasNext(); ) {
                if (tick - it.next().getValue().tick > EXPIRE) it.remove();
            }
        }
    }

    /** Somebody hit somebody. Called from the damage listener for every game hit. */
    public static void engaged(Player attacker, Player victim, double distance) {
        if (attacker == null || victim == null || attacker == victim) return;
        boolean human = !BotAI.isBotPlayer(attacker) || !BotAI.isBotPlayer(victim);
        if (!human) return;                                    // bot fights train through their own brains
        double[] x = FightModel.featuresRaw(attacker, victim, distance);
        OPEN.put(attacker.getUniqueId(), new Sample(attacker, victim, x, tick));
    }

    /** Somebody died: settle every engagement that pointed at him. */
    public static void resolved(Player victim) {
        if (victim == null) return;
        for (Iterator<Map.Entry<UUID, Sample>> it = OPEN.entrySet().iterator(); it.hasNext(); ) {
            Sample s = it.next().getValue();
            if (s.victim != victim) continue;
            it.remove();
            if (!s.attacker.isOnline() || s.attacker == victim) continue;
            FightModel.learn(s.x, true);
            // the victim is usually already teleported into another world by the time the death fires: never measure
            // across worlds, that throws and takes the whole death handler with it
            double gap = 3.0;
            try {
                if (victim.getWorld() == s.attacker.getWorld())
                    gap = Math.max(1.0, victim.getLocation().distance(s.attacker.getLocation()));
            } catch (Throwable ignored) { }
            double[] loser = FightModel.featuresRaw(victim, s.attacker, gap);
            FightModel.learn(loser, false);
            SelfStats.onFight(s.attacker, true);
            SelfStats.onFight(victim, false);
            learnt += 2;
            net.h2020.api.train.Replay.log("human.fight", "\"x\":" + net.h2020.api.train.Replay.vec(s.x)
                    + ",\"y\":1");
        }
    }

    public static List<String> report() {
        List<String> out = new ArrayList<String>();
        out.add(String.format("§6[ml] §ehuman fights: §f%d§7 samples learned from real players, §f%d§7 open now",
                learnt, OPEN.size()));
        return out;
    }

    public static void clear() { OPEN.clear(); learnt = 0; }
}
