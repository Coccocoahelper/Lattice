package net.h2020.bots.ai.ml;

import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.BotContext;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Movement and clicks inside a melee fight: a contextual bandit over the seven things a 1.8 player does in the last
 * three blocks. Every window of ~10 ticks one stance is chosen and held; when the window ends the exchange is scored
 * (hits landed minus hits taken) and the stance learns whether it works from that kind of position.
 * <ul>
 * <li>press - walk in, sprint, click on cooldown;</li>
 * <li>circle left / circle right - strafe around them while clicking (the side the fight-flow model says is open);</li>
 * <li>space - back out and reset the sprint (S-tap);</li>
 * <li>block hit - right-click the sword between hits, hold the ground;</li>
 * <li>reset - stop clicking, wait out their hitstun, come back in;</li>
 * <li>jump crit - jump and hit on the way down.</li>
 * </ul>
 * The stance never replaces the physics or the aim model: it sets intent, Combat still clamps every step against the
 * void and HumanMouse still does the mouse work.
 */
public final class DuelPolicy {
    public static final int PRESS = 0, CIRCLE_L = 1, CIRCLE_R = 2, SPACE = 3, BLOCK_HIT = 4, RESET = 5, JUMP_CRIT = 6;
    public static final int ACTIONS = 7;
    /** Feature width. Bumped from 10: height, void, gear and the opponent's footing decide a trade as much as hp. */
    public static final int N = 14;

    private static final double LR = 0.08;
    private static final double[] W = new double[ACTIONS * N];
    private static double baseline;
    private static long windows, fights;
    /** The last window's inputs, so the fight's outcome can be credited to the stance that was actually running. */
    private static double[] lastX;
    private static int lastAction = -1;
    /** Did the last section move health either way? Decides whether the fight result may speak at all. */
    private static boolean lastWindowDamage;
    private static File file;
    private static boolean dirty;
    private static final java.util.Random R = new java.util.Random();

    private DuelPolicy() {}

    public static void install(JavaPlugin plugin) {
        if (plugin == null) return;
        file = new File(plugin.getDataFolder(), "duel-policy.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() { if (dirty) save(); }
        }, 1500L, 1500L);
    }

    public static double[] features(BotContext ctx, org.bukkit.entity.Player target, double d, double pWin,
                                    int combo, int hitsTaken, boolean voidBehind) {
        double[] x = new double[N];
        double myHp = Math.max(0, ctx.health());
        double theirHp = target == null ? 20 : Math.max(0, target.getHealth());
        x[0] = 1;
        x[1] = Math.min(1, myHp / 20.0);
        x[2] = Math.min(1, theirHp / 20.0);
        x[3] = clamp((myHp - theirHp) / 20.0, -1, 1);
        x[4] = clamp(d / 5.0, 0, 1);
        x[5] = clamp((pWin - 0.5) * 2, -1, 1);
        x[6] = clamp(combo / 5.0, 0, 1);
        x[7] = clamp(hitsTaken / 5.0, 0, 1);
        x[8] = ctx.c.onGround() ? 1 : 0;
        x[9] = voidBehind ? 1 : 0;
        // height: fighting up a block is a different fight from fighting down one
        x[10] = target == null ? 0 : clamp(-(target.getLocation().getY() - ctx.pos().getY()) / 3.0, -1, 1);
        // how exposed we are: two void sides means any knockback is lethal
        int vx = (int) Math.floor(ctx.pos().getX()), vy = (int) Math.floor(ctx.pos().getY() + 0.01), vz = (int) Math.floor(ctx.pos().getZ());
        x[11] = Math.min(1, ctx.world().voidSides(vx, vy, vz) / 4.0);
        // gear: the sword in hand against the one they are holding is most of a duel's outcome
        x[12] = clamp((swordTier(ctx.player) - swordTier(target)) / 3.0, -1, 1);
        x[13] = target != null && target.isOnGround() ? 1 : 0;
        return x;
    }

    /** 0 = fist, 3 = diamond; only the material matters for a duel. */
    private static int swordTier(org.bukkit.entity.Player p) {
        if (p == null) return 0;
        org.bukkit.inventory.ItemStack it = p.getItemInHand();
        if (it == null) return 0;
        switch (it.getType()) {
            case WOOD_SWORD: case GOLD_SWORD: return 1;
            case STONE_SWORD: return 2;
            case IRON_SWORD: return 3;
            case DIAMOND_SWORD: return 4;
            default: return 0;
        }
    }

    /** How much this model should be trusted yet (0 = use the hand-written mechanics, 1 = fully learned). */
    public static double confidence() { return Math.min(1.0, windows / 160.0); }

    public static long windows() { return windows; }

    /** Sample a stance for the next window. */
    public static int choose(double[] x) {
        double[] p = probabilities(x);
        double r = R.nextDouble();
        double acc = 0;
        for (int a = 0; a < ACTIONS; a++) { acc += p[a]; if (r <= acc) return a; }
        return PRESS;
    }

    public static double[] probabilities(double[] x) {
        double[] z = new double[ACTIONS];
        double max = -1e9;
        for (int a = 0; a < ACTIONS; a++) { z[a] = dot(a, x); max = Math.max(max, z[a]); }
        double sum = 0;
        for (int a = 0; a < ACTIONS; a++) { z[a] = Math.exp((z[a] - max) / 0.8); sum += z[a]; }
        for (int a = 0; a < ACTIONS; a++) z[a] /= sum;
        return z;
    }

    /** A window ended: hits landed minus hits taken decides whether that stance was a good idea here. */
    public static void record(double[] x, int action, int hitsLanded, int hitsTaken) {
        recordScaled(x, action, hitsLanded, hitsTaken, 1.0);
    }

    /**
     * The same window, graded by health instead of hit counts. Six damage of net trade is a full point, so a crit
     * (7) outweighs a tap (1) and eating fall damage from a missed jump-crit reads as the loss it is. Falls back to
     * the hit count when nothing measurable came off either bar (blocked hits, clean misses).
     */
    public static void record(double[] x, int action, int hitsLanded, int hitsTaken, double dmgDealt, double dmgTaken) {
        record(x, action, hitsLanded, hitsTaken, dmgDealt, dmgTaken, 1.0);
    }

    /**
     * @param weight evidence weight for this window: beating somebody the fight model expects to lose to is worth more
     *               than beating somebody it expects to beat, otherwise a stance farmed against weak opponents and one
     *               holding against strong ones look identical.
     */
    public static void record(double[] x, int action, int hitsLanded, int hitsTaken, double dmgDealt, double dmgTaken,
                              double weight) { record(x, action, hitsLanded, hitsTaken, dmgDealt, dmgTaken, weight, 0); }

    public static void record(double[] x, int action, int hitsLanded, int hitsTaken, double dmgDealt, double dmgTaken,
                              double weight, double gearDiff) {
        record(x, action, hitsLanded, hitsTaken, dmgDealt, dmgTaken, weight, gearDiff, 0, 0);
    }

    /**
     * One section of a fight (a ~10 tick window), graded on its own. A window is not the fight: a good exchange late
     * in a lost fight is still a good exchange, so the result never reaches back into earlier sections.
     *
     * @param combo      consecutive hits landed without taking one before this window
     * @param unanswered hits taken in this window with nothing landed back: the one pattern that must always lose
     *                   value, whatever the window's net damage says
     */
    public static void record(double[] x, int action, int hitsLanded, int hitsTaken, double dmgDealt, double dmgTaken,
                              double weight, double gearDiff, int combo, int unanswered) {
        net.h2020.api.stats.CombatStats.inc("ml.duel.call");
        double netDmg = dmgDealt - dmgTaken;
        double bonus = Math.min(5, Math.max(0, combo)) * 0.06;                  // combos pay, capped
        double penalty = unanswered > 0 ? Math.min(0.6, 0.25 * unanswered) : 0; // hit back or lose value
        if (bonus > 0) net.h2020.api.stats.CombatStats.inc("ml.duel.combo");
        if (penalty > 0) net.h2020.api.stats.CombatStats.inc("ml.duel.unanswered");
        if (Math.abs(netDmg) >= 1.0 || dmgDealt + dmgTaken >= 2.0) {
            net.h2020.api.stats.CombatStats.inc("ml.duel.dmg");
            lastWindowDamage = true;
            double gearScale = clamp(1.0 - gearDiff / 16.0, 0.6, 1.6);   // out-geared trades teach more
            recordReward(x, action, clamp(netDmg / 6.0 + bonus - penalty, -1, 1), weight * gearScale);
        } else {
            lastWindowDamage = false;
            // even the hit-count fallback leans on damage when any moved: a tap that lost 7 HP is not a draw
            double base = (hitsLanded - hitsTaken) / 2.0;
            double dmg = clamp((dmgDealt - dmgTaken) / 6.0, -1, 1);
            if (dmgDealt + dmgTaken > 0.1) base = 0.6 * base + 0.4 * dmg;
            double evidence = 0.5 + Math.min(0.5, Math.abs(dmgDealt - dmgTaken) / 12.0);
            recordReward(x, action, clamp(base + bonus - penalty, -1, 1), weight * evidence);
        }
    }

    /** Hindsight pass: the same update at a fraction of the online learning rate. */
    public static void recordQuiet(double[] x, int action, double reward, double scale) {
        recordReward(x, action, reward, scale);
    }

    private static void recordScaled(double[] x, int action, int hitsLanded, int hitsTaken, double scale) {
        recordReward(x, action, clamp((hitsLanded - hitsTaken) / 2.0, -1, 1), scale);
    }

    private static void recordReward(double[] x, int action, double reward, double scale) {
        if (x == null) return;
        double adv = reward - baseline;
        baseline = 0.97 * baseline + 0.03 * reward;
        double[] p = probabilities(x);
        double lr = LR * scale / (1.0 + windows / 800.0);
        for (int a = 0; a < ACTIONS; a++) {
            double ind = a == action ? 1 : 0;
            double g = adv * (ind - p[a]);
            for (int i = 0; i < N; i++) W[a * N + i] += lr * g * x[i];
        }
        net.h2020.api.train.Replay.log("stance", "\"x\":" + net.h2020.api.train.Replay.vec(x)
                + ",\"a\":" + action + ",\"r\":" + String.format(java.util.Locale.ROOT, "%.3f", reward)
                + ",\"p\":" + String.format(java.util.Locale.ROOT, "%.3f", p[action]));
        lastX = x;
        lastAction = action;
        windows++;
        dirty = true;
    }

    /**
     * The opponent went into the void within a couple of seconds of our hit. A hit trade cannot see that: the swing
     * that set it up may have been the last one in the window, and the fall happens after the window already closed.
     * Credits the stance that was running when the hit landed.
     */
    public static void voidCredit() {
        if (lastX == null || lastAction < 0) return;
        recordReward(lastX, lastAction, 0.8, 0.8);
        net.h2020.api.stats.CombatStats.inc("ml.duel.voidcredit");
    }

    /**
     * The fight ended. Hit trades inside a fight are a proxy; the result is the thing being predicted, so the final
     * stance gets the terminal signal - winning the fight pays even if the last window traded evenly, dying costs even
     * if it did not, and getting knocked into the void costs most of all.
     */
    public static void terminal(boolean win, boolean died) { terminal(win, died, 0); }

    /**
     * The fight's result, graded against the gear the two of us were holding. Losing to a full diamond player while
     * wearing chain is the expected outcome and is barely a lesson; beating that player is the most valuable sample the
     * model can get. The reverse is just as true: losing a fight you were kitted out to win is the expensive mistake.
     *
     * @param gearDiff (my armour + weapon) - (theirs), the same units the fight model uses
     */
    public static void terminal(boolean win, boolean died, double gearDiff) {
        if (lastX == null || lastAction < 0) return;
        // the result is worth a fraction of a section, and only when the last section had nothing measurable to say:
        // otherwise a fight that ended badly would overrule a window that was actually won
        if (lastWindowDamage) {
            net.h2020.api.stats.CombatStats.inc("ml.duel.terminal.skipped");
            lastX = null;
            lastAction = -1;
            return;
        }
        double pGear = clamp(0.5 + gearDiff / 10.0, 0.1, 0.9);   // expected win chance from gear alone
        double surprise = (win ? 1 : 0) - pGear;                 // -0.9 .. 0.9
        double r = clamp(surprise, -1.0, 1.0);
        if (died) r -= 0.15;                                      // dying costs, but it is not the whole lesson
        if (gearDiff <= -3) net.h2020.api.stats.CombatStats.inc("ml.gear.outgunned");
        else if (gearDiff >= 3) net.h2020.api.stats.CombatStats.inc("ml.gear.advantage");
        recordReward(lastX, lastAction, r, 0.4);
        lastX = null;
        lastAction = -1;
        net.h2020.api.stats.CombatStats.inc("ml.duel.terminal");
    }

    public static void onFightEnd() { fights++; dirty = true; }

    private static double dot(int a, double[] x) {
        double z = 0;
        int base = a * N;
        for (int i = 0; i < N; i++) z += W[base + i] * x[i];
        return z;
    }

    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }

    public static List<String> report() {
        String[] names = {"press", "circleL", "circleR", "space", "blockHit", "reset", "jumpCrit"};
        List<String> out = new ArrayList<String>();
        out.add(String.format("§6[ml] §eduel stances: §f%d§7 windows, §f%d§7 fights, baseline §f%+.2f", windows, fights, baseline));
        StringBuilder sb = new StringBuilder("§7stance preferences:");
        // what each stance is worth at an even fight: the bias term
        for (int a = 0; a < ACTIONS; a++) sb.append(String.format(" %s=%+.2f", names[a], W[a * N]));
        out.add(sb.toString());
        return out;
    }

    public static void reset() {
        for (int i = 0; i < W.length; i++) W[i] = 0;
        baseline = 0;
        windows = 0;
        fights = 0;
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
            if (y.getInt("n", 0) != N) {
                // the feature vector changed shape: the old weights mean nothing in the new basis, start clean but
                // keep the baseline so the first fights are still scored against a sensible average
                baseline = y.getDouble("baseline", 0);
                windows = 0;
                fights = 0;
                net.h2020.api.stats.CombatStats.inc("ml.duel.features.reset");
                return;
            }
            List<Double> w = y.getDoubleList("weights");
            for (int i = 0; i < Math.min(W.length, w.size()); i++) W[i] = w.get(i);
            baseline = y.getDouble("baseline", 0);
            windows = y.getLong("windows", 0);
            fights = y.getLong("fights", 0);
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
            y.set("windows", windows);
            y.set("fights", fights);
            if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
            y.save(file);
            dirty = false;
        } catch (Throwable ignored) { }
    }
}
