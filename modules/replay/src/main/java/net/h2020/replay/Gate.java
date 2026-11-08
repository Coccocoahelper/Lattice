package net.h2020.replay;

import net.h2020.bots.ai.ml.CombatArbiter;
import net.h2020.bots.ai.ml.DuelPolicy;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * The promotion gate: a generation only replaces the champion if it makes better decisions on the same, never-trained
 * holdout of real play.
 * <p>
 * Two policy models are scored: the combat arbiter (does it predict which mechanic pays off?) and the duel policy
 * (does it put more probability on stances that win exchanges?). Both scores are computed from the same holdout rows,
 * so the comparison between generations is paired - a lucky game cannot promote anything, and a generation that only
 * got better at beating itself cannot either.
 */
public final class Gate {
    /** How many holdout rows must exist before the gate will decide anything. */
    public static final int MIN_ROWS = 2000;
    /** How much better the challenger has to be. */
    public static final double MARGIN = 0.004;
    /** A policy whose actions are this concentrated has stopped choosing; it must not be promoted on accuracy alone. */
    public static final double MIN_ENTROPY = 0.35;

    private Gate() {}

    public static final class Result {
        public final double champion, challenger;
        public final int mechRows, stanceRows;
        public final boolean promoted;
        public final String reason;
        Result(double c, double ch, int m, int s, boolean p, String r) {
            champion = c; challenger = ch; mechRows = m; stanceRows = s; promoted = p; reason = r;
        }
        public String line() {
            return String.format(java.util.Locale.ROOT,
                    "§6[train] §egate: §f%.4f§7 champion vs §f%.4f§7 challenger on §f%d§7+§f%d§7 holdout rows - %s",
                    champion, challenger, mechRows, stanceRows, reason);
        }
    }

    /** Score both generations on the holdout and promote the challenger if it is genuinely better. */
    public static Result evaluate() {
        File holdout = Replay.holdoutFile();
        int rows = Replay.count(holdout);
        if (rows < MIN_ROWS) {
            // No evidence either way. The live weights keep everything the arena has learned - restoring the champion
            // here is what used to throw a whole generation away for want of a holdout.
            return new Result(0, 0, 0, 0, false, "only " + rows + " holdout rows, need " + MIN_ROWS
                    + " - keeping the current weights (nothing discarded)");
        }
        League.restore(League.championDir());
        double champ = score(holdout, -1);
        double champA = score(holdout, 0);
        double champB = score(holdout, 1);
        League.restore(League.challengerDir());
        double chal = score(holdout, -1);
        double chalA = score(holdout, 0);
        double chalB = score(holdout, 1);
        double chalRecent = scoreRecent(holdout);
        League.restore(League.championDir());
        double champRecent = scoreRecent(holdout);
        League.restore(League.challengerDir());
        OffPolicy.Estimate chalOp = OffPolicy.stance(holdout);
        League.restore(League.championDir());
        OffPolicy.Estimate champOp = OffPolicy.stance(holdout);
        League.restore(League.challengerDir());
        double entropy = meanStanceEntropy(holdout);
        int[] counts = counts(holdout);
        // the challenger has to beat the champion on the whole holdout *and* on both interleaved halves, so a lucky
        // split cannot promote it; and a collapsed policy is refused however accurate it looks
        boolean better = chal > champ + MARGIN
                && chalA > champA + MARGIN * 0.5 && chalB > champB + MARGIN * 0.5
                && chalRecent >= champRecent - 0.002;            // no step backwards on the newest play
        boolean collapsed = entropy < MIN_ENTROPY;
        boolean promote = better && !collapsed;
        if (promote) League.promoteChallenger();
        else League.discardChallenger();
        String why;
        if (promote) why = "challenger promoted to generation " + League.generation();
        else if (collapsed) why = String.format(java.util.Locale.ROOT,
                "challenger refused: stance policy collapsed (entropy %.2f < %.2f) - champion stays", entropy, MIN_ENTROPY);
        else if (!better) why = String.format(java.util.Locale.ROOT,
                "challenger discarded (%.4f vs %.4f overall, halves %.4f/%.4f vs %.4f/%.4f)",
                chal, champ, chalA, chalB, champA, champB);
        else why = "challenger discarded, champion stays";
        why += String.format(java.util.Locale.ROOT,
                " [op: champion %.3f vs challenger %.3f, ess %.0f, tail %.3f vs %.3f]",
                champOp.value, chalOp.value, chalOp.ess, champRecent, chalRecent);
        if (chalOp.usable(rows) && chalOp.value < champOp.value - 0.02) {
            why += " - off-policy says the challenger is worse";
        }
        return new Result(champ, chal, counts[0], counts[1], promote, why);
    }

    /** Mean entropy of the stance policy over the holdout's stance rows (natural log, 0 = one action always). */
    private static double meanStanceEntropy(File holdout) {
        double sum = 0;
        int n = 0;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(holdout));
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.contains("\"k\":\"stance\"")) continue;
                double[] x = vec(line);
                if (x == null) continue;
                double[] p = DuelPolicy.probabilities(x);
                double h = 0;
                for (double v : p) if (v > 1e-9) h -= v * Math.log(v);
                sum += h;
                n++;
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
        }
        return n == 0 ? 0 : sum / n;
    }

    /** Score on the newest rows only: a challenger has to generalise forward, not just fit the whole file. */
    private static double scoreRecent(File holdout) {
        int total = Replay.count(holdout);
        int from = (int) (total * 0.75);
        return score(holdout, -1, from);
    }

    /** @param parity 0/1 = that interleaved half of the rows, -1 = all of them. */
    private static double score(File holdout, int parity) { return score(holdout, parity, 0); }

    private static double score(File holdout, int parity, int skipRows) {
        double mechHits = 0, stanceGood = 0, stanceBad = 0;
        int mech = 0, good = 0, bad = 0;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(holdout));
            String line;
            int row = 0;
            while ((line = r.readLine()) != null) {
                row++;
                if (row <= skipRows) continue;
                if (parity >= 0 && (row & 1) != parity) continue;
                if (line.contains("\"k\":\"mech\"")) {
                    int kind = (int) num(line, "m", -1);
                    double[] x = vec(line);
                    int a = (int) num(line, "a", 0);
                    int y = (int) num(line, "y", 0);
                    if (kind < 0 || x == null) continue;
                    double p = CombatArbiter.value(kind, x, a == 1);
                    if ((p >= 0.5) == (y == 1)) mechHits++;
                    mech++;
                } else if (line.contains("\"k\":\"stance\"")) {
                    double[] x = vec(line);
                    int a = (int) num(line, "a", 0);
                    double rw = num(line, "r", 0);
                    if (x == null) continue;
                    double p = DuelPolicy.probabilities(x)[Math.max(0, Math.min(6, a))];
                    if (rw > 0) { stanceGood += p; good++; }
                    else if (rw < 0) { stanceBad += p; bad++; }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
        }
        double mechAcc = mech == 0 ? 0 : mechHits / mech;
        double pref = (good == 0 || bad == 0) ? 0.5 : 0.5 + 0.5 * (stanceGood / good - stanceBad / bad);
        int total = mech + good + bad;
        if (total == 0) return 0;
        return (mech * mechAcc + (good + bad) * pref) / total;
    }

    private static int[] counts(File holdout) {
        int m = 0, s = 0;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(holdout));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains("\"k\":\"mech\"")) m++;
                else if (line.contains("\"k\":\"stance\"")) s++;
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
        }
        return new int[]{m, s};
    }

    static double num(String line, String key, double def) {
        String k = "\"" + key + "\":";
        int i = line.indexOf(k);
        if (i < 0) return def;
        int j = i + k.length();
        int end = j;
        while (end < line.length() && "-0123456789.eE".indexOf(line.charAt(end)) >= 0) end++;
        try { return Double.parseDouble(line.substring(j, end)); } catch (Throwable t) { return def; }
    }

    static double[] vec(String line) {
        int i = line.indexOf("\"x\":[");
        if (i < 0) return null;
        int j = i + 5;
        int end = line.indexOf(']', j);
        if (end < 0) return null;
        String[] parts = line.substring(j, end).split(",");
        double[] x = new double[parts.length];
        try {
            for (int k = 0; k < parts.length; k++) x[k] = Double.parseDouble(parts[k].trim());
        } catch (Throwable t) {
            return null;
        }
        return x;
    }
}
