package net.h2020.bots.ai.ml;

import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.perception.PlayerStats;
import net.h2020.bots.ai.perception.Sighting;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Who is the bot going to fight next? A multi-class model over the enemies that exist as far as this bot knows them
 * (tab list plus what it has seen), scored from its own perspective: how close they are, whether they are closing on
 * us or on our bed, what they look like (gear, click speed), whether their bed is up, whether they are the last of
 * their team, how long since we last saw them, whether they are sitting in the base we rushed first, and how far they
 * are from mid.
 * <p>
 * Learned from what actually happens: every time the bot engages somebody, the prediction that was made that tick is
 * updated towards the enemy who really showed up (cross-entropy / softmax gradient). Teams that are dead are not
 * candidates at all - and when the team we rushed first is dead, the "sitting in the first rush base" term stops
 * mattering because nobody is left there.
 */
public final class NextOpponent {
    public static final int N = 14;
    private static final double LR = 0.05;
    private static final double[] W = new double[N];
    private static long samples;
    private static File file;
    private static boolean dirty;

    private NextOpponent() {}

    public static void install(JavaPlugin plugin) {
        if (plugin == null) return;
        file = new File(plugin.getDataFolder(), "next-opponent.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() { if (dirty) save(); }
        }, 1500L, 1500L);
    }

    /**
     * One candidate, as the bot sees them.
     * @param enemyPos where we think they are (their last known / extrapolated position)
     */
    public static double[] features(BotContext ctx, Player enemy, Location enemyPos, Location myBed,
                                    boolean lastOfTeam, boolean bedAlive, Location firstRushBase) {
        double[] x = new double[N];
        Location me = ctx.pos();
        Sighting s = ctx.perception.get(enemy);
        double d = enemyPos == null ? 64 : enemyPos.distance(me);
        x[0] = 1;
        x[1] = clamp(d / 64.0, 0, 1);
        // closing on us
        double closing = 0;
        if (s != null && enemyPos != null) {
            double ux = me.getX() - enemyPos.getX(), uz = me.getZ() - enemyPos.getZ();
            double l = Math.max(1e-3, Math.sqrt(ux * ux + uz * uz));
            closing = (s.lastVel.getX() * ux / l + s.lastVel.getZ() * uz / l);
        }
        x[2] = clamp(closing * 5, -1, 1);
        // heading at our bed
        double towardBed = 0;
        if (s != null && myBed != null && enemyPos != null) {
            double ux = myBed.getX() - enemyPos.getX(), uz = myBed.getZ() - enemyPos.getZ();
            double l = Math.max(1e-3, Math.sqrt(ux * ux + uz * uz));
            towardBed = (s.lastVel.getX() * ux / l + s.lastVel.getZ() * uz / l);
        }
        x[3] = clamp(towardBed * 4, -1, 1);
        // how good they look
        double skill = 0.3;
        if (s != null) {
            skill = clamp(0.15 + 0.25 * (s.armorSeen / 9.0) + 0.2 * ((s.weaponSeen - 1) / 7.0)
                    + 0.1 * clamp(PlayerStats.cps(enemy) / 12.0, 0, 1), 0, 1);
        }
        x[4] = skill;
        x[5] = bedAlive ? 1 : 0;
        x[6] = lastOfTeam ? 1 : 0;
        x[7] = s == null ? 1 : clamp(s.unseenSeconds(ctx.tick) / 30.0, 0, 1);
        // are they sitting where we rushed first (or were rushed)?
        double rushBase = 0;
        if (firstRushBase != null && enemyPos != null && firstRushBase.getWorld() == enemyPos.getWorld()) {
            rushBase = enemyPos.distance(firstRushBase) < 20 ? 1 : 0;
        }
        x[8] = rushBase;
        // recently fighting (somebody hit somebody near them)
        x[9] = s == null ? 0 : clamp((ctx.tick - s.lastHitByMeTick) < 100 ? 1 : 0, 0, 1);
        x[10] = enemy == null ? 0.5 : clamp(1 - enemyPos.distance(me) / 100.0, 0, 1);
        // what they are wearing, how hurt they look, and whether they own the high ground
        x[11] = s == null ? 0 : clamp(s.armorSeen / 9.0, 0, 1);
        x[12] = enemy == null ? 0.5 : clamp(enemy.getHealth() / 20.0, 0, 1);
        x[13] = enemyPos == null ? 0.5 : clamp((enemyPos.getY() - me.getY()) / 4.0 + 0.5, 0, 1);
        return x;
    }

    /** Softmax over the candidates' scores. */
    public static double[] probabilities(double[][] feats) {
        double[] p = new double[feats.length];
        if (feats.length == 0) return p;
        double max = -1e9;
        for (double[] x : feats) max = Math.max(max, dot(x));
        double sum = 0;
        for (int i = 0; i < feats.length; i++) { p[i] = Math.exp((dot(feats[i]) - max) / 0.7); sum += p[i]; }
        for (int i = 0; i < p.length; i++) p[i] /= sum;
        return p;
    }

    /** The enemy the bot expects to meet next (index into the candidates), or -1. */
    public static int best(double[][] feats) {
        double[] p = probabilities(feats);
        int best = -1;
        double bp = -1;
        for (int i = 0; i < p.length; i++) if (p[i] > bp) { bp = p[i]; best = i; }
        return best;
    }

    /** The enemy who actually showed up: move the model towards them. */
    public static void observe(double[][] feats, int chosen) {
        if (feats == null || chosen < 0 || chosen >= feats.length) return;
        double[] p = probabilities(feats);
        double lr = LR / (1.0 + samples / 400.0);
        for (int i = 0; i < feats.length; i++) {
            double ind = i == chosen ? 1 : 0;
            double g = ind - p[i];
            for (int j = 0; j < N; j++) W[j] += lr * g * feats[i][j];
        }
        samples++;
        dirty = true;
    }

    private static double dot(double[] x) {
        double z = 0;
        for (int i = 0; i < N; i++) z += W[i] * x[i];
        return z;
    }

    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }

    public static List<String> report() {
        List<String> out = new ArrayList<String>();
        out.add(String.format("§6[ml] §enext opponent: §f%d§7 updates; distance %.2f, closing %.2f, bed-bound %.2f, skill %.2f, lastOfTeam %.2f, rushBase %.2f",
                samples, W[1], W[2], W[3], W[4], W[6], W[8]));
        return out;
    }

    public static void reset() {
        for (int i = 0; i < N; i++) W[i] = 0;
        samples = 0;
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
                net.h2020.api.stats.CombatStats.inc("ml.nextopponent.reset");
                return;
            }
            for (int i = 0; i < Math.min(N, w.size()); i++) W[i] = w.get(i);
            samples = y.getLong("samples", 0);
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
            y.set("samples", samples);
            if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
            y.save(file);
            dirty = false;
        } catch (Throwable ignored) { }
    }
}
