package net.h2020.bots.ai.ml;

import net.h2020.api.stats.CombatStats;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Why a bot did what it did, and what it cost or earned.
 *
 * <p>The learning models all share one weakness that no amount of reward tuning fixes: they attribute an outcome to
 * <em>the</em> last action, and they are static, so with a dozen bots on the server the reward lands on whichever bot
 * happened to decide most recently. A macro decision made by one bot could be credited to another bot's rush three
 * seconds later. That is not attribution, it is noise with a plausible label.
 *
 * <p>This class is the bookkeeping layer that fixes it. Every decision is recorded against the body that made it, with
 * the features it was made on, and closed when the outcome arrives - which may be many seconds later, after a fight,
 * a death or a bed break. A record carries:
 *
 * <ul>
 *   <li><b>who</b> - the bot, so two bots never share a credit</li>
 *   <li><b>what</b> - the policy, the action index, the action's name</li>
 *   <li><b>why</b> - the features and the top contributing terms, so a decision can be explained afterwards</li>
 *   <li><b>how it went</b> - the reward, and the events that landed on it (a kill, a death, a bed, a timeout)</li>
 * </ul>
 *
 * <p>Three things come out of it that the models could not answer before:
 *
 * <ol>
 *   <li><b>Per-bot regret.</b> For a closed decision the alternatives' scores are known, so the class can say what the
 *       best action <em>would</em> have been and by how much the chosen one lost. That is the "what could have been
 *       improved" question answered with a number rather than an anecdote.</li>
 *   <li><b>Credit separation.</b> An outcome is only ever applied to the decision that was open for the body it
 *       happened to.</li>
 *   <li><b>A trail.</b> The last N decisions per bot are kept, so {@code /bot why} can show the run-up to a death
 *       instead of one line.</li>
 * </ol>
 */
public final class Attribution {

    /** One decision, open until something closes it. */
    public static final class Decision {
        public final long id;
        public final UUID bot;
        public final String botName;
        public final String policy;
        public final int action;
        public final String actionName;
        public final double[] features;
        public final String[] featureNames;
        /** Score of every option at decision time, so regret is computable later. */
        public final double[] scores;
        public final String[] optionNames;
        /** The weights behind the chosen option, so a reason can be a contribution rather than a raw feature value. */
        public final double[] weights;
        public final long tick;
        public final long wallMs;
        public final Location where;

        // filled in when the outcome arrives
        long closedAtTick;
        double reward;
        String outcome = "open";
        final List<String> events = new ArrayList<String>();

        Decision(long id, Player p, String policy, int action, String actionName, double[] features,
                 String[] featureNames, double[] scores, String[] optionNames, double[] weights,
                 long tick, Location where) {
            this.weights = weights;
            this.id = id;
            this.bot = p.getUniqueId();
            this.botName = p.getName();
            this.policy = policy;
            this.action = action;
            this.actionName = actionName;
            this.features = features;
            this.featureNames = featureNames;
            this.scores = scores;
            this.optionNames = optionNames;
            this.tick = tick;
            this.wallMs = System.currentTimeMillis();
            this.where = where;
        }

        public boolean open() { return closedAtTick == 0; }

        /** The action that scored highest, and how much the one taken lost by. The core of "what could be better". */
        public String regret() {
            if (scores == null || optionNames == null || action < 0 || action >= scores.length) return "-";
            int best = 0;
            for (int i = 1; i < scores.length; i++) if (scores[i] > scores[best]) best = i;
            // second place matters even when the choice was right: a decision that won by 0.01 was a coin flip,
            // and that is exactly the thing worth reporting back to the model
            int second = -1;
            for (int i = 0; i < scores.length; i++) if (i != action && (second < 0 || scores[i] > scores[second])) second = i;
            if (best == action) {
                if (second < 0) return "chosen was best";
                double margin = scores[action] - scores[second];
                return String.format(Locale.ROOT, "best, ahead of %s by %.2f", optionNames[second], margin);
            }
            double loss = scores[best] - scores[action];
            return String.format(Locale.ROOT, "missed %s by %.2f", optionNames[best], loss);
        }

        /**
         * The three features that pushed the chosen action hardest, for a decision you have to explain.
         *
         * <p>Ranked by the term's signed contribution (weight x feature) when weights are available, because raw
         * feature magnitude is useless as an explanation: the bias term is always 1.0 and always ranked first, so
         * every decision carried the same "because bias=1.00". A feature only explains a choice if it moved the
         * score, and by how much.
         */
        public String because() {
            if (features == null) return "-";
            int n = features.length;
            final double[] pull = new double[n];
            boolean haveWeights = weights != null && weights.length >= n;
            for (int i = 0; i < n; i++) {
                pull[i] = haveWeights ? weights[i] * features[i] : features[i];
                if (i == 0 && haveWeights) pull[i] = 0;      // the bias is not a reason
            }
            List<Integer> idx = new ArrayList<Integer>();
            for (int i = 0; i < n; i++) idx.add(i);
            idx.sort(new Comparator<Integer>() {
                @Override public int compare(Integer a, Integer b) {
                    return Double.compare(Math.abs(pull[b]), Math.abs(pull[a]));
                }
            });
            StringBuilder b = new StringBuilder();
            for (int k = 0; k < Math.min(3, idx.size()); k++) {
                int i = idx.get(k);
                if (Math.abs(pull[i]) < 1e-6) continue;
                String nm = featureNames != null && i < featureNames.length ? featureNames[i] : ("x" + i);
                if (b.length() > 0) b.append(", ");
                b.append(nm).append('=').append(String.format(Locale.ROOT, "%+.2f", features[i]))
                        .append(" (").append(String.format(Locale.ROOT, "%+.2f", pull[i])).append(')');
            }
            return b.length() == 0 ? "no term moved the score" : b.toString();
        }

        public String line() {
            StringBuilder b = new StringBuilder();
            b.append("#").append(id).append(' ').append(policy).append(' ').append(actionName);
            b.append(" [").append(outcome).append(" r=").append(String.format(Locale.ROOT, "%.2f", reward)).append(']');
            b.append(" - ").append(regret());
            if (!events.isEmpty()) b.append(" events=").append(events);
            return b.toString();
        }
    }

    /** Decisions per bot, newest last, capped so a long session cannot grow without bound. */
    private static final int KEEP = 24;
    private static final int GLOBAL_KEEP = 400;
    private static final Map<UUID, Deque<Decision>> BY_BOT = new HashMap<UUID, Deque<Decision>>();
    /** The decision each bot currently has open, one per policy. */
    private static final Map<UUID, Map<String, Decision>> OPEN = new HashMap<UUID, Map<String, Decision>>();
    private static final Deque<Decision> RECENT = new ArrayDeque<Decision>();
    private static long seq;
    private static File file;
    private static boolean logging;

    private Attribution() {}

    public static void install(org.bukkit.plugin.java.JavaPlugin plugin) {
        if (plugin == null) return;
        file = new File(plugin.getDataFolder(), "attribution.log");
        logging = true;
    }

    /**
     * Record a decision. Replaces any open decision for this bot and policy - a macro policy that re-decides has
     * abandoned its previous plan, and keeping both open would let one outcome close two records.
     */
    public static Decision decide(Player p, String policy, int action, String actionName, double[] features,
                                 String[] featureNames, double[] scores, String[] optionNames, long tick) {
        return decide(p, policy, action, actionName, features, featureNames, scores, optionNames, null, tick);
    }

    /** As above, with the chosen option's weights so the explanation can rank by contribution. */
    public static Decision decide(Player p, String policy, int action, String actionName, double[] features,
                                 String[] featureNames, double[] scores, String[] optionNames, double[] weights,
                                 long tick) {
        if (p == null) return null;
        Decision d = new Decision(++seq, p, policy, action, actionName, features, featureNames, scores, optionNames,
                weights, tick, p.getLocation());
        Map<String, Decision> open = OPEN.get(p.getUniqueId());
        if (open == null) { open = new LinkedHashMap<String, Decision>(); OPEN.put(p.getUniqueId(), open); }
        open.put(policy, d);
        Deque<Decision> q = BY_BOT.get(p.getUniqueId());
        if (q == null) { q = new ArrayDeque<Decision>(); BY_BOT.put(p.getUniqueId(), q); }
        q.addLast(d);
        while (q.size() > KEEP) q.removeFirst();
        RECENT.addLast(d);
        while (RECENT.size() > GLOBAL_KEEP) RECENT.removeFirst();
        return d;
    }

    /**
     * Close the open decision for this bot and policy with an outcome.
     *
     * @param reward positive for what the plan achieved, negative for a cost
     * @param outcome a short label: kill, death, void, bed, bought, collected, timeout, abandoned
     * @param note    free text kept with the record
     */
    public static Decision close(Player p, String policy, double reward, String outcome, String note) {
        if (p == null) return null;
        Map<String, Decision> open = OPEN.get(p.getUniqueId());
        if (open == null) return null;
        Decision d = open.remove(policy);
        if (d == null) return null;
        d.closedAtTick = Math.max(1, System.currentTimeMillis() / 50);
        d.reward = reward;
        d.outcome = outcome;
        if (note != null && !note.isEmpty()) d.events.add(note);
        CombatStats.inc("attr." + policy + "." + outcome);
        write(d);
        return d;
    }

    /** Attach an event to whatever is open for this bot without closing it (a kill mid-rush, a hit taken). */
    public static void note(Player p, String policy, String event) {
        if (p == null) return;
        Map<String, Decision> open = OPEN.get(p.getUniqueId());
        if (open == null) return;
        Decision d = open.get(policy);
        if (d != null && d.events.size() < 8) d.events.add(event);
    }

    /** Every decision is closed with a timeout after this long, so a plan that never resolves is still counted. */
    public static void sweep(long nowMs) {
        for (Map<String, Decision> open : new ArrayList<Map<String, Decision>>(OPEN.values())) {
            for (Map.Entry<String, Decision> e : new ArrayList<Map.Entry<String, Decision>>(open.entrySet())) {
                Decision d = e.getValue();
                if (nowMs - d.wallMs < 45_000L) continue;
                d.closedAtTick = Math.max(1, nowMs / 50);
                d.reward = 0;
                d.outcome = "timeout";
                d.events.add("no resolution in 45s");
                open.remove(e.getKey());
                CombatStats.inc("attr." + d.policy + ".timeout");
                write(d);
            }
        }
    }

    /** The last N decisions for a bot, newest first: the run-up to whatever just happened. */
    public static List<Decision> history(Player p, int n) {
        List<Decision> out = new ArrayList<Decision>();
        Deque<Decision> q = p == null ? null : BY_BOT.get(p.getUniqueId());
        if (q == null) return out;
        java.util.Iterator<Decision> it = q.descendingIterator();
        while (it.hasNext() && out.size() < n) out.add(it.next());
        return out;
    }

    /** What is open right now for a bot. */
    public static Decision current(Player p, String policy) {
        Map<String, Decision> open = p == null ? null : OPEN.get(p.getUniqueId());
        return open == null ? null : open.get(policy);
    }

    // ------------------------------------------------------------------ reporting

    /**
     * The answer to "what could have been improved": per policy, how often the chosen action was the best one, and the
     * average score left on the table when it was not. A high regret means the decision features do not separate the
     * options; a low one means the policy is picking well and the losses come from execution.
     */
    public static List<String> report() {
        Map<String, int[]> counts = new LinkedHashMap<String, int[]>();       // {closed, wasBest, hasRegret}
        Map<String, double[]> sums = new LinkedHashMap<String, double[]>();   // {regretSum, rewardSum, rewardSq}
        for (Decision d : RECENT) {
            if (d.open()) continue;
            int[] c = counts.get(d.policy);
            if (c == null) { c = new int[3]; counts.put(d.policy, c); }
            double[] s = sums.get(d.policy);
            if (s == null) { s = new double[3]; sums.put(d.policy, s); }
            c[0]++;
            s[1] += d.reward;
            s[2] += d.reward * d.reward;
            if (d.scores != null && d.optionNames != null && d.action >= 0 && d.action < d.scores.length) {
                int best = 0;
                for (int i = 1; i < d.scores.length; i++) if (d.scores[i] > d.scores[best]) best = i;
                if (best == d.action) c[1]++;
                else { s[0] += d.scores[best] - d.scores[d.action]; c[2]++; }
            }
        }
        List<String> out = new ArrayList<String>();
        if (counts.isEmpty()) { out.add("attribution: no closed decisions yet"); return out; }
        out.add("attribution (closed decisions per policy):");
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            int[] c = e.getValue();
            double[] s = sums.get(e.getKey());
            double mean = c[0] == 0 ? 0 : s[1] / c[0];
            double var = c[0] == 0 ? 0 : Math.max(0, s[2] / c[0] - mean * mean);
            double regret = c[2] == 0 ? 0 : s[0] / c[2];
            out.add(String.format(Locale.ROOT,
                    "  %-12s n=%d  best-choice=%.0f%%  mean-reward=%+.2f +/-%.2f  regret=%.2f on %d%% of picks",
                    e.getKey(), c[0], 100.0 * c[1] / Math.max(1, c[0]), mean, Math.sqrt(var),
                    regret, 100 * c[2] / Math.max(1, c[0])));
        }
        return out;
    }

    /** The worst recent decisions, which is where a fix is worth the most. */
    public static List<String> worst(int n) {
        List<Decision> closed = new ArrayList<Decision>();
        for (Decision d : RECENT) if (!d.open()) closed.add(d);
        closed.sort(new Comparator<Decision>() {
            @Override public int compare(Decision a, Decision b) { return Double.compare(a.reward, b.reward); }
        });
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < Math.min(n, closed.size()); i++) {
            Decision d = closed.get(i);
            out.add("  " + d.botName + " " + d.line() + "  because " + d.because());
        }
        if (out.isEmpty()) out.add("  (nothing closed yet)");
        return out;
    }

    /** The run-up for one bot, newest first - what it was doing before the thing you are looking at. */
    public static List<String> trail(Player p, int n) {
        List<String> out = new ArrayList<String>();
        for (Decision d : history(p, n)) out.add("  " + d.line() + "  because " + d.because());
        if (out.isEmpty()) out.add("  (no decisions recorded for this bot)");
        return out;
    }

    public static void reset() {
        BY_BOT.clear();
        OPEN.clear();
        RECENT.clear();
        seq = 0;
    }

    /** One line per closed decision to attribution.log, so a session can be read after the fact. */
    private static void write(Decision d) {
        if (!logging || file == null) return;
        try (PrintWriter w = new PrintWriter(new FileWriter(file, true))) {
            w.println(new SimpleDateFormat("HH:mm:ss").format(new Date()) + " " + d.botName + " " + d.line()
                    + " because " + d.because());
        } catch (Throwable ignored) { }
    }
}
