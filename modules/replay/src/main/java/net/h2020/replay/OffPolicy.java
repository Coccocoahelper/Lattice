package net.h2020.replay;

import net.h2020.bots.ai.ml.CombatArbiter;
import net.h2020.bots.ai.ml.DuelPolicy;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Off-policy evaluation of the current policies against the decisions that were actually logged.
 * <p>
 * A holdout accuracy says "this model predicts recorded outcomes better". It does not say "this policy would have
 * scored better", because the rows were produced by the old policy: the actions that were never taken are missing.
 * Self-normalised importance sampling (SNIPS) reweights each logged decision by how much more (or less) likely the
 * new policy is to take that same action, so the estimate is for the new policy while the data stays the old one.
 * The effective sample size (ESS) is the honesty check: when a handful of rows carry all the weight, the estimate is
 * noise and must not gate anything.
 */
public final class OffPolicy {
    public static final double MIN_PROPENSITY = 0.05;

    public static final class Estimate {
        public final double value, ess, weight;
        public final int rows;
        Estimate(double v, double e, double w, int n) { value = v; ess = e; weight = w; rows = n; }
        public boolean usable(int total) { return rows >= 200 && ess >= 0.15 * total; }
        public String line() {
            return String.format(java.util.Locale.ROOT, "off-policy %.3f (ess %.0f/%d rows)", value, ess, rows);
        }
    }

    private OffPolicy() {}

    /** SNIPS estimate of the stance policy's reward over every stance row in the file. */
    public static Estimate stance(File rows) {
        double sumW = 0, sumWR = 0, sumW2 = 0;
        int n = 0;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(rows));
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.contains("\"k\":\"stance\"")) continue;
                double[] x = Gate.vec(line);
                if (x == null) continue;
                double logged = Gate.num(line, "p", 0);
                if (logged < MIN_PROPENSITY) continue;          // no propensity: an old row, cannot be used
                double reward = Gate.num(line, "r", 0);
                int a = (int) Gate.num(line, "a", 0);
                double pNew = DuelPolicy.probabilities(x)[Math.max(0, Math.min(6, a))];
                double w = Math.min(10.0, pNew / logged);
                sumW += w; sumWR += w * reward; sumW2 += w * w;
                n++;
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
        }
        double value = sumW <= 0 ? 0 : sumWR / sumW;
        double ess = sumW2 <= 0 ? 0 : (sumW * sumW) / sumW2;
        return new Estimate(value, ess, sumW, n);
    }

    /** SNIPS estimate for the mechanic arbiter (reward is the recorded outcome probability of the taken action). */
    public static Estimate mechanics(File rows) {
        double sumW = 0, sumWR = 0, sumW2 = 0;
        int n = 0;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(rows));
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.contains("\"k\":\"mech\"")) continue;
                double[] x = Gate.vec(line);
                int kind = (int) Gate.num(line, "m", -1);
                if (x == null || kind < 0) continue;
                double logged = Gate.num(line, "p", 0);
                if (logged < MIN_PROPENSITY) continue;
                boolean a = Gate.num(line, "a", 0) == 1;
                double outcome = Gate.num(line, "y", 0);
                double pNew = CombatArbiter.value(kind, x, a);
                double w = Math.min(10.0, pNew / logged);
                sumW += w; sumWR += w * outcome; sumW2 += w * w;
                n++;
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
        }
        double value = sumW <= 0 ? 0 : sumWR / sumW;
        double ess = sumW2 <= 0 ? 0 : (sumW * sumW) / sumW2;
        return new Estimate(value, ess, sumW, n);
    }
}
