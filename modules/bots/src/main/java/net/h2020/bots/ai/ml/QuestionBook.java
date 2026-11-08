package net.h2020.bots.ai.ml;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The things a bot cannot see but a teammate can just tell it, asked as yes/no questions and learned twice over:
 * <ul>
 * <li>the answer itself becomes a belief about that player (in a generator, low health, diamond armour, running,
 *   fighting, bridging to mid / to our base / to theirs);</li>
 * <li>and the same answer trains a small predictor, so the bot can <i>guess</i> the hidden thing from what it can see
 *   (distance to a generator, standing still, sneaking, speed, recently hit) without asking at all.</li>
 * </ul>
 * Asking is constant while a real player is around: the question with the least certain belief (or the stalest one) is
 * picked each time, so the questions spread over everything the bot is unsure about instead of repeating one.
 */
public final class QuestionBook {
    public static final int IN_GENERATOR = 0, LOW_HP = 1, DIAMOND_ARMOUR = 2, HAS_SWORD = 3,
            BRIDGING_TO_MID = 4, BRIDGING_TO_ENEMY = 5, BRIDGING_TO_BASE = 6, RUNNING = 7, FIGHTING = 8,
            GATHERING_DIAMONDS = 9, GATHERING_EMERALDS = 10, DEFENDING = 11, SHOPPING = 12, HEALING = 13, BACKING_OFF = 14;
    public static final int FACTS = 15;
    public static final int N = BehaviourModel.N;

    private static final double[] W = new double[FACTS * N];
    private static long answers;
    private static File file;
    private static boolean dirty;

    /** Per player, per fact: belief 0..1 and when it was last answered (ms). */
    private static final Map<UUID, double[]> BELIEF = new HashMap<UUID, double[]>();
    private static final Map<UUID, long[]> ANSWERED = new HashMap<UUID, long[]>();
    /** Player -> {fact, deadline ms}: the question waiting for an answer. */
    private static final Map<UUID, Object[]> PENDING = new HashMap<UUID, Object[]>();

    private QuestionBook() {}

    public static void install(JavaPlugin plugin) {
        if (plugin == null) return;
        file = new File(plugin.getDataFolder(), "question-book.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() { if (dirty) save(); }
        }, 1900L, 1900L);
    }

    public static String name(int fact) {
        switch (fact) {
            case IN_GENERATOR: return "in a generator";
            case LOW_HP: return "low";
            case DIAMOND_ARMOUR: return "in diamond armour";
            case HAS_SWORD: return "holding a sword";
            case BRIDGING_TO_MID: return "bridging to mid";
            case BRIDGING_TO_ENEMY: return "bridging to their base";
            case BRIDGING_TO_BASE: return "bridging back";
            case RUNNING: return "running";
            case FIGHTING: return "fighting";
            case GATHERING_DIAMONDS: return "on diamonds";
            case GATHERING_EMERALDS: return "on emeralds";
            case DEFENDING: return "defending";
            case SHOPPING: return "shopping";
            case HEALING: return "healing";
            case BACKING_OFF: return "backing off";
            default: return "?";
        }
    }

    private static String question(int fact) {
        switch (fact) {
            case IN_GENERATOR: return "u in the gen? y/n";
            case LOW_HP: return "u low? y/n";
            case DIAMOND_ARMOUR: return "u diamond? y/n";
            case HAS_SWORD: return "got ur sword? y/n";
            case BRIDGING_TO_MID: return "bridging to mid? y/n";
            case BRIDGING_TO_ENEMY: return "bridging to their base? y/n";
            case BRIDGING_TO_BASE: return "bridging back? y/n";
            case RUNNING: return "u running rn? y/n";
            default: return "y/n?";
        }
    }

    // ------------------------------------------------------------------ asking

    /**
     * The next question for this player: the fact whose belief is least certain (and least recently asked). Constant -
     * one open question at a time, no rate limit.
     */
    public static String questionFor(Player p) {
        if (p == null) return null;
        Object[] open = PENDING.get(p.getUniqueId());
        if (open != null && (Long) open[1] > System.currentTimeMillis()) return null;
        double[] belief = BELIEF.get(p.getUniqueId());
        long[] at = ANSWERED.get(p.getUniqueId());
        int pick = -1;
        double worst = -1;
        long now = System.currentTimeMillis();
        for (int f = 0; f < FACTS; f++) {
            double b = belief == null ? 0.5 : belief[f];
            double stale = at == null ? 1.0 : Math.min(1.0, (now - at[f]) / 120000.0);
            double score = (1.0 - Math.abs(b - 0.5) * 2.0) + stale;     // unsure first, then stale
            if (score > worst) { worst = score; pick = f; }
        }
        if (pick < 0) return null;
        PENDING.put(p.getUniqueId(), new Object[]{pick, now + 6000});
        return question(pick);
    }

    public static boolean hasPending(Player p) {
        Object[] q = PENDING.get(p.getUniqueId());
        return q != null && (Long) q[1] > System.currentTimeMillis();
    }

    /** An answer to the open question. @return true when there was one. */
    public static boolean answer(Player p, boolean yes) {
        Object[] q = PENDING.remove(p.getUniqueId());
        if (q == null) return false;
        int fact = (Integer) q[0];
        double[] belief = BELIEF.get(p.getUniqueId());
        if (belief == null) { belief = new double[FACTS]; java.util.Arrays.fill(belief, 0.5); BELIEF.put(p.getUniqueId(), belief); }
        long[] at = ANSWERED.get(p.getUniqueId());
        if (at == null) { at = new long[FACTS]; }

        belief[fact] = belief[fact] * 0.25 + (yes ? 1 : 0) * 0.75;
        at[fact] = System.currentTimeMillis();
        ANSWERED.put(p.getUniqueId(), at);

        // and train the predictor so the bot can infer it next time without asking
        double[] x = BehaviourModel.featuresBefore(p, 1000);
        if (x != null) learn(fact, x, yes);
        return true;
    }

    /** A multiple-choice answer that settles a hidden fact directly. */
    public static void setBelief(Player p, int fact, boolean value) {
        double[] belief = BELIEF.get(p.getUniqueId());
        if (belief == null) { belief = new double[FACTS]; java.util.Arrays.fill(belief, 0.5); BELIEF.put(p.getUniqueId(), belief); }
        long[] at = ANSWERED.get(p.getUniqueId());
        if (at == null) { at = new long[FACTS]; }
        belief[fact] = belief[fact] * 0.2 + (value ? 1 : 0) * 0.8;
        at[fact] = System.currentTimeMillis();
        ANSWERED.put(p.getUniqueId(), at);
        double[] x = BehaviourModel.featuresBefore(p, 1000);
        if (x != null) learn(fact, x, value);
    }

    /** A classical check (not an answer) settled this fact: move the belief, do not train the predictor on it. */
    public static void setObserved(Player p, int fact, boolean value, double confidence) {
        if (p == null || fact < 0 || fact >= FACTS) return;
        double[] belief = BELIEF.get(p.getUniqueId());
        if (belief == null) { belief = new double[FACTS]; java.util.Arrays.fill(belief, 0.5); BELIEF.put(p.getUniqueId(), belief); }
        double target = value ? confidence : 1.0 - confidence;
        belief[fact] = belief[fact] * 0.3 + target * 0.7;
    }

    /** What the team currently believes about a hidden fact of a player (0.5 when never asked). */
    public static double belief(Player p, int fact) {
        double[] b = BELIEF.get(p.getUniqueId());
        return b == null ? 0.5 : b[fact];
    }

    /** The model's own guess at a fact from observable features (for players nobody has asked). */
    public static double infer(Player p, int fact) {
        double[] x = BehaviourModel.featuresBefore(p, 1000);
        if (x == null) return 0.5;
        return sigmoid(dot(fact, x));
    }

    private static void learn(int fact, double[] x, boolean yes) {
        double p = sigmoid(dot(fact, x));
        double y = yes ? 1 : 0;
        double lr = 0.06 / (1.0 + answers / 400.0);
        for (int i = 0; i < N; i++) W[fact * N + i] += lr * (y - p) * x[i];
        answers++;
        dirty = true;
    }

    private static double dot(int fact, double[] x) {
        double z = 0;
        int base = fact * N;
        for (int i = 0; i < N; i++) z += W[base + i] * x[i];
        return z;
    }

    private static double sigmoid(double z) {
        if (z < -30) return 0;
        if (z > 30) return 1;
        return 1.0 / (1.0 + Math.exp(-z));
    }

    public static List<String> report() {
        List<String> out = new ArrayList<String>();
        out.add(String.format("§6[ml] §eanswers: §f%d§7 yes/no answers learned, §f%d§7 questions open", answers, PENDING.size()));
        StringBuilder sb = new StringBuilder("§7facts the model now infers unaided (weight of the strongest cue):");
        for (int f = 0; f < FACTS; f++) {
            int best = -1;
            double bv = 0;
            for (int i = 1; i < N; i++) if (Math.abs(W[f * N + i]) > Math.abs(bv)) { bv = W[f * N + i]; best = i; }
            if (best < 0 || Math.abs(bv) < 0.05) continue;
            sb.append(String.format(" %s=%.2f", name(f), bv));
        }
        out.add(sb.toString());
        return out;
    }

    public static void reset() {
        for (int i = 0; i < W.length; i++) W[i] = 0;
        answers = 0;
        BELIEF.clear(); ANSWERED.clear(); PENDING.clear();
        dirty = true;
        save();
    }

    private static void load() {
        if (file == null || !file.exists()) return;
        try {
            org.bukkit.configuration.file.YamlConfiguration y =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            List<Double> w = y.getDoubleList("weights");
            for (int i = 0; i < Math.min(W.length, w.size()); i++) W[i] = w.get(i);
            answers = y.getLong("answers", 0);
        } catch (Throwable ignored) { }
    }

    public static void save() {
        if (file == null) return;
        try {
            org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
            List<Double> w = new ArrayList<Double>(W.length);
            for (double v : W) w.add(v);
            y.set("weights", w);
            y.set("answers", answers);
            if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
            y.save(file);
            dirty = false;
        } catch (Throwable ignored) { }
    }
}
