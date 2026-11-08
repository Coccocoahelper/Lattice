package net.h2020.bots.ai.brain.bedwars;

import net.h2020.api.game.GameTeam;
import net.h2020.api.game.TeamColor;
import net.h2020.bots.ai.BotAI;
import net.h2020.bots.ai.brain.BaseBrain;
import net.h2020.bots.ai.brain.BedwarsBrain;
import net.h2020.bots.ai.inv.Inv;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The team's job board.
 * <p>
 * Delegation used to be per bot: everyone scored its own options and the team ended up with four rushers and no bed
 * defence, or three bots on the same diamond generator. This class decides the <b>jobs</b> for the whole team first -
 * one defender while the bed is soft, one diamond runner, one emerald runner when the team is big enough, the rest on
 * beds - and then each bot's own utility only has to pick the details of the job it was given.
 * <p>
 * Assignments are re-made once a second and are sticky for as long as they stay valid, so a bot never has to abandon
 * a bridge because the board shuffled; a dead or cornered owner frees the job immediately.
 */
public final class TeamJobs {
    public static final int NONE = -1, DEFEND = 0, DIAMONDS = 1, EMERALDS = 2, RUSH = 3, IRON = 4, SHOP = 5;

    /** world|team -> player uuid -> job. */
    private static final Map<String, Map<java.util.UUID, Integer>> BOARD = new HashMap<String, Map<java.util.UUID, Integer>>();
    private static final Map<String, Long> BOARD_AT = new HashMap<String, Long>();
    private static long remakes;

    private TeamJobs() {}

    public static int jobOf(BedwarsBrain b) {
        if (b == null || b.color() == null || b.context() == null || b.context().player == null) return NONE;
        String key = key(b);
        long now = System.currentTimeMillis();
        Long at = BOARD_AT.get(key);
        if (at == null || now - at > 1200) remade(b, key, now);
        Map<java.util.UUID, Integer> m = BOARD.get(key);
        if (m == null) return NONE;
        Integer j = m.get(b.context().player.getUniqueId());
        return j == null ? NONE : j;
    }

    /** The score index in BedwarsBrain.decide() this job maps to, or -1. */
    public static int scoreIndex(int job) {
        switch (job) {
            case DEFEND: return 3;
            case DIAMONDS: return 5;
            case EMERALDS: return 6;
            case RUSH: return 4;
            case IRON: return 2;
            case SHOP: return 0;
            default: return -1;
        }
    }

    public static String name(int job) {
        switch (job) {
            case DEFEND: return "defend";
            case DIAMONDS: return "diamonds";
            case EMERALDS: return "emeralds";
            case RUSH: return "rush";
            case IRON: return "iron";
            case SHOP: return "shop";
            default: return "-";
        }
    }

    private static String key(BedwarsBrain b) {
        return b.context().player.getWorld().getName() + "|" + b.color();
    }

    /** Rebuild the board for this bot's team: pick the coverage the team is missing, then hand out the rest. */
    private static void remade(BedwarsBrain b, String key, long now) {
        TeamColor color = b.color();
        List<BedwarsBrain> crew = new ArrayList<BedwarsBrain>();
        for (BaseBrain bb : BotAI.brains()) {
            if (!(bb instanceof BedwarsBrain)) continue;
            BedwarsBrain m = (BedwarsBrain) bb;
            if (m.color() != color || m.context() == null || m.context().player == null) continue;
            if (m.context().player.getWorld() != b.context().player.getWorld()) continue;
            if (m.context().player.isDead()) continue;
            crew.add(m);
        }
        Map<java.util.UUID, Integer> board = new HashMap<java.util.UUID, Integer>();
        if (crew.isEmpty()) { BOARD.put(key, board); BOARD_AT.put(key, now); return; }

        boolean bedAlive = b.bedAliveNow();
        int n = crew.size();

        // keep the previous board where it is still sensible (sticky jobs, no churn mid-bridge)
        Map<java.util.UUID, Integer> prev = BOARD.get(key);
        if (prev != null) {
            for (BedwarsBrain m : crew) {
                Integer j = prev.get(m.context().player.getUniqueId());
                if (j == null) continue;
                if (j == DEFEND && !bedAlive) continue;
                // a job that needs blocks but has none is not sticky
                if ((j == RUSH || j == DIAMONDS || j == EMERALDS) && Inv.count(m.context().player, Inv.BLOCK) < 8 && m.task() == null)
                    continue;
                board.put(m.context().player.getUniqueId(), j);
            }
        }

        boolean hasDefender = board.containsValue(DEFEND);
        boolean hasDiamonds = board.containsValue(DIAMONDS);
        boolean hasEmeralds = board.containsValue(EMERALDS);
        boolean hasRusher = board.containsValue(RUSH);

        // 1. somebody must hold the bed while it is still standing and the team is more than two
        if (bedAlive && !hasDefender && n >= 2) {
            BedwarsBrain pick = weakestFree(crew, board);
            if (pick != null) board.put(pick.context().player.getUniqueId(), DEFEND);
        }
        // 2. one diamond runner and, when there are enough bots, one emerald runner (emeralds need confidence)
        if (n >= 2 && !hasDiamonds) {
            BedwarsBrain pick = strongestFree(crew, board);
            if (pick != null) board.put(pick.context().player.getUniqueId(), DIAMONDS);
        }
        if (n >= 3 && !hasEmeralds) {
            BedwarsBrain pick = strongestFree(crew, board);
            if (pick != null) board.put(pick.context().player.getUniqueId(), EMERALDS);
        }
        // 3. at least one rusher, at most two in a team of four; the leftovers stay on beds as well
        int rushers = 0;
        for (Integer j : board.values()) if (j == RUSH) rushers++;
        int wantRushers = n >= 4 ? 2 : (n >= 2 ? 1 : 1);
        for (BedwarsBrain m : crew) {
            if (rushers >= wantRushers) break;
            java.util.UUID id = m.context().player.getUniqueId();
            if (board.containsKey(id)) continue;
            board.put(id, RUSH);
            rushers++;
        }
        for (BedwarsBrain m : crew) {
            java.util.UUID id = m.context().player.getUniqueId();
            if (!board.containsKey(id)) board.put(id, RUSH);
        }
        BOARD.put(key, board);
        BOARD_AT.put(key, now);
        remakes++;
    }

    private static BedwarsBrain weakestFree(List<BedwarsBrain> crew, Map<java.util.UUID, Integer> board) {
        BedwarsBrain best = null;
        double bv = 2;
        for (BedwarsBrain m : crew) {
            if (board.containsKey(m.context().player.getUniqueId())) continue;
            double v = m.context().persona.skill.aggression;
            if (v < bv) { bv = v; best = m; }
        }
        return best;
    }

    private static BedwarsBrain strongestFree(List<BedwarsBrain> crew, Map<java.util.UUID, Integer> board) {
        BedwarsBrain best = null;
        double bv = -1;
        for (BedwarsBrain m : crew) {
            if (board.containsKey(m.context().player.getUniqueId())) continue;
            double v = m.context().persona.skill.overall;
            if (v > bv) { bv = v; best = m; }
        }
        return best;
    }

    public static String report(BedwarsBrain b) {
        return "job=" + name(jobOf(b)) + " (board remakes " + remakes + ")";
    }

    public static void clear() { BOARD.clear(); BOARD_AT.clear(); }
}
