package net.h2020.bots.ai.ml;

import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.inv.Items;
import net.h2020.bots.ai.perception.Sighting;
import net.h2020.bots.ai.world.WorldView;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Online logistic regression that learns who wins a 1.8 duel from the variables a player actually judges a fight by.
 * <p>
 * One sample per fight: the feature vector is snapshotted when the two of them first get into reach, and the label
 * arrives when the fight ends (the target dies = win, we die = loss). SGD with a decaying step size updates the
 * weights on every finished fight, so the bots get better at judging "do I take this fight" during a session and
 * across restarts (the model is saved to fight-model.yml). If a fight has no outcome (we ran, both lived) nothing is
 * learned from it.
 * <p>
 * Features (all scaled to a comparable range):
 * <pre>
 *  0 bias                     1 my hp / 20                 2 their hp / 20
 *  3 hp advantage / 20        4 gear advantage / 8         5 closeness (10 - d) / 10
 *  6 blocks / 64              7 gapple in inventory        8 buffed (speed/strength/invis)
 *  9 teammate within 10      10 their teammate within 10  11 void next to me
 * 12 void next to them       13 hits taken recently / 5   14 my combo / 5
 * 15 their armour / 10       16 my armour / 10            17 weapon advantage / 8
 * 18 their perceived skill  19 my recent form (confidence) 20 tilt (losing streak)
 * 21 my block-in speed at a bed
 * 22 my armour / 10         23 their armour / 10          24 my potion effects / 3
 * 25 their potion effects / 3  26 my hunger / 20          27 my ping / 300
 * </pre>
 * Until it has seen a few dozen fights the prediction is blended toward 0.5, so decision code never follows a model
 * that has not learned anything yet.
 */
public final class FightModel {
    public static final int N = 30;
    private static final double LR = 0.09;
    private static final long MIN_SAMPLES = 30;
    /** Fights after which the learned weights have fully replaced the hand-written baseline. */
    private static final double BLEND_SAMPLES = 150;
    /** Softens the baseline's logit: a player at 6 hp is in trouble, not mathematically dead. */
    private static final double PRIOR_TEMP = 1.5;

    /**
     * The hard-coded statistical baseline: what a decent Bed Wars player knows about duels before any learning. It is
     * a real estimate from the first fight on - the learned weights only take over gradually as fights are seen.
     */
    private static final double[] PRIOR_W = new double[N];
    private static final double PRIOR_B;
    static {
        PRIOR_W[1] = 2.6; PRIOR_W[2] = -2.6; PRIOR_W[3] = 2.2; PRIOR_W[4] = 0.9; PRIOR_W[5] = 0.35;
        PRIOR_W[6] = 0.25; PRIOR_W[7] = 0.5; PRIOR_W[8] = 0.45; PRIOR_W[9] = 0.5; PRIOR_W[10] = -0.55;
        PRIOR_W[11] = -1.4; PRIOR_W[12] = 1.1; PRIOR_W[13] = -0.9; PRIOR_W[14] = 0.45;
        PRIOR_W[15] = -0.8; PRIOR_W[16] = 0.8; PRIOR_W[17] = 0.7;
        // perceived skill of the opponent, own form, tilt, own block-in speed
        PRIOR_W[18] = -0.9; PRIOR_W[19] = 0.7; PRIOR_W[20] = -0.5; PRIOR_W[21] = 0.35;
        // armour points both sides, potions both sides, hunger, ping
        PRIOR_W[22] = 0.5; PRIOR_W[23] = -0.5; PRIOR_W[24] = 0.3; PRIOR_W[25] = -0.3; PRIOR_W[26] = 0.15; PRIOR_W[27] = -0.2;
        PRIOR_B = -0.15;
    }

    /** Learned weights (seeded from the baseline, so a fresh model already answers with the statistics). */
    private static final double[] W = new double[N];
    private static double bias;
    private static long samples;
    private static double accuracy = 0.5;
    private static double lossEma = 0.7;
    /** Platt calibration of the predicted probability: p' = sigmoid(calA * logit(p) + calB). */
    private static double calA = 1.0, calB = 0.0;
    private static double brier = 0.25;
    private static long calibrations;
    /** Labels from replays (damage-delta guesses), kept out of the sample count and calibration. */
    private static long shadows;
    /** 10-bin reliability table for the expected calibration error. */
    private static final double[] BIN_P = new double[10], BIN_Y = new double[10], BIN_N = new double[10];
    private static File file;
    private static boolean dirty;
    private static final Random R = new Random(7);

    static {
        resetToPrior();
    }

    private static void resetToPrior() {
        System.arraycopy(PRIOR_W, 0, W, 0, N);
        bias = PRIOR_B;
    }

    private FightModel() {}

    public static void install(JavaPlugin plugin) {
        if (plugin == null) return;
        file = new File(plugin.getDataFolder(), "fight-model.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() { if (dirty) save(); }
        }, 1200L, 1200L);
    }

    private static void load() {
        if (file == null || !file.exists()) return;
        try {
            org.bukkit.configuration.file.YamlConfiguration y =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            List<Double> w = y.getDoubleList("weights");
            if (y.getInt("n", 0) != N) {   // feature vector changed shape: the old weights mean nothing here
                net.h2020.api.stats.CombatStats.inc("ml.fightmodel.reset");
                return;
            }
            for (int i = 0; i < Math.min(N, w.size()); i++) W[i] = w.get(i);
            bias = y.getDouble("bias", bias);
            calA = y.getDouble("calA", calA);
            calB = y.getDouble("calB", calB);
            brier = y.getDouble("brier", brier);
            samples = y.getLong("samples", 0);
            accuracy = y.getDouble("accuracy", 0.5);
            lossEma = y.getDouble("loss", 0.7);
        } catch (Throwable ignored) { }
    }

    public static void save() {
        if (file == null) return;
        try {
            org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
            List<Double> w = new ArrayList<Double>(N);
            for (double v : W) w.add(v);
            y.set("weights", w);
            y.set("n", N);
            y.set("bias", bias);
            y.set("calA", calA);
            y.set("calB", calB);
            y.set("brier", brier);
            y.set("samples", samples);
            y.set("accuracy", accuracy);
            y.set("loss", lossEma);
            if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
            y.save(file);
            dirty = false;
        } catch (Throwable ignored) { }
    }

    /** The feature vector of a fight with `target` at `dist` blocks, as this bot sees it right now. */
    public static double[] features(BotContext ctx, Player target, double dist) {
        double[] x = new double[N];
        Player me = ctx.player;
        double myHp = Math.max(0, me.getHealth()), theirHp = target == null ? 20 : Math.max(0, target.getHealth());
        double myGear = Inv.totalArmor(me) + Inv.bestMeleeValue(me);
        double theirGear = target == null ? 6 : Inv.totalArmor(target) + Inv.bestMeleeValue(target);
        x[0] = 1;
        x[1] = clamp01(myHp / 20.0);
        x[2] = clamp01(theirHp / 20.0);
        x[3] = clampAbs((myHp - theirHp) / 20.0, 1);
        x[4] = clampAbs((myGear - theirGear) / 8.0, 1);
        x[5] = clamp01((10 - dist) / 10.0);
        x[6] = clamp01(Inv.count(me, Inv.BLOCK) / 64.0);
        x[7] = Inv.has(me, Inv.GAPPLE) ? 1 : 0;
        x[8] = (me.hasPotionEffect(org.bukkit.potion.PotionEffectType.SPEED)
                || me.hasPotionEffect(org.bukkit.potion.PotionEffectType.INCREASE_DAMAGE)
                || me.hasPotionEffect(org.bukkit.potion.PotionEffectType.JUMP)) ? 1 : 0;
        x[9] = teammateWithin(ctx, me, 10) ? 1 : 0;
        x[10] = target != null && teammateWithin(ctx, target, 10) ? 1 : 0;
        WorldView wv = ctx.world();
        int y = floor(me.getLocation().getY() + 0.01);
        x[11] = voidAround(wv, floor(me.getLocation().getX()), y, floor(me.getLocation().getZ()), 2) ? 1 : 0;
        if (target != null) {
            int ty = floor(target.getLocation().getY() + 0.01);
            x[12] = voidAround(wv, floor(target.getLocation().getX()), ty, floor(target.getLocation().getZ()), 2) ? 1 : 0;
        }
        Sighting s = ctx.perception.get(target);
        x[13] = clamp01((s == null ? 0 : Math.min(5, s.hitsOnMe)) / 5.0);
        x[14] = 0;                              // combo is filled by the caller that owns the fight state
        x[15] = clamp01((target == null ? 0 : Inv.totalArmor(target)) / 10.0);
        x[16] = clamp01(Inv.totalArmor(me) / 10.0);
        double myWeapon = Inv.bestMeleeValue(me);
        double theirWeapon = target == null ? 1 : Items.meleeValue(target.getItemInHand());
        x[17] = clampAbs((myWeapon - theirWeapon) / 8.0, 1);
        // the opponent as this bot has read them (gear seen, how fast they click, how far they reach), not the truth
        x[18] = clamp01(perceivedSkill(target, s));
        // and the bot's own state: form, tilt, and how quickly it gets blocks down at a bed
        x[19] = clamp01(net.h2020.bots.ai.ml.SelfStats.confidence(me));
        x[20] = clamp01(net.h2020.bots.ai.ml.SelfStats.tilt(me));
        x[21] = clamp01(net.h2020.bots.ai.ml.SelfStats.blockInSpeed(me));
        x[22] = clamp01(Inv.totalArmor(me) / 10.0);
        x[23] = clamp01((target == null ? 0 : Inv.totalArmor(target)) / 10.0);
        x[24] = clamp01(me.getActivePotionEffects().size() / 3.0);
        x[25] = clamp01((target == null ? 0 : target.getActivePotionEffects().size()) / 3.0);
        x[26] = clamp01(me.getFoodLevel() / 20.0);
        x[27] = clamp01(ctx.pingMs() / 300.0);
        x[28] = clampAbs((me.getLocation().getY() - (target == null ? me.getLocation().getY()
                : target.getLocation().getY())) / 3.0, 1);
        x[29] = target == null ? 0 : clamp01(Inv.count(target, Inv.BLOCK) / 64.0);
        return x;
    }

    /**
     * The same vector for a player who is not one of our bots (a human): no persona, no perception object, no combo
     * state - everything else is what anybody could see of that fight. Used to train the model on real gameplay.
     */
    public static double[] featuresRaw(Player me, Player target, double dist) {
        double[] x = new double[N];
        x[0] = 1;
        double myHp = Math.max(0, me.getHealth()), theirHp = target == null ? 20 : Math.max(0, target.getHealth());
        x[1] = clamp01(myHp / 20.0);
        x[2] = clamp01(theirHp / 20.0);
        x[3] = clampAbs((myHp - theirHp) / 20.0, 1);
        x[4] = clampAbs(((Inv.totalArmor(me) + Inv.bestMeleeValue(me)) - (target == null ? 6 : Inv.totalArmor(target) + Inv.bestMeleeValue(target))) / 8.0, 1);
        x[5] = clamp01((10 - dist) / 10.0);
        x[6] = clamp01(Inv.count(me, Inv.BLOCK) / 64.0);
        x[7] = Inv.has(me, Inv.GAPPLE) ? 1 : 0;
        x[8] = me.getActivePotionEffects().size() > 0 ? 1 : 0;
        x[9] = 0;                                     // teammate proximity is the brain's knowledge, not the game's
        x[10] = 0;
        x[11] = 0;
        x[12] = 0;
        x[13] = clamp01(Math.min(5, net.h2020.bots.ai.perception.PlayerStats.hitsTaken(me)) / 5.0);
        x[14] = 0;
        x[15] = clamp01((target == null ? 0 : Inv.totalArmor(target)) / 10.0);
        x[16] = clamp01(Inv.totalArmor(me) / 10.0);
        x[17] = clampAbs((Inv.bestMeleeValue(me) - (target == null ? 1 : Items.meleeValue(target.getItemInHand()))) / 8.0, 1);
        x[18] = target == null ? 0.5 : clamp01(0.15 + 0.35 * (Inv.totalArmor(target) / 10.0)
                + 0.2 * Math.min(1, net.h2020.bots.ai.perception.PlayerStats.cps(target) / 12.0));
        x[19] = clamp01(net.h2020.bots.ai.ml.SelfStats.confidence(me));
        x[20] = clamp01(net.h2020.bots.ai.ml.SelfStats.tilt(me));
        x[21] = clamp01(net.h2020.bots.ai.ml.SelfStats.blockInSpeed(me));
        x[22] = clamp01(Inv.totalArmor(me) / 10.0);
        x[23] = clamp01((target == null ? 0 : Inv.totalArmor(target)) / 10.0);
        x[24] = clamp01(me.getActivePotionEffects().size() / 3.0);
        x[25] = clamp01((target == null ? 0 : target.getActivePotionEffects().size()) / 3.0);
        x[26] = clamp01(me.getFoodLevel() / 20.0);
        x[27] = clamp01(net.h2020.bots.ai.perception.LagView.pingOf(me) / 300.0);
        return x;
    }

    /** Overwrite the combo slot (the combat state lives in Combat, not here). */
    public static void setCombo(double[] x, int combo, int hitsTaken) {
        if (x == null) return;
        x[14] = clampAbs((combo - hitsTaken) / 5.0, 1);
        x[13] = clamp01(Math.min(5, hitsTaken) / 5.0);
    }

    /**
     * P(win) of this fight. Never a coin flip: the hard-coded statistical baseline answers from the first fight on,
     * and the learned weights are blended in as fights are seen (fully after {@link #BLEND_SAMPLES}).
     */
    public static double predict(double[] x) {
        double pPrior = sigmoid((PRIOR_B + dot(PRIOR_W, x)) / PRIOR_TEMP);
        double t = Math.min(1.0, samples / BLEND_SAMPLES);
        if (t <= 0) return pPrior;
        double pLearned = sigmoid(bias + dot(W, x));
        return calibrate(pPrior * (1 - t) + pLearned * t);
    }

    /** Logistic calibration. A model whose 0.7 means "usually 0.55" gates fights wrongly; this is the fix. */
    private static double calibrate(double p) {
        double q = p < 1e-6 ? 1e-6 : (p > 1 - 1e-6 ? 1 - 1e-6 : p);
        double z = Math.log(q / (1 - q));
        return sigmoid(calA * z + calB);
    }

    /** Probability the decision code should use: blended model, then calibrated. */
    public static double pWin(double[] x) {
        // under ten observed outcomes the fit is noise: stay with the raw probability until there is evidence
        return calibrations >= 10 ? calibrate(predict(x)) : predict(x);
    }

    private static double dot(double[] w, double[] x) {
        double z = 0;
        for (int i = 0; i < N; i++) z += w[i] * x[i];
        return z;
    }

    /** Learn from a finished fight (win = the target died first). */
    public static void learn(double[] x, boolean win) { learn(x, win, 1.0); }

    /**
     * @param weight how much this label is trusted. A real fight is 1; a label reconstructed from damage deltas in a
     *               replay is a guess and is fed at a fraction, so it cannot outvote what actually happened. Synthetic
     *               labels also do not move the blend counter or the calibration: those must reflect real fights.
     */
    public static void learn(double[] x, boolean win, double weight) {
        if (x == null || weight <= 0) return;
        double p = predictRaw(x);
        double y = win ? 1 : 0;
        double lr = LR * Math.min(1.0, weight) / (1.0 + samples / 400.0);
        // wins are the rarer class: give them more weight, and clamp a single outlier fight so one 1-hp win
        // cannot swing thirty features at once
        double err = p - y;
        double wgt = win ? 1.25 : 1.0;
        double clamped = Math.max(-0.85, Math.min(0.85, err));
        for (int i = 0; i < N; i++) W[i] -= lr * wgt * clamped * x[i];
        bias -= lr * wgt * clamped;
        // light L2 so a long session cannot run the weights away
        for (int i = 1; i < N; i++) W[i] *= (1 - 0.0005);
        lossEma = 0.98 * lossEma + 0.02 * (-(y * Math.log(p + 1e-9) + (1 - y) * Math.log(1 - p + 1e-9)));
        if (weight >= 0.9) samples++;
        else shadows++;
        dirty = true;
    }

    /** Record whether the prediction that was made before the fight turned out right. */
    public static void observe(double prediction, boolean win) {
        boolean correct = (prediction >= 0.5) == win;
        accuracy = 0.97 * accuracy + 0.03 * (correct ? 1 : 0);
        double y = win ? 1 : 0;
        double p = Math.max(1e-4, Math.min(1 - 1e-4, prediction));
        net.h2020.api.stats.CombatStats.inc("ml.cal.observed");
        calibrations++;
        // Platt fit on exactly the (prediction, outcome) pairs the fight model handed to the decision code
        double z = Math.log(p / (1 - p));
        double q = sigmoid(calA * z + calB);
        double lr = 0.05;                       // 14 fights already move it visibly; the clamp keeps it sane
        calA -= lr * (q - y) * z;
        calB -= lr * (q - y);
        calA = Math.max(0.15, Math.min(3.5, calA));
        calB = Math.max(-2.5, Math.min(2.5, calB));
        brier = 0.98 * brier + 0.02 * (p - y) * (p - y);
        int bin = Math.min(9, Math.max(0, (int) (p * 10)));
        BIN_P[bin] += p; BIN_Y[bin] += y; BIN_N[bin] += 1;
    }

    /** Expected calibration error over the last window of observed fights (0 = perfectly calibrated). */
    public static double ece() {
        double total = 0, n = 0;
        for (int i = 0; i < 10; i++) {
            if (BIN_N[i] <= 0) continue;
            total += Math.abs(BIN_Y[i] / BIN_N[i] - BIN_P[i] / BIN_N[i]) * BIN_N[i];
            n += BIN_N[i];
        }
        return n == 0 ? 0 : total / n;
    }

    public static double brier() { return brier; }
    public static double calA() { return calA; }
    public static double calB() { return calB; }

    private static double predictRaw(double[] x) {
        double z = bias;
        for (int i = 0; i < N; i++) z += W[i] * x[i];
        return sigmoid(z);
    }

    public static boolean confident() { return samples >= MIN_SAMPLES; }

    public static long samples() { return samples; }

    public static double accuracy() { return accuracy; }

    public static double loss() { return lossEma; }

    public static List<String> report() {
        List<String> out = new ArrayList<String>();
        out.add(String.format("§6[ml] §efights seen: §f%d§7, accuracy §f%.0f%%§7, log-loss §f%.3f§7, learned blend §f%d%%§7 (statistics until then: §f%s§7)",
                samples, accuracy * 100, lossEma, (int) (100 * Math.min(1, samples / BLEND_SAMPLES)), "bedwars duel baseline"));
        out.add(String.format("§6[ml] §ecalibration: brier §f%.3f§7, ece §f%.3f§7, platt a=§f%.2f§7 b=§f%.2f§7",
                brier, ece(), calA, calB));
        String[] names = {"bias", "myHp", "theirHp", "hpDiff", "gearDiff", "closeness", "blocks", "gapple", "buffed",
                "mateNear", "theirMate", "voidMine", "voidTheirs", "hitsTaken", "combo", "theirArmor", "myArmor", "weapon",
                "theirSkill", "form", "tilt", "blockIn", "myArmourPts", "theirArmourPts", "myPotions", "theirPotions",
                "hunger", "ping", "height", "theirBlocks"};
        StringBuilder sb = new StringBuilder("§7weights:");
        for (int i = 0; i < Math.min(N, names.length); i++) sb.append(String.format(" %s=%.2f", names[i], W[i]));
        out.add(sb.toString());
        return out;
    }

    public static void reset() {
        resetToPrior();
        samples = 0;
        accuracy = 0.5;
        lossEma = 0.7;
        dirty = true;
        save();
    }

    // ------------------------------------------------------------------ helpers

    private static boolean teammateWithin(BotContext ctx, Player around, double r) {
        for (Player p : around.getWorld().getPlayers()) {
            if (p == around || p == ctx.player) continue;
            if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            if (p.getLocation().distanceSquared(around.getLocation()) > r * r) continue;
            // teammates share a scoreboard team in the game modules; use the bot's own team list instead
            if (isTeammate(ctx, around, p)) return true;
        }
        return false;
    }

    private static boolean isTeammate(BotContext ctx, Player a, Player b) {
        net.h2020.api.game.GameManager gm = net.h2020.api.H2020.find(net.h2020.api.game.GameManager.class);
        net.h2020.api.game.Game g = gm == null ? null : gm.gameOf(a);
        if (g == null) return false;
        net.h2020.api.game.GameTeam ta = g.teamOf(a), tb = g.teamOf(b);
        return ta != null && ta == tb;
    }

    private static boolean voidAround(WorldView wv, int x, int y, int z, int r) {
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (dx == 0 && dz == 0) continue;
            if (wv.passable(x + dx, y, z + dz) && wv.voidBelow(x + dx, y, z + dz, 20)) return true;
        }
        return false;
    }

    /**
     * What the bot thinks of the opponent from what it has seen: gear on the body, the weapon in hand, how fast they
     * click (the sound/animations go around), and how far they have been hitting from. 0..1.
     */
    private static double perceivedSkill(Player target, Sighting s) {
        if (target == null) return 0.5;
        double armour = s == null ? 0 : s.armorSeen;
        double weapon = s == null ? 1 : s.weaponSeen;
        double cps = net.h2020.bots.ai.perception.PlayerStats.cps(target);
        double reach = net.h2020.bots.ai.perception.PlayerStats.recentReach(target);
        double skill = 0.15
                + 0.30 * clamp01(armour / 9.0)
                + 0.25 * clamp01((weapon - 1) / 7.0)
                + 0.20 * clamp01((cps - 4) / 10.0)
                + 0.10 * clamp01((reach - 2.6) / 1.0);
        return clamp01(skill);
    }

    private static double sigmoid(double z) {
        if (z < -30) return 0;
        if (z > 30) return 1;
        return 1.0 / (1.0 + Math.exp(-z));
    }

    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    private static double clampAbs(double v, double lim) { return v < -lim ? -lim : (v > lim ? lim : v); }

    private static int floor(double d) { int i = (int) d; return d < i ? i - 1 : i; }
}
