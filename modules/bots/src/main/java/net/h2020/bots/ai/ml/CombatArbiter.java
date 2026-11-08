package net.h2020.bots.ai.ml;

import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.BotContext;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The hand-written fight mechanics, kept as the base, with an ML model that takes over once it has seen enough.
 * <p>
 * Two decisions in {@code Combat} were pure persona heuristics: whether to W-tap after a hit, and whether to hit and
 * run against better gear. Both become a value model: {@code P(this decision turns out well | state, did it)} is
 * learned from every exchange, including the ones where the hand-written rule made the call.
 * <p>
 * That shadow phase matters: before the model is trusted it only watches. The bot keeps playing the old rule, the
 * outcome is still scored, and the model learns which option pays in which situation. Once a decision has
 * {@link #MIN_SAMPLES} observations and the two options differ by more than {@link #MARGIN}, the model is allowed to
 * overrule the heuristic - and even then only some of the time, proportional to its confidence, so a fresh model
 * changes nothing and a trained one owns the decision.
 */
public final class CombatArbiter {
    public static final int WTAP = 0, HITRUN = 1, STRAFE = 2, BLOCKHIT = 3, JUMPCRIT = 4;
    private static final int KINDS = 5;
    public static final int N = 13;
    public static final int MIN_SAMPLES = 60;
    private static final double MARGIN = 0.06;

    private static final double[] W = new double[KINDS * N];
    private static final double[] SAMPLES = new double[KINDS];
    private static final double[] ASKS = new double[KINDS];
    private static final double[] SKIPS = new double[KINDS];
    private static long overrides, asks, observes;
    private static File file;
    private static boolean dirty;

    /** A decision waiting to be scored, with the action that was actually taken. */
    private static final class Pending {
        final int kind; final double[] x; final boolean action; final double prop;
        Pending(int k, double[] x, boolean a, double p) { this.kind = k; this.x = x; this.action = a; this.prop = p; }
    }
    /** Pending decisions, keyed by player and kind: a single hit opens a window for both mechanics. */
    private static final java.util.Map<String, Pending> PENDING = new java.util.HashMap<String, Pending>();

    private static String key(BotContext ctx, int kind) { return ctx.player.getUniqueId() + "|" + kind; }

    private CombatArbiter() {}

    public static void install(JavaPlugin p) {
        if (p == null) return;
        file = new File(p.getDataFolder(), "combat-arbiter.yml");
        load();
        org.bukkit.Bukkit.getScheduler().runTaskTimer(p, new Runnable() {
            @Override public void run() { if (dirty) save(); }
        }, 2400L, 2400L);
    }

    /** The state a mechanic is decided in (the action itself is appended by the model). */
    public static double[] features(BotContext ctx, double d, double pWin, int hpDiff, int gearDiff,
                                    boolean edgeBehind, boolean cornered, int combo) {
        double[] x = new double[N];
        x[0] = 1;
        x[1] = Math.max(0, ctx.health()) / 20.0;
        x[2] = Math.min(1, d / 4.5);
        x[3] = Math.max(-1, Math.min(1, hpDiff / 20.0));
        x[4] = Math.max(-1, Math.min(1, gearDiff / 6.0));
        x[5] = pWin;
        x[6] = edgeBehind ? 1 : 0;
        x[7] = cornered ? 1 : 0;
        x[8] = Math.min(1, combo / 4.0);
        x[9] = Math.min(1, ctx.world().voidSides((int) Math.floor(ctx.pos().getX()),
                (int) Math.floor(ctx.pos().getY() + 0.01), (int) Math.floor(ctx.pos().getZ())) / 4.0);
        x[10] = ctx.player != null && ctx.player.isOnGround() ? 1 : 0;
        x[11] = Math.min(1, net.h2020.bots.ai.inv.Inv.count(ctx.player, net.h2020.bots.ai.inv.Inv.BLOCK) / 64.0);
        x[12] = Math.min(1, net.h2020.bots.ai.inv.Inv.bestMeleeValue(ctx.player) / 8.0);
        return x;
    }

    /**
     * What the model wants for this decision (null = keep the hand-written rule). Every call is also recorded for
     * learning, so the model keeps improving whether or not it is the one deciding.
     */
    public static Boolean decide(int kind, BotContext ctx, double[] x, boolean handWritten) {
        if (x == null) return null;
        asks++;
        ASKS[kind]++;
        boolean confident = SAMPLES[kind] >= MIN_SAMPLES;
        if (!confident) {
            observe(ctx, kind, x, handWritten);                 // shadow mode: watch the old rule's outcomes
            return null;
        }
        double pYes = value(kind, x, true), pNo = value(kind, x, false);
        double conf = confidence(kind);
        if (Math.abs(pYes - pNo) < MARGIN * (1.6 - conf)) {     // options too close: not worth overriding
            observe(ctx, kind, x, handWritten);
            return null;
        }
        boolean best = pYes > pNo;
        if (best == handWritten) { observe(ctx, kind, x, handWritten); return null; }
        if (ctx.rng.nextDouble() > conf * 0.75 + 0.25) {        // partial trust: sometimes still the old rule
            observe(ctx, kind, x, handWritten);
            return null;
        }
        overrides++;
        double pAct = conf * 0.75 + 0.25;                 // the behaviour policy mixed in the hand-written rule
        PENDING.put(key(ctx, kind), new Pending(kind, x.clone(), true, pAct));
        return true;
    }

    private static void observe(BotContext ctx, int kind, double[] x, boolean action) {
        observes++;
        double pAct = action ? 0.6 : 0.4;
        PENDING.put(key(ctx, kind), new Pending(kind, x.clone(), action, pAct));
    }

    /** Resolve only one mechanic (the others keep their own windows). */
    /**
     * A section of a fight, graded with the same rules the stance bandit uses: a combo is worth more than the hits
     * alone, and taking hits with nothing landed back is a loss even if the raw exchange was even.
     */
    public static void resolveWindow(BotContext ctx, int kind, int landedDelta, int takenDelta, int combo, int unanswered) {
        resolveWindow(ctx, kind, landedDelta, takenDelta, combo, unanswered, 1.0);
    }

    /**
     * @param weight evidence weight: a good exchange against somebody the fight model expects to lose to is worth
     *               more than the same exchange against a weaker opponent, so mechanics are learned about players,
     *               not about the average of whoever happened to be in front of us.
     */
    public static void resolveWindow(BotContext ctx, int kind, int landedDelta, int takenDelta, int combo,
                                     int unanswered, double weight) {
        Pending p = PENDING.remove(key(ctx, kind));
        if (p == null) return;
        double outcome = landedDelta - takenDelta
                + Math.min(3, Math.max(0, combo)) * 0.3
                - (unanswered > 0 ? Math.min(1.2, 0.4 * unanswered) : 0);
        if (Math.abs(outcome) < 0.05) return;
        if (unanswered > 0) net.h2020.api.stats.CombatStats.inc("ml.arb.unanswered");
        if (combo >= 2) net.h2020.api.stats.CombatStats.inc("ml.arb.combo");
        // inverse propensity: an action the policy rarely takes carries more information about that action, capped so a
        // 0.15-propensity sample cannot dominate the model
        double ips = Math.min(3.0, 1.0 / Math.max(0.15, p.prop));
        learnScaled(p.kind, p.x, p.action, outcome > 0, Math.max(0.4, Math.min(1.6, weight)) * ips);
    }

    public static void resolveKind(BotContext ctx, int kind, int landedDelta, int takenDelta) {
        Pending p = PENDING.remove(key(ctx, kind));
        if (p == null) return;
        double outcome = landedDelta - takenDelta;
        if (outcome == 0) return;
        learn(p.kind, p.x, p.action, outcome > 0);
    }

    /**
     * Damage-weighted variant: the sign of the outcome is the same, but the decision is graded by how much health
     * actually moved. A landed crit that takes 7 is not the same evidence as a tap that takes 1, and eating fall
     * damage for a jump-crit that missed should read as a loss even if the hit count was even.
     */
    public static void resolve(BotContext ctx, int landedDelta, int takenDelta, double dmgDealt, double dmgTaken) {
        resolve(ctx, landedDelta, takenDelta, dmgDealt, dmgTaken, 0);
    }

    /**
     * @param gearDiff our armour+weapon minus theirs: mechanics learned against better gear are worth more, and the
     *                 same win against much worse gear is worth less, because it says nothing about the mechanic.
     */
    public static void resolve(BotContext ctx, int landedDelta, int takenDelta, double dmgDealt, double dmgTaken,
                               double gearDiff) {
        double netDmg = (dmgDealt - dmgTaken) + 2.0 * (landedDelta - takenDelta);
        if (Math.abs(netDmg) < 0.5) return;
        net.h2020.api.stats.CombatStats.inc("ml.arb.dmg");
        // the learner only needs the sign of the exchange; the damage decides which sign it is
        double scale = Math.max(0.6, Math.min(1.6, 1.0 - gearDiff / 16.0));
        boolean good = netDmg > 0;
        for (int kind = 0; kind < KINDS; kind++) {
            Pending p = PENDING.remove(key(ctx, kind));
            if (p == null) continue;
            learnScaled(p.kind, p.x, p.action, good, scale);
        }
    }

    /** The exchange the decision produced, a few ticks later. */
    public static void resolve(BotContext ctx, int landedDelta, int takenDelta) {
        for (int kind = 0; kind < KINDS; kind++) {
            Pending p = PENDING.remove(key(ctx, kind));
            if (p == null) continue;
            double outcome = landedDelta - takenDelta;
            if (outcome == 0) { SKIPS[kind]++; continue; }     // nothing exchanged: no lesson either way
            learn(p.kind, p.x, p.action, outcome > 0);
        }
    }

    /** A hindsight pass: the same update at a fraction of the online learning rate. */
    public static void learnQuiet(int kind, double[] x, boolean action, boolean good, double scale) {
        learnScaled(kind, x, action, good, scale);
    }

    private static void learn(int kind, double[] x, boolean action, boolean good) {
        learnScaled(kind, x, action, good, 1.0);
    }

    private static void learnScaled(int kind, double[] x, boolean action, boolean good, double scale) {
        x[N - 1] = action ? 1 : 0;
        double p = value(kind, x, action);
        double y = good ? 1 : 0;
        double lr = 0.08 * scale / (1.0 + SAMPLES[kind] / 300.0);
        int base = kind * N;
        for (int i = 0; i < N; i++) {
            W[base + i] += lr * (y - p) * x[i];
            if (W[base + i] > 3) W[base + i] = 3;
            if (W[base + i] < -3) W[base + i] = -3;
        }
        net.h2020.api.train.Replay.log("mech", "\"m\":" + kind + ",\"x\":"
                + net.h2020.api.train.Replay.vec(x) + ",\"a\":" + (action ? 1 : 0) + ",\"y\":" + (good ? 1 : 0) + ",\"p\":" + String.format(java.util.Locale.ROOT, "%.3f", p));
        SAMPLES[kind] += 1;
        dirty = true;
    }

    /** Probability that this decision ends well, given the state and the action. */
    public static double value(int kind, double[] x, boolean action) {
        double z = 0;
        int base = kind * N;
        for (int i = 0; i < N - 1; i++) z += W[base + i] * x[i];
        z += W[base + N - 1] * (action ? 1 : 0);
        return 1.0 / (1.0 + Math.exp(-Math.max(-30, Math.min(30, z))));
    }

    public static double confidence(int kind) {
        return Math.min(1.0, SAMPLES[kind] / (MIN_SAMPLES * 2.0));
    }

    public static String name(int kind) {
        switch (kind) {
            case WTAP: return "w-tap";
            case HITRUN: return "hit-run";
            case STRAFE: return "strafe";
            case BLOCKHIT: return "block-hit";
            default: return "jump-crit";
        }
    }

    public static List<String> report() {
        StringBuilder sb = new StringBuilder("§6[ml] §ecombat arbiter: §f" + overrides + "§7 overrides, "
                + observes + "§7 watched, §f" + asks + "§7 asks");
        for (int k = 0; k < KINDS; k++) {
            sb.append(String.format(java.util.Locale.ROOT, " §7| %s %d/%d conf %d%% (asked %d, flat %d)",
                    name(k), (int) SAMPLES[k], MIN_SAMPLES * 2, (int) (confidence(k) * 100),
                    (int) ASKS[k], (int) SKIPS[k]));
        }
        List<String> out = new ArrayList<String>();
        out.add(sb.toString());
        return out;
    }

    public static void reset() {
        for (int i = 0; i < W.length; i++) W[i] = 0;
        for (int i = 0; i < SAMPLES.length; i++) SAMPLES[i] = 0;
        overrides = 0;
        asks = 0;
        observes = 0;
        PENDING.clear();
        dirty = true;
        save();
    }

    /** Re-read this model from disk (a training generation was restored). */
    public static void reload() { load(); }

    private static void load() {
        if (file == null || !file.exists()) return;
        try {
            org.bukkit.configuration.file.YamlConfiguration y =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            List<Double> w = y.getDoubleList("weights");
            if (y.getInt("n", 0) != N) {   // feature vector changed shape: the old weights mean nothing here
                net.h2020.api.stats.CombatStats.inc("ml.combatarbiter.reset");
                return;
            }
            for (int i = 0; i < Math.min(W.length, w.size()); i++) W[i] = w.get(i);
            List<Double> s = y.getDoubleList("samples");
            for (int i = 0; i < Math.min(SAMPLES.length, s.size()); i++) SAMPLES[i] = s.get(i);
            overrides = y.getLong("overrides", 0);
            asks = y.getLong("asks", 0);
            observes = y.getLong("observes", 0);
        } catch (Throwable ignored) { }
    }

    public static void save() {
        if (file == null) return;
        try {
            org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
            List<Double> w = new ArrayList<Double>(W.length);
            for (double v : W) w.add(v);
            List<Double> s = new ArrayList<Double>(SAMPLES.length);
            for (double v : SAMPLES) s.add(v);
            y.set("weights", w);
            y.set("n", N);
            y.set("samples", s);
            y.set("overrides", overrides);
            y.set("asks", asks);
            y.set("observes", observes);
            if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
            y.save(file);
            dirty = false;
        } catch (Throwable ignored) { }
    }
}
