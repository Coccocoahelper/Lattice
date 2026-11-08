package net.h2020.bots.ai.ml;

import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.inv.Inv;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Which job a bot should take on the team, learned from whether the job it took actually worked out.
 * <p>
 * The hand-written role scores stay as the prior (aggression decides who rushes, the passive ones defend), and this
 * model learns the correction: every bot's role decision is recorded with the state it was made in, and when the first
 * task of that role ends well (the rush broke a bed, the collect filled, the defence got blocks down) the role gains
 * score for that kind of team and situation, and loses it when the job fell over. Over a session the teams arrange
 * themselves by what has been working, not only by personality.
 */
public final class RoleModel {
    public static final int RUSHER = 0, FARMER = 1, DEFENDER = 2;
    public static final int ACTIONS = 3;
    public static final int N = 9;

    private static final double LR = 0.07;
    private static final double[] W = new double[ACTIONS * N];
    private static double baseline;
    private static long decisions, rewards;
    private static File file;
    private static boolean dirty;
    private static double[] lastX;
    private static int lastRole = -1;

    private RoleModel() {}

    public static void install(JavaPlugin p) {
        if (p == null) return;
        file = new File(p.getDataFolder(), "role-model.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(p, new Runnable() {
            @Override public void run() { if (dirty) save(); }
        }, 2000L, 2000L);
    }

    public static double[] features(BotContext ctx, int teamSize, int teammateCount, boolean bedAlive, int gameSeconds) {
        double[] x = new double[N];
        x[0] = 1;
        x[1] = ctx.persona.skill.aggression;
        x[2] = ctx.persona.skill.overall;
        x[3] = ctx.persona.tryhard ? 1 : 0;
        x[4] = Math.min(1, Inv.count(ctx.player, Inv.BLOCK) / 64.0);
        x[5] = Math.max(0, ctx.health()) / 20.0;
        x[6] = Math.min(1, teamSize / 4.0);
        x[7] = Math.min(1, teammateCount / 3.0);
        x[8] = bedAlive ? 1 : 0;
        return x;
    }

    /** Pick a role: the prior scores plus the learned correction. */
    public static int choose(BotContext ctx, double[] prior, double[] x) {
        int best = RUSHER;
        double bv = -1e9;
        for (int a = 0; a < ACTIONS; a++) {
            double s = prior[a] + 0.4 * dot(a, x);
            if (s > bv) { bv = s; best = a; }
        }
        lastX = x;
        lastRole = best;
        decisions++;
        return best;
    }

    /** The first task of that role is over: did the job work? */
    public static void reward(boolean ok) {
        if (lastRole < 0 || lastX == null) return;
        double r = ok ? 1 : -0.6;
        double adv = r - baseline;
        baseline = 0.97 * baseline + 0.03 * r;
        double lr = LR / (1.0 + rewards / 400.0);
        int base = lastRole * N;
        for (int i = 0; i < N; i++) W[base + i] += lr * adv * lastX[i];
        rewards++;
        lastRole = -1;
        dirty = true;
    }

    public static String name(int r) {
        return r == RUSHER ? "rusher" : r == FARMER ? "farmer" : "defender";
    }

    private static double dot(int a, double[] x) {
        double z = 0;
        int base = a * N;
        for (int i = 0; i < N; i++) z += W[base + i] * x[i];
        return z;
    }

    public static List<String> report() {
        List<String> out = new ArrayList<String>();
        out.add(String.format("§6[ml] §eroles: §f%d§7 decisions, §f%d§7 rewarded, baseline §f%+.2f", decisions, rewards, baseline));
        out.add(String.format("§7role preference: rusher %+.2f, farmer %+.2f, defender %+.2f",
                W[RUSHER * N], W[FARMER * N], W[DEFENDER * N]));
        return out;
    }

    public static void reset() {
        for (int i = 0; i < W.length; i++) W[i] = 0;
        baseline = 0;
        decisions = 0;
        rewards = 0;
        lastRole = -1;
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
            y.set("baseline", baseline);
            y.set("decisions", decisions);
            y.set("rewards", rewards);
            if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
            y.save(file);
            dirty = false;
        } catch (Throwable ignored) { }
    }
}
