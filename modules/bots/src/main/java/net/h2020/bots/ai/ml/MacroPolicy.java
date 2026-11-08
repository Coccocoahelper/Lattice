package net.h2020.bots.ai.ml;

import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.BotContext;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The next big thing to do, per bot: a policy over the macro actions the Bed Wars brain can take (shop, upgrade, iron,
 * defend, rush, diamonds, emeralds, guard, share, deposit).
 * <p>
 * The hand-written utility scores stay as the prior - the personality and the game situation decide what is sensible -
 * and the learned weights are a correction on top of them, trained from what the action actually achieved: a shop trip
 * that bought something, a collect that filled, a bed that got broken, a defence that held. A bot that keeps picking
 * a bad action in a situation loses score for it there and then, so the brain drifts towards what works for that bot.
 */
public final class MacroPolicy {
    public static final int ACTIONS = 10;
    public static final int N = 13;

    private static final double LR = 0.05;
    private static final double[] W = new double[ACTIONS * N];
    private static double baseline;
    private static long decisions, rewards;
    private static File file;
    private static boolean dirty;

    private static double[] lastX;
    private static int lastAction = -1;

    private MacroPolicy() {}

    public static void install(JavaPlugin plugin) {
        if (plugin == null) return;
        file = new File(plugin.getDataFolder(), "macro-policy-v3.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() { if (dirty) save(); }
        }, 1700L, 1700L);
    }

    /** The situation, in the order the weights expect it. */
    public static double[] features(BotContext ctx, int gsec, int blocks, int iron, int dias, int ems,
                                    boolean bedAlive, int roleIndex, int teammates, double targetDistance) {
        double[] x = new double[N];
        x[0] = 1;
        x[1] = clamp(gsec / 600.0, 0, 1);
        x[2] = clamp(blocks / 64.0, 0, 1);
        x[3] = clamp(iron / 32.0, 0, 1);
        x[4] = clamp(dias / 8.0, 0, 1);
        x[5] = clamp(ems / 8.0, 0, 1);
        x[6] = bedAlive ? 1 : 0;
        x[7] = clamp(Math.max(0, ctx.health()) / 20.0, 0, 1);
        x[8] = clamp(roleIndex / 3.0, 0, 1);
        x[9] = clamp(targetDistance / 100.0, 0, 1);
        // pressure and company: a chore that takes you away from a fight you are needed in is not free
        x[10] = clamp(ctx.perception.enemiesWithin(32) / 8.0, 0, 1);
        x[11] = clamp(teammates / 3.0, 0, 1);
        x[12] = clamp(net.h2020.bots.ai.inv.Inv.totalArmor(ctx.player) / 10.0, 0, 1);
        return x;
    }

    /**
     * Pick the action: the prior scores plus the learned correction, with a little exploration for the less skilled
     * bots (they try things that are not obviously the best plan).
     */
    public static int choose(BotContext ctx, double[] prior, double[] x) {
        int best = 0;
        double bv = -1e9;
        double[] s = new double[ACTIONS];
        for (int a = 0; a < ACTIONS; a++) {
            // the hand-written utility stays in charge; learning only fine-tunes it
            // multiplicative and bounded: a learned term can fine-tune a live option, it can never promote an option
            // the hand-written utility considers useless (that is how every bot ended up guarding forever)
            s[a] = prior[a] <= 0.02 ? prior[a] : prior[a] * (1.0 + 0.35 * Math.tanh(dot(a, x)));
            if (s[a] > bv) { bv = s[a]; best = a; }
        }
        // exploration: a weaker bot sometimes takes its second-best idea
        if (prior[best] > 0.02 && ctx.rng.nextDouble() < 0.10 * (1 - ctx.persona.skill.overall)) {
            int second = -1;
            double sv = -1e9;
            for (int a = 0; a < ACTIONS; a++) if (a != best && prior[a] > 0.02 && s[a] > sv) { sv = s[a]; second = a; }
            if (second >= 0) best = second;
        }
        lastX = x;
        lastAction = best;
        decisions++;
        return best;
    }

    /**
     * The action that was taken achieved something (or did not). A task that just ran for a while and ended is not a
     * reward: only a filled collect, a purchase, a broken bed, a defence that got blocks down count.
     */
    public static void reward(double result) { reward(result, 0, 0); }

    /**
     * @param seconds   how long the task ran: a plan that pays off after two minutes is worth less than one that pays
     *                  off in twenty seconds, and the model should not need a second task to notice
     * @param resources blocks and iron it consumed
     */
    public static void reward(double result, double seconds, double resources) {
        if (lastAction < 0 || lastX == null || result == 0) return;
        double r = result > 0 ? 1 : -0.5;
        r -= Math.min(0.35, seconds / 120.0);      // time cost, capped so a slow success still counts as a success
        r -= Math.min(0.25, resources / 64.0);     // resource cost, same idea
        double adv = r - baseline;
        baseline = 0.97 * baseline + 0.03 * r;
        double lr = LR / (1.0 + rewards / 400.0);
        int base = lastAction * N;
        for (int i = 0; i < N; i++) W[base + i] += lr * adv * lastX[i];
        rewards++;
        lastAction = -1;
        dirty = true;
    }

    /**
     * Death during a task. Dying is the most expensive mistake in the game and the old reward only ever reached the
     * policy when a task produced something, so a plan that kept walking the bot into a fight could never be blamed.
     * This is attributed to the plan that was running when it happened.
     */
    public static void penalize(double weight, String reason) {
        if (lastAction < 0 || lastX == null) return;
        apply(-1.5 * weight);
        net.h2020.api.stats.CombatStats.inc("ml.macro.penalty." + reason);
    }

    /** Partial credit: half of the reward for the fraction of the job that was actually banked. */
    public static void progress(double frac) {
        if (lastAction < 0 || lastX == null || frac <= 0.05) return;
        double adv = Math.min(1.0, frac) * 0.5 - baseline;
        double lr = LR / (1.0 + rewards / 400.0);
        int base = lastAction * N;
        for (int i = 0; i < N; i++) W[base + i] += lr * adv * lastX[i];
        net.h2020.api.stats.CombatStats.inc("ml.macro.progress");
    }

    /**
     * Walking away from a task because of a threat is a good decision, not a missing one. Without this the policy only
     * ever hears about tasks that finished, and learns to prefer safe chores over the rush that got interrupted.
     */
    public static void abandon() {
        if (lastAction < 0 || lastX == null) return;
        apply(0.15);
        net.h2020.api.stats.CombatStats.inc("ml.macro.abandon");
    }

    private static void apply(double r) {
        double adv = r - baseline;
        baseline = 0.97 * baseline + 0.03 * r;
        double lr = LR / (1.0 + rewards / 400.0);
        int base = lastAction * N;
        for (int i = 0; i < N; i++) W[base + i] += lr * adv * lastX[i];
        rewards++;
        lastAction = -1;
        dirty = true;
    }

    private static double dot(int a, double[] x) {
        double z = 0;
        int base = a * N;
        for (int i = 0; i < N; i++) z += W[base + i] * x[i];
        return z;
    }

    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }

    public static List<String> report() {
        String[] names = {"shop", "upgrade", "iron", "defend", "rush", "diamond", "emerald", "guard", "share", "deposit"};
        List<String> out = new ArrayList<String>();
        out.add(String.format("§6[ml] §emacro: §f%d§7 decisions, §f%d§7 rewards, baseline §f%+.2f", decisions, rewards, baseline));
        StringBuilder sb = new StringBuilder("§7action preferences:");
        for (int a = 0; a < ACTIONS; a++) sb.append(String.format(" %s=%+.2f", names[a], W[a * N]));
        out.add(sb.toString());
        return out;
    }

    public static void reset() {
        for (int i = 0; i < W.length; i++) W[i] = 0;
        baseline = 0;
        decisions = 0;
        rewards = 0;
        lastAction = -1;
        lastX = null;
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
                net.h2020.api.stats.CombatStats.inc("ml.macropolicy.reset");
                return;
            }
            for (int i = 0; i < Math.min(W.length, w.size()); i++) W[i] = w.get(i);
            baseline = y.getDouble("baseline", 0);
            decisions = y.getLong("decisions", 0);
            rewards = y.getLong("rewards", 0);
        } catch (Throwable ignored) { }
    }

    public static void save() {
        if (file == null) return;
        try {
            org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
            List<Double> w = new ArrayList<Double>(W.length);
            for (double v : W) w.add(v);
            y.set("weights", w);
            y.set("n", N);
            y.set("baseline", baseline);
            y.set("decisions", decisions);
            y.set("rewards", rewards);
            if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
            y.save(file);
            dirty = false;
        } catch (Throwable ignored) { }
    }
}
