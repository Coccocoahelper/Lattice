package net.h2020.bots.ai.ml;

import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.BotContext;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Where to aim: a four-armed bandit over the places players actually aim in a 1.8 duel, rewarded by whether the sword
 * connects. The arms are aim profiles, not coordinates - the human aim model (HumanMouse) still does the mouse work,
 * with its reaction time, overshoot and per-tick error; the arm only moves the point it is heading for and how much
 * lead it takes:
 * <ul>
 * <li>head / leading - high on the hitbox, big lead: what a sweat does against somebody strafing;</li>
 * <li>chest - the default, middle of the hitbox;</li>
 * <li>low torso - slightly low, small lead: reliable against somebody walking straight in;</li>
 * <li>feet - lowest point of the hitbox: knockback hits, the usual answer to a jumper.</li>
 * </ul>
 * The reward is the hit rate of the arm (hits landed / clicks during the fight), so it learns which aim point connects
 * for this bot's skill and ping without ever teaching it to aim perfectly.
 */
public final class AimPolicy {
    public static final int ARMS = 4;

    private static final double[] FRAC_BIAS = {0.14, 0.0, -0.10, -0.42};
    private static final double[] LEAD_SCALE = {1.6, 1.0, 0.9, 0.6};
    private static final String[] NAMES = {"head+lead", "chest", "low", "feet"};

    private static final int[] PULLS = new int[ARMS];
    private static final double[] RATE = new double[ARMS];
    /** Hit rate split by range: an aim profile that only ever fights at one distance must not look good everywhere. */
    private static final double[][] RATE_BY_RANGE = new double[ARMS][3];
    private static final int[][] PULLS_BY_RANGE = new int[ARMS][3];
    private static long fights;
    private static File file;
    private static boolean dirty;
    private static final java.util.Random R = new java.util.Random();

    private AimPolicy() {}

    public static void install(JavaPlugin plugin) {
        if (plugin == null) return;
        file = new File(plugin.getDataFolder(), "aim-policy.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() { if (dirty) save(); }
        }, 1600L, 1600L);
    }

    static { for (int i = 0; i < ARMS; i++) RATE[i] = 0.55; }

    /** Pick the aim profile for a fight: unexplored arms first, then the best arm with skill-dependent noise. */
    public static int choose(BotContext ctx) {
        for (int i = 0; i < ARMS; i++) if (PULLS[i] < 3) return i;
        double temp = 0.02 + 0.10 * (1 - ctx.persona.skill.overall);
        double sum = 0;
        double[] s = new double[ARMS];
        for (int i = 0; i < ARMS; i++) { s[i] = Math.exp(RATE[i] / temp); sum += s[i]; }
        double r = R.nextDouble() * sum;
        for (int i = 0; i < ARMS; i++) { r -= s[i]; if (r <= 0) return i; }
        return 1;
    }

    /** One fight's worth of evidence for the arm that was in use. */
    public static void record(int arm, int hits, int swings) { record(arm, hits, swings, 4.0); }

    /**
     * Same, with the range the fight was fought at. Two changes to the reward:
     * <ul>
     * <li><b>misses cost something.</b> The old signal was hits/swings, so an arm that swings once and hits once scored
     * 100% and an arm that applies constant pressure scored less for the same damage. Swings that never land now
     * subtract a third of a hit, which is what a wasted click actually costs (cooldown + a free hit for them).</li>
     * <li><b>range is part of the state.</b> An arm used only at point blank looked better than one that was trusted
     * with the long range fights; the rates are kept per range band so the comparison is like for like.</li>
     * </ul>
     */
    public static void record(int arm, int hits, int swings, double dist) {
        if (arm < 0 || arm >= ARMS || swings < 3) return;
        double rate = Math.max(0, Math.min(1, (hits - 0.33 * (swings - hits)) / (double) swings));
        RATE[arm] = PULLS[arm] == 0 ? rate : RATE[arm] * 0.85 + rate * 0.15;
        PULLS[arm]++;
        int b = dist < 2.5 ? 0 : (dist < 4.5 ? 1 : 2);
        RATE_BY_RANGE[arm][b] = PULLS_BY_RANGE[arm][b] == 0 ? rate
                : RATE_BY_RANGE[arm][b] * 0.85 + rate * 0.15;
        PULLS_BY_RANGE[arm][b]++;
        if (swings > hits) net.h2020.api.stats.CombatStats.add("ml.aim.missed", swings - hits);
        fights++;
        dirty = true;
    }

    public static double fracBias(int arm) { return arm >= 0 && arm < ARMS ? FRAC_BIAS[arm] : 0; }

    public static double leadScale(int arm) { return arm >= 0 && arm < ARMS ? LEAD_SCALE[arm] : 1.0; }

    public static List<String> report() {
        List<String> out = new ArrayList<String>();
        out.add(String.format("§6[ml] §eaim: §f%d§7 fights scored", fights));
        StringBuilder sb = new StringBuilder("§7hit rate per aim profile:");
        for (int i = 0; i < ARMS; i++) sb.append(String.format(" %s=%.0f%%(%d)", NAMES[i], RATE[i] * 100, PULLS[i]));
        out.add(sb.toString());
        StringBuilder rb = new StringBuilder("§7by range (close/mid/far):");
        for (int i = 0; i < ARMS; i++) {
            rb.append(" ").append(NAMES[i]).append("=");
            for (int b = 0; b < 3; b++) rb.append(PULLS_BY_RANGE[i][b] == 0 ? "-" : String.format("%.0f%%", RATE_BY_RANGE[i][b] * 100)).append("/");
        }
        out.add(rb.toString());
        return out;
    }

    public static void reset() {
        for (int i = 0; i < ARMS; i++) { PULLS[i] = 0; RATE[i] = 0.55; }
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
            for (int i = 0; i < ARMS; i++) {
                PULLS[i] = y.getInt("arm" + i + ".pulls", 0);
                RATE[i] = y.getDouble("arm" + i + ".rate", 0.55);
            }
            fights = y.getLong("fights", 0);
        } catch (Throwable ignored) { }
    }

    public static void save() {
        if (file == null) return;
        try {
            org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
            for (int i = 0; i < ARMS; i++) {
                y.set("arm" + i + ".pulls", PULLS[i]);
                y.set("arm" + i + ".rate", RATE[i]);
                y.set("arm" + i + ".name", NAMES[i]);
            }
            y.set("fights", fights);
            if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
            y.save(file);
            dirty = false;
        } catch (Throwable ignored) { }
    }
}
