package net.h2020.bots.ai.ml;

/**
 * Adam, in the form an online model with one sample at a time needs.
 *
 * <p>Every model here learned with plain SGD: {@code w -= lr * err * x}. On these feature vectors that is a bad fit,
 * for two reasons that are visible in the numbers rather than theoretical.
 *
 * <ul>
 *   <li><b>The features are on wildly different scales.</b> The fight model's bias term is a constant {@code 1.0}
 *       while its ping term is {@code ping/300} and its health terms are {@code hp/20}. A single global learning rate
 *       has to be small enough not to blow up the bias and large enough to move the rare features, so it is always
 *       wrong for most of the vector: the bias converges in a handful of samples and the rare features never move.</li>
 *   <li><b>Fights arrive one at a time and rarely.</b> A bot sees a duel every few minutes, so a fixed rate either
 *       underfits early or oscillates late. Adam's per-parameter step is scaled by the historical magnitude of that
 *       parameter's own gradient, which is exactly the correction a sparse, unnormalised stream needs.</li>
 * </ul>
 *
 * <p>This is Adam with the bias correction folded in, no weight decay, and a step cap. It is deliberately small and
 * dependency-free: the whole point is that {@code FightModel}, {@code PlacementPolicy} and the rest can hold an
 * instance next to their weights and swap three lines of update code.
 *
 * <pre>
 *   Adam o = new Adam(n);
 *   o.step(W, grad, lr);      // W[i] -= lr * m[i]/(sqrt(v[i])+eps), bias-corrected
 * </pre>
 *
 * <p>State is per-weight-array, so one instance belongs to one model. It is not thread safe and does not need to be:
 * every model here is updated from the server thread.
 */
public final class Adam {
    private static final double BETA1 = 0.9;
    private static final double BETA2 = 0.999;
    private static final double EPS = 1e-8;
    /** Adam's first step is ~lr regardless of gradient size; cap it so one outlier fight cannot jump a weight. */
    private static final double MAX_STEP = 0.35;

    private final double[] m;
    private final double[] v;
    private long t;

    public Adam(int n) {
        m = new double[n];
        v = new double[n];
        t = 0;
    }

    /**
     * Apply one gradient step to {@code w}.
     *
     * @param w    weights, updated in place
     * @param grad gradient of the loss with respect to each weight (same length as {@code w})
     * @param lr   base learning rate; Adam's effective step is this, scaled per parameter
     */
    public void step(double[] w, double[] grad, double lr) {
        if (w == null || grad == null || w.length != grad.length || m.length != w.length) return;
        t++;
        double bc1 = 1.0 - Math.pow(BETA1, t);
        double bc2 = 1.0 - Math.pow(BETA2, t);
        for (int i = 0; i < w.length; i++) {
            double g = grad[i];
            m[i] = BETA1 * m[i] + (1 - BETA1) * g;
            v[i] = BETA2 * v[i] + (1 - BETA2) * g * g;
            double mh = m[i] / bc1;                 // bias-corrected first moment
            double vh = v[i] / bc2;                 // bias-corrected second moment
            double step = lr * mh / (Math.sqrt(vh) + EPS);
            if (step > MAX_STEP) step = MAX_STEP;
            else if (step < -MAX_STEP) step = -MAX_STEP;
            w[i] -= step;
        }
    }

    /** How many steps this optimiser has taken, for reports and for "has this model ever trained". */
    public long steps() { return t; }

    /** Re-seed after a load from disk: the moments belong to the old trajectory, not to the restored weights. */
    public void reset() {
        java.util.Arrays.fill(m, 0);
        java.util.Arrays.fill(v, 0);
        t = 0;
    }

    /** Save/restore so a restart does not throw away the momentum of a converging model. */
    public double[] state() {
        double[] s = new double[m.length * 2 + 1];
        System.arraycopy(m, 0, s, 0, m.length);
        System.arraycopy(v, 0, s, m.length, v.length);
        s[s.length - 1] = t;
        return s;
    }

    public void restore(double[] s) {
        if (s == null || s.length != m.length * 2 + 1) return;
        System.arraycopy(s, 0, m, 0, m.length);
        System.arraycopy(s, m.length, v, 0, v.length);
        t = (long) s[s.length - 1];
    }
}
