package net.h2020.bots.ai.ml;

import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.combat.Style;
import net.h2020.bots.ai.inv.Inv;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Which block placement to make, learned from the fights it wins.
 * <p>
 * A softmax policy over the eight things a 1.8 player can do with blocks in a duel (nothing, stomp, 2-block pillar,
 * middle exit, head hitter, tower, bridge wall, bridge cut). The score of each action is its prior - the persona's
 * habit, i.e. what that player would do anyway - plus a learned correction from the context the fight is in.
 * <p>
 * Learning is REINFORCE with a baseline: every decision is recorded with the distribution it was sampled from, and
 * when the fight ends the chosen action is credited or blamed by the fight result against the running average. A
 * placement that keeps showing up in lost fights loses its score in that kind of fight, one that keeps showing up in
 * won fights gains it. Nothing about the geometry is learned - whether a stomp is even possible stays a physics check
 * in BlockTactics; this only decides what to try among the things that are possible.
 */
public final class PlacementPolicy {
    public static final int NONE = 0, STOMP = 1, DUEL_PILLAR = 2, MIDDLE_EXIT = 3, HEAD_HITTER = 4, TOWER = 5,
            WALL = 6, CUT = 7, SHELTER = 8, BACKSTEP = 9, RAMP = 10, DRIVE_THRU = 11, BED_BLOCKOFF = 12;
    public static final int ACTIONS = 13;
    public static final int N = 18;

    private static final double LR = 0.06;
    private static final double[] W = new double[ACTIONS * N];
    private static double baseline = 0.5;
    private static long decisions, fights;
    private static File file;
    private static boolean dirty;

    /** Decisions taken in the current fight: {action, probability it had, features...}. */
    private static final List<double[]> PENDING = new ArrayList<double[]>();
    /** Blocks this bot placed and when: if one is taken down within three seconds the placement was a bad one. */
    private static final java.util.Map<Long, Long> PLACED_AT = new java.util.HashMap<Long, Long>();
    private static int minedFast;

    private PlacementPolicy() {}

    public static void install(JavaPlugin plugin) {
        if (plugin == null) return;
        file = new File(plugin.getDataFolder(), "placement-policy.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() { if (dirty) save(); }
        }, 1400L, 1400L);
    }

    /** The context of one decision, in the order the weights expect it. */
    public static double[] features(BotContext ctx, Style style, Player target, double d, boolean onLine, boolean losing, double pWin) {
        double[] x = new double[N];
        Player me = ctx.player;
        double myHp = Math.max(0, me.getHealth()), theirHp = target == null ? 20 : Math.max(0, target.getHealth());
        x[0] = 1;
        x[1] = Math.min(1, myHp / 20.0);
        x[2] = Math.min(1, theirHp / 20.0);
        x[3] = clamp((myHp - theirHp) / 20.0, -1, 1);
        x[4] = clamp(d / 8.0, 0, 1);
        x[5] = onLine ? 1 : 0;
        x[6] = Math.min(1, Inv.count(me, Inv.BLOCK) / 64.0);
        x[7] = ctx.persona.skill.overall;
        x[8] = losing ? 1 : 0;
        x[9] = clamp((pWin - 0.5) * 2, -1, 1);
        x[10] = target != null && !target.isOnGround() ? 1 : 0;
        x[11] = ctx.c.onGround() ? 1 : 0;
        x[12] = style.bridgeBlock ? 1 : 0;
        x[13] = ctx.persona.tryhard ? 1 : 0;
        // height, exposure, what they can dig with, and whether they can answer with blocks: the four things that
        // decide whether a wall is worth two blocks or a waste of the stack
        x[14] = target == null ? 0 : clamp((target.getLocation().getY() - me.getLocation().getY()) / 3.0, -1, 1);
        x[15] = Math.min(1, ctx.world().voidSides((int) Math.floor(me.getLocation().getX()),
                (int) Math.floor(me.getLocation().getY() + 0.01), (int) Math.floor(me.getLocation().getZ())) / 4.0);
        x[16] = target != null && Inv.has(target, Inv.PICKAXE) ? 1 : 0;
        x[17] = target == null ? 0 : Math.min(1, Inv.count(target, Inv.BLOCK) / 64.0);
        return x;
    }

    /** The persona's habits: the score the action starts with before any learning. */
    private static double prior(Style style, int action, double d, boolean onLine, boolean losing, double hp) {
        switch (action) {
            case STOMP: return style.stomp * 1.1;
            case DUEL_PILLAR: return onLine ? -1 : style.duelPillar * 0.8;
            case MIDDLE_EXIT: return onLine ? style.middleExit * 1.0 : -1;
            case HEAD_HITTER: return style.headHitter * 0.5;
            case TOWER: return (losing && hp <= 8 && !onLine) ? 0.7 : -0.6;
            case WALL: return onLine && style.bridgeBlock ? 0.6 : -1;
            case CUT: return onLine && style.bridgeBlock ? 0.45 : -1;
            case SHELTER: return losing || hp <= 10 ? 0.4 : 0.1;          // box up when the trade is going badly
            case BACKSTEP: return losing ? 0.35 : 0.05;                   // block the chaser while backing off
            case RAMP: return d > 2.6 && d < 5.5 ? 0.3 : -0.2;            // a step up for high ground in a fight
            default: return 0.25;                       // doing nothing is always an option
        }
    }

    /**
     * Rank the actions for this moment, highest score first: the caller tries them in order and keeps the first one
     * that is physically possible. The features used are remembered for the learning update.
     */
    public static int[] rank(BotContext ctx, Style style, Player target, double d, boolean onLine, boolean losing, double pWin) {
        double[] x = features(ctx, style, target, d, onLine, losing, pWin);
        double[] score = new double[ACTIONS];
        double hp = ctx.health();
        // what a person does in this exact state is the prior the bot's own learning adjusts; it never overrides a
        // placement that is physically impossible here (those keep their negative priors)
        double[] human = HumanPlacements.state(ctx.player, target);
        for (int a = 0; a < ACTIONS; a++) {
            score[a] = prior(style, a, d, onLine, losing, hp) + dot(a, x);
            if (a == BED_BLOCKOFF) score[a] = -1e9;   // driven by the bed raid, never by the duel placement policy
            int h = humanClass(a);
            if (h >= 0) score[a] += 0.55 * HumanPlacements.prob(h, human);
        }
        // experimentation: the bandit has to try what it has not tried, weighted by how rarely each arm was used, so
        // a new placement type gets discovered instead of the policy settling on the three it already knows
        for (int a = 0; a < ACTIONS; a++) {
            if (score[a] > 0.02) score[a] += 0.9 / (1.0 + TRIES[a]);
        }
        double explore = 0.32 - 0.20 * ctx.persona.skill.overall;
        if (ctx.rng.nextDouble() < explore) {
            int pick = -1, seen = 0;
            for (int a = 0; a < ACTIONS; a++) {
                if (score[a] <= 0.02) continue;
                seen++;
                if (ctx.rng.nextDouble() < 1.0 / seen) pick = a;
            }
            if (pick >= 0 && score[pick] > 0.02) score[pick] += 10.0;      // force it to the front of the ranking
        }
        int[] order = new int[ACTIONS];
        for (int i = 0; i < ACTIONS; i++) order[i] = i;
        for (int i = 1; i < ACTIONS; i++) {
            int v = order[i], j = i - 1;
            while (j >= 0 && score[order[j]] < score[v]) { order[j + 1] = order[j]; j--; }
            order[j + 1] = v;
        }
        TRIES[order[0]] += 1;
        LAST_X = x;
        decisions++;
        return order;
    }

    /** The human model's class for each placement action (-1 when people cannot teach us that one). */
    private static int humanClass(int action) {
        switch (action) {
            case STOMP: return HumanPlacements.STOMP;
            case DUEL_PILLAR: return HumanPlacements.PILLAR;
            case WALL: return HumanPlacements.WALL;
            case HEAD_HITTER: return HumanPlacements.HEAD;
            case CUT: return HumanPlacements.FLOOR;
            default: return -1;                          // middle exit / tower are not observable from a human's block
        }
    }

    /** How often each arm has been tried, for the novelty term. */
    private static final double[] TRIES = new double[ACTIONS];

    private static double[] LAST_X;
    /** How quickly the last decision played out (1 = fast placement, 0 = slow). */
    private static double lastSpeed = 0.5;

    /**
     * The action that actually started, at `chosenIndex` in the ranking: remember it with the probability its rank
     * carried, for the REINFORCE update when the fight ends.
     */
    public static void record(int[] ranking, int chosenIndex) {
        if (ranking == null || LAST_X == null || ranking.length == 0) return;
        int idx = Math.max(0, Math.min(ranking.length - 1, chosenIndex));
        double[] p = rankProbabilities(ranking.length, 1.0);
        double[] u = new double[N + 3];
        u[0] = ranking[idx];
        u[1] = p[idx];
        System.arraycopy(LAST_X, 0, u, 2, N);
        u[N + 2] = lastSpeed;
        lastSpeed = 0.5;
        PENDING.add(u);
        dirty = true;
    }

    /** Softmax over rank positions with temperature T. */
    private static double[] rankProbabilities(int n, double t) {
        double[] raw = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) { raw[i] = Math.exp(-i / t); sum += raw[i]; }
        for (int i = 0; i < n; i++) raw[i] /= sum;
        return raw;
    }

    /**
     * Fight over: credit (or blame) the actions taken in it by the result against the running baseline. Only the
     * chosen action is moved - the ones that were never tried may simply have been impossible from where we stood.
     */
    /** A placement went down: remember where, so a fast break can be blamed on it. */
    public static void notePlaced(int x, int y, int z, long nowMs) {
        if (PLACED_AT.size() > 96) {
            java.util.Iterator<java.util.Map.Entry<Long, Long>> it = PLACED_AT.entrySet().iterator();
            while (it.hasNext()) if (nowMs - it.next().getValue() > 20_000L) it.remove();
        }
        PLACED_AT.put(((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF), nowMs);
        net.h2020.api.stats.CombatStats.inc("ml.place.tracked");
    }

    /** Somebody broke a block; if it was ours and recent, that placement failed at its job. */
    public static void noteBroken(int x, int y, int z, long nowMs) {
        Long at = PLACED_AT.remove(((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF));
        if (at != null) net.h2020.api.stats.CombatStats.inc("ml.place.broken");
        if (at != null && nowMs - at <= 3000L) {
            minedFast++;
            net.h2020.api.stats.CombatStats.inc("ml.place.mined");
        }
    }

    public static void finish(boolean win) {
        fights++;
        double y = win ? 1 : 0;
        double adv = y - baseline;
        baseline = 0.97 * baseline + 0.03 * y;
        double lr = LR / (1.0 + fights / 300.0);
        // a block that was taken down within three seconds did not do its job, whatever the fight ended up doing
        double minedSc = -0.25 * Math.min(3, minedFast);
        for (double[] u : PENDING) {
            int a = (int) u[0];
            double prob = u[1];
            double speed = u[N + 2];
            double sc = (adv + minedSc) * (1.0 + 0.25 * speed);   // a fast placement is worth more than a slow one
            for (int i = 0; i < N; i++) W[a * N + i] += lr * sc * (1 - prob) * u[2 + i];
        }
        PENDING.clear();
        minedFast = 0;
        dirty = true;
    }

    /** The placement that was started finished in `ticks` ticks: remember how quick it was. */
    public static void completed(int ticks) {
        lastSpeed = clamp(1.0 - ticks / 80.0, 0, 1);
    }

    /** The fight ended without a result (both walked away): nothing to learn from it. */
    public static void discard() {
        PENDING.clear();
    }

    private static double dot(int action, double[] x) {
        double z = 0;
        int base = action * N;
        for (int i = 0; i < N; i++) z += W[base + i] * x[i];
        return z;
    }

    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }

    /** Re-read this model from disk (a training generation was restored). */
    public static void reload() { load(); }

    private static void load() {
        if (file == null || !file.exists()) return;
        try {
            org.bukkit.configuration.file.YamlConfiguration y =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            List<Double> w = y.getDoubleList("weights");
            if (y.getInt("n", 0) != N) {   // feature vector changed shape: the old weights mean nothing here
                net.h2020.api.stats.CombatStats.inc("ml.placementpolicy.reset");
                return;
            }
            for (int i = 0; i < Math.min(W.length, w.size()); i++) W[i] = w.get(i);
            baseline = y.getDouble("baseline", 0.5);
            fights = y.getLong("fights", 0);
            decisions = y.getLong("decisions", 0);
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
            y.set("fights", fights);
            y.set("decisions", decisions);
            if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
            y.save(file);
            dirty = false;
        } catch (Throwable ignored) { }
    }

    public static void reset() {
        for (int i = 0; i < W.length; i++) W[i] = 0;
        baseline = 0.5;
        fights = 0;
        decisions = 0;
        PENDING.clear();
        dirty = true;
        save();
    }

    public static List<String> report() {
        String[] names = {"none", "stomp", "pillar2", "midExit", "headHit", "tower", "wall", "cut", "shelter", "backstep", "ramp"};
        List<String> out = new ArrayList<String>();
        out.add(String.format("§6[ml] §eplacements: §f%d§7 decisions, §f%d§7 fights scored, win baseline §f%.0f%%",
                decisions, fights, baseline * 100));
        StringBuilder sb = new StringBuilder("§7per action, the strongest learned terms:");
        for (int a = 0; a < ACTIONS; a++) {
            int best = -1;
            double bv = 0;
            for (int i = 1; i < N; i++) {
                double v = W[a * N + i];
                if (Math.abs(v) > Math.abs(bv)) { bv = v; best = i; }
            }
            if (best < 0 || Math.abs(bv) < 0.02) continue;
            sb.append(String.format(" %s:%s%.2f", names[Math.min(a, names.length - 1)], featureName(best), bv));
        }
        out.add(sb.toString());
        return out;
    }

    private static String featureName(int i) {
        switch (i) {
            case 1: return "hp";
            case 2: return "theirHp";
            case 3: return "hpDiff";
            case 4: return "dist";
            case 5: return "onLine";
            case 6: return "blocks";
            case 7: return "skill";
            case 8: return "losing";
            case 9: return "pWin";
            case 10: return "theyAir";
            case 11: return "ground";
            case 12: return "bridgeBlock";
            case 13: return "tryhard";
            default: return "x" + i;
        }
    }
}
