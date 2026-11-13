package net.h2020.practice;

import net.h2020.practice.drill.BowDrill;
import net.h2020.practice.drill.BridgeDrill;
import net.h2020.practice.drill.Drill;
import net.h2020.practice.drill.FireballDrill;
import net.h2020.practice.drill.GameSenseDrill;

/**
 * One Bed Wars mechanic to practise. Each drill owns its arena, its kit and its scoring; the game only drives it.
 *
 * <p>The same drills run for bots, which is the point of the mode being playable at all: a bot dropped into the bow
 * drill is being measured on the same targets a player is, and the numbers are directly comparable.
 */
public final class Drills {

    private Drills() { }

    public static Drill byId(String id) {
        if (id == null) return null;
        String k = id.toLowerCase(java.util.Locale.ROOT);
        if (k.equals("fireball") || k.equals("fireballs") || k.equals("deflect")) return new FireballDrill();
        if (k.equals("bow") || k.equals("aim") || k.equals("archery")) return new BowDrill();
        if (k.equals("gamesense") || k.equals("sense") || k.equals("scenarios")) return new GameSenseDrill();
        if (k.equals("bridge") || k.equals("bridging")) return new BridgeDrill();
        return null;
    }

    public static String[] ids() { return new String[]{"fireball", "bow", "gamesense", "bridge"}; }
}
