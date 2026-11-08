package net.h2020.bots.ai.stats;

import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.brain.BaseBrain;
import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.BotAI;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Why bots die, split by cause. A single "fall.died" counter hides the difference between being knocked off a bridge
 * (the fight was lost), walking off one (the movement was wrong) and failing a clutch (the save was attempted and
 * missed) - and those three want opposite fixes.
 */
public final class DeathStats {
    public static final int KNOCKED = 0, BRIDGE_FALL = 1, WALK_FALL = 2, CLUTCH_FAIL = 3, MELEE = 4, OTHER = 5,
            VOID_UNKNOWN = 6, BLOCKS_NO_SAVE = 7;
    public static final String[] NAMES = {"knocked off", "fell off a bridge", "walked into the void",
            "clutch tried and failed", "killed in a fight", "other", "fell (unknown)",
            "fell holding blocks, no save tried"};

    private static final long[] COUNT = new long[NAMES.length];
    private static final Map<String, Integer> RECENT = new LinkedHashMap<String, Integer>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Integer> e) { return size() > 12; }
    };

    private DeathStats() {}

    /** Called from the death listener, which is the only place that knows whether it was a void death. */
    public static void record(Player victim, boolean voided, Player killer) {
        if (victim == null) return;
        int cause = classify(victim, voided, killer);
        COUNT[cause]++;
        CombatStats.inc("death." + key(cause));
        String where = String.format(Locale.ROOT, "%.0f,%.0f,%.0f", victim.getLocation().getX(),
                victim.getLocation().getY(), victim.getLocation().getZ());
        RECENT.put(victim.getName() + " " + key(cause) + " @(" + where + ")", cause);
    }

    private static int classify(Player victim, boolean voided, Player killer) {
        if (!voided) return killer == null ? OTHER : MELEE;
        BaseBrain brain = BotAI.brainOf(victim);
        BotContext ctx = brain == null ? null : brain.context();
        if (ctx == null) return VOID_UNKNOWN;              // a human: we know it fell, not why
        long sinceHurt = ctx.tick - ctx.combat.lastHurtTick();
        if (ctx.combat.engaged() || sinceHurt <= 60) return KNOCKED;   // hit a moment before the fall
        // bridging recently, even if the bridge had already ended when the drop started. This is checked before the
        // clutch because a bot that walks off its own bridge also tries to catch itself on the way down
        if (ctx.nav.bridging() || ctx.tick - ctx.nav.lastBridgeTick() <= 40) return BRIDGE_FALL;
        // the clutch actually ran and did not catch: a failed save, not a missed one
        if (ctx.tick - ctx.combat.lastClutchTick() <= 80) return CLUTCH_FAIL;
        // blocks in hand and nothing tried: the save never fired at all - a behaviour hole, not a missed timing
        if (Inv.count(ctx.player, Inv.BLOCK) >= 2) return BLOCKS_NO_SAVE;
        return WALK_FALL;
    }

    private static String key(int cause) {
        switch (cause) {
            case KNOCKED: return "knocked";
            case BRIDGE_FALL: return "bridge";
            case WALK_FALL: return "walk";
            case CLUTCH_FAIL: return "clutch";
            case MELEE: return "melee";
            case VOID_UNKNOWN: return "void?";
            case BLOCKS_NO_SAVE: return "nosave";
            default: return "other";
        }
    }

    public static List<String> report() {
        List<String> out = new ArrayList<String>();
        long total = 0;
        for (long c : COUNT) total += c;
        out.add("§6[deaths] §f" + total + "§7 deaths since start");
        for (int i = 0; i < NAMES.length; i++) {
            if (COUNT[i] == 0) continue;
            out.add(String.format(Locale.ROOT, "§7%s: §f%d§7 (%.0f%%)", NAMES[i], COUNT[i],
                    100.0 * COUNT[i] / Math.max(1, total)));
        }
        if (!RECENT.isEmpty()) {
            out.add("§7last: §f" + String.join("§7, §f", RECENT.keySet()));
        }
        return out;
    }

    public static void reset() { for (int i = 0; i < COUNT.length; i++) COUNT[i] = 0; RECENT.clear(); }
}
