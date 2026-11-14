package net.h2020.bots.ai.brain.bedwars;

import net.h2020.api.game.TeamColor;
import net.h2020.bots.ai.perception.PlayerStats;
import net.h2020.bots.ai.perception.Sighting;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

/**
 * What the team knows about the other teams: how good they look, and what they have been seen picking up. Every bot
 * feeds it from what its own client sees (armour, weapon in hand, click speed, someone picking emeralds or diamonds up
 * inside its view), and the brain uses it to decide which base to go for next.
 * <p>
 * It is a belief, not the server's truth: a team that has been out of sight keeps its last estimate, and one that has
 * been seen wearing diamond armour is treated as a real threat until proven otherwise.
 */
public final class ScoutModel {
    private static final double SKILL_ALPHA = 0.25;

    public static final class Belief {
        public double skill = 0.5;                 // 0..1 perceived team skill
        public int emeraldsSeen, diamondsSeen;
        public boolean diamondArmour;
        public boolean bow;
        public long lastUpdate;
    }

    private static final Map<String, Belief> MAP = new HashMap<String, Belief>();

    private ScoutModel() {}

    private static String key(World w, TeamColor t) {
        return (w == null ? "?" : w.getName()) + "|" + (t == null ? "?" : t.name());
    }

    public static Belief of(World w, TeamColor t) {
        String k = key(w, t);
        Belief b = MAP.get(k);
        if (b == null) { b = new Belief(); MAP.put(k, b); }
        return b;
    }

    /** Fold one visible enemy into the team's estimate. */
    public static void observe(World w, TeamColor team, Player enemy, Sighting s, long tick) {
        if (team == null || enemy == null) return;
        Belief b = of(w, team);
        double armour = s == null ? 0 : s.armorSeen;
        double weapon = s == null ? 1 : s.weaponSeen;
        double cps = PlayerStats.cps(enemy);
        double sample = 0.15 + 0.35 * clamp01(armour / 9.0) + 0.3 * clamp01((weapon - 1) / 7.0) + 0.2 * clamp01((cps - 4) / 10.0);
        b.skill = b.skill * (1 - SKILL_ALPHA) + sample * SKILL_ALPHA;
        if (armour >= 7) b.diamondArmour = true;
        if (s != null && s.bowSeen) b.bow = true;
        b.lastUpdate = tick;
    }

    /** Somebody on that team was seen holding / picking up something that matters. */
    public static void sawResource(World w, TeamColor team, Material m) {
        if (team == null || m == null) return;
        Belief b = of(w, team);
        switch (m) {
            case EMERALD: b.emeraldsSeen++; break;
            case DIAMOND: b.diamondsSeen++; break;
            case DIAMOND_CHESTPLATE: case DIAMOND_LEGGINGS: case DIAMOND_BOOTS: case DIAMOND_HELMET:
            case DIAMOND_SWORD: b.diamondArmour = true; break;
            default: break;
        }
    }

    /** How much of a problem this team is expected to be (0..~2). */
    public static double threat(World w, TeamColor t) {
        Belief b = of(w, t);
        double strong = b.skill;
        if (b.diamondArmour) strong += 0.35;
        if (b.bow) strong += 0.1;
        strong += Math.min(0.3, (b.emeraldsSeen + b.diamondsSeen * 0.5) * 0.05);
        return strong;
    }

    /**
     * Score for "which base next": close bases are cheap, teams that look strong are less attractive, and a team whose
     * bed is already broken is worth much less (bed first, then final kills).
     */
    public static double targetScore(World w, TeamColor t, double distance, boolean bedAlive, boolean hasPlayers,
                                     double defenseIntegrity) {
        return targetScore(w, t, distance, bedAlive, hasPlayers, defenseIntegrity, 0, 1);
    }

    /**
     * The same, with what a scout has actually seen: how many of that team are standing on their bed, and how many of
     * them are still alive.
     *
     * <p>An empty base is the thing to punish. Real players read "both of them went mid" and take the bed in the time
     * it takes them to come back; a bot that only knows the bed is 40 blocks away goes to whichever is nearest and
     * walks into two defenders. A team down to one player is also cheaper than a team of three at the same distance,
     * and that is public knowledge from the tab list.
     */
    public static double targetScore(World w, TeamColor t, double distance, boolean bedAlive, boolean hasPlayers,
                                     double defenseIntegrity, int defendersHome, int aliveCount) {
        double s = distance;
        s *= 1.0 - 0.3 * Math.min(1.0, threat(w, t));       // a strong team at the same distance is less attractive
        if (!bedAlive) s += 60;
        if (!hasPlayers) s += 500;
        if (defenseIntegrity > 0.95) s += 10;                // a full defence is another trip's work
        s += 9.0 * Math.max(0, defendersHome);               // somebody is home: this is a fight, not a walk-in
        s += 6.0 * Math.max(0, aliveCount - 1);              // a team at full strength is not a free bed
        if (aliveCount <= 1) s -= 8.0;                       // last man standing: the bed is usually undefended
        return s;
    }

    /** One line per team belief, for /bot ml. */
    public static java.util.List<String> report() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (java.util.Iterator<Map.Entry<String, Belief>> it = MAP.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Belief> e = it.next();
            Belief b = e.getValue();
            String[] parts = e.getKey().split("\\|");
            // a world that is gone was a finished game: forget everything the team believed about it
            if (parts.length > 0 && org.bukkit.Bukkit.getWorld(parts[0]) == null) { it.remove(); continue; }
            if (b.lastUpdate == 0) continue;
            out.add(String.format("§6[ml] §escouts §7%s/%s: skill §f%.2f§7, emeralds seen §f%d§7, diamonds §f%d§7%s",
                    parts.length > 0 ? parts[0] : "?", parts.length > 1 ? parts[1] : "?",
                    b.skill, b.emeraldsSeen, b.diamondsSeen, b.diamondArmour ? " §b(diamond gear)" : ""));
        }
        return out;
    }

    public static void clear(World w) {
        String prefix = (w == null ? "?" : w.getName()) + "|";
        MAP.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public static void clearAll() { MAP.clear(); }

    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
}
