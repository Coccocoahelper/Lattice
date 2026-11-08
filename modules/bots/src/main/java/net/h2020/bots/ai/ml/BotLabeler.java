package net.h2020.bots.ai.ml;

import net.h2020.bots.ai.BotAI;
import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.brain.BaseBrain;
import net.h2020.bots.ai.brain.BedwarsBrain;
import net.h2020.bots.ai.brain.bedwars.BwTask;
import net.h2020.bots.ai.brain.bedwars.CollectTask;
import net.h2020.bots.ai.brain.bedwars.DefendTask;
import net.h2020.bots.ai.brain.bedwars.DepositTask;
import net.h2020.bots.ai.brain.bedwars.GuardTask;
import net.h2020.bots.ai.brain.bedwars.HuntTask;
import net.h2020.bots.ai.brain.bedwars.RushTask;
import net.h2020.bots.ai.brain.bedwars.ShopTask;
import net.h2020.bots.ai.brain.bedwars.WithdrawTask;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ground truth from our own bots. A bot knows exactly what it is doing - it is the one who chose it - so every bot
 * that bridges, shops, defends, gathers or fights is a labelled example of what that action <i>looks like</i> from the
 * outside, through the same observable features the models use on people. That is the supply of examples the answer
 * questions cannot provide: people are asked rarely, bots are doing things all game.
 * <p>
 * Nothing here is oracle information about people: the samples are bots only, and the label is the bot's own task
 * state, so a model still has to recognise a human's bridging from posture and movement.
 */
public final class BotLabeler {
    private static long activitySamples, intentSamples;
    private static final Map<UUID, Long> LAST_AT = new HashMap<UUID, Long>();
    private static final Map<String, Integer> COVER = new HashMap<String, Integer>();

    private BotLabeler() {}

    public static void install(JavaPlugin p) {
        org.bukkit.Bukkit.getScheduler().runTaskTimer(p, new Runnable() {
            @Override public void run() { tick(); }
        }, 240L, 40L);                                  // every 2 s: one sample per bot, staggered
    }

    private static void tick() {
        if (!MlSettings.trainFromBots()) return;          // bot labels are opt-in, see MlSettings
        for (BaseBrain b : BotAI.brains()) {
            if (!(b instanceof BedwarsBrain)) continue;
            BotContext ctx = b.context();
            if (ctx == null || ctx.player == null || ctx.player.isDead()) continue;
            Player p = ctx.player;
            Long last = LAST_AT.get(p.getUniqueId());
            long now = System.currentTimeMillis();
            if (last != null && now - last < 1600) continue;
            LAST_AT.put(p.getUniqueId(), now);
            try { sample(ctx, (BedwarsBrain) b); } catch (Throwable ignored) { }
        }
    }

    private static void sample(BotContext ctx, BedwarsBrain brain) {
        // the observable features as they were half a second ago (the act is already under way)
        double[] x = BehaviourModel.featuresBefore(ctx.player, 500);
        if (x == null) return;
        int activity = activityOf(ctx, brain);
        BehaviourModel.learnSnapshot(x, activity);
        activitySamples++;
        bump("act." + BehaviourModel.name(activity));

        int intent = intentOf(brain);
        if (intent >= 0) {
            IntentModel.learnSnapshot(x, intent);
            intentSamples++;
            bump("int." + IntentModel.name(intent));
        }
    }

    /** The bot's own activity, straight from what it is doing. */
    private static int activityOf(BotContext ctx, BedwarsBrain brain) {
        BwTask t = brain.task();
        if (ctx.combat.engaged()) return BehaviourModel.FIGHTING;
        if (ctx.nav.bridging()) return BehaviourModel.BRIDGING;
        if (t instanceof DefendTask) return BehaviourModel.PLACING;
        if (t instanceof ShopTask) return BehaviourModel.SHOPPING;
        if (t instanceof DepositTask || t instanceof WithdrawTask) return BehaviourModel.SHOPPING;
        if (t instanceof CollectTask) return BehaviourModel.GATHERING;
        if (ctx.nav.active()) return BehaviourModel.TRAVELLING;
        return BehaviourModel.IDLE;
    }

    /** The bot's own intent, from the task it chose. */
    private static int intentOf(BedwarsBrain brain) {
        BwTask t = brain.task();
        if (t instanceof RushTask) return IntentModel.RUSH;
        if (t instanceof DefendTask || t instanceof GuardTask) return IntentModel.DEFEND;
        if (t instanceof HuntTask) return IntentModel.HUNT;
        if (t instanceof DepositTask) return IntentModel.DEPOSIT;
        if (t instanceof ShopTask) return ((ShopTask) t).name().equals("upgrades") ? IntentModel.UPGRADE : IntentModel.SHOP;
        if (t instanceof CollectTask) {
            CollectTask c = (CollectTask) t;
            String n = c.name();
            if (n.endsWith("diamond")) return IntentModel.DIAMONDS;
            if (n.endsWith("emerald")) return IntentModel.EMERALDS;
            return IntentModel.IRON;
        }
        return -1;
    }

    private static void bump(String key) {
        Integer v = COVER.get(key);
        COVER.put(key, v == null ? 1 : v + 1);
    }

    public static List<String> report() {
        List<String> out = new ArrayList<String>();
        out.add(String.format("§6[ml] §ebot labels: §f%d§7 activity, §f%d§7 intent samples (bots labelling themselves)",
                activitySamples, intentSamples));
        StringBuilder sb = new StringBuilder("§7what the bots have demonstrated:");
        for (Map.Entry<String, Integer> e : COVER.entrySet()) sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
        if (COVER.size() > 0) out.add(sb.toString());
        return out;
    }

    public static void reset() { activitySamples = 0; intentSamples = 0; COVER.clear(); LAST_AT.clear(); }
}
