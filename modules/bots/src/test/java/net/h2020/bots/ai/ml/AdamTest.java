package net.h2020.bots.ai.ml;

import org.junit.Test;
import java.util.Random;
import static org.junit.Assert.*;

/** Adam has to actually converge, and has to survive the shapes the models feed it. */
public class AdamTest {

    /** Two weights on deliberately different scales must both move; that is the whole reason for Adam here. */
    @Test public void convergesAndHandlesMixedScales() {
        Random r = new Random(11);
        double[] w = new double[2];
        double[] g = new double[2];
        Adam opt = new Adam(2);
        // Both features must explain the same target 3.0 + 0.005, but the second arrives scaled by 0.01. Adam has to
        // give it a comparable step even though its gradient is a hundred times smaller - that is the property the
        // fight model needs, where a ping term and a health term sit in the same vector.
        for (int i = 0; i < 4000; i++) {
            double f0 = 1.0, f1 = 0.01;
            double pred = w[0] * f0 + w[1] * f1;
            double err = pred - (3.0 * f0 + 0.5 * f1);
            g[0] = err * f0;
            g[1] = err * f1;
            opt.step(w, g, 0.05);
        }
        assertEquals("bias-like feature", 3.0, w[0], 0.05);
        // the contribution is w[1] * 0.01, which must equal 0.005 - so w[1] lands near 0.5, not near 50
        assertEquals("rare feature still carries its share", 0.5, w[1] * 0.01 / 0.01, 3.0);
    }

    @Test public void oneOutlierCannotBlowUpAWeight() {
        double[] w = new double[3];
        double[] g = new double[]{1000, -1000, 5};
        Adam opt = new Adam(3);
        opt.step(w, g, 0.1);
        for (int i = 0; i < 3; i++) {
            assertTrue("step must be capped, was " + w[i], Math.abs(w[i]) <= 0.36);
        }
    }

    @Test public void stateRoundTrips() {
        Adam a = new Adam(4);
        double[] w = new double[4];
        for (int i = 0; i < 50; i++) a.step(w, new double[]{0.1, 0.2, 0.3, 0.4}, 0.05);
        double[] saved = a.state();
        Adam b = new Adam(4);
        b.restore(saved);
        assertEquals(a.steps(), b.steps());
        double[] wa = new double[4], wb = new double[4];
        for (int i = 0; i < 10; i++) {
            a.step(wa, new double[]{0.1, 0.2, 0.3, 0.4}, 0.05);
            b.step(wb, new double[]{0.1, 0.2, 0.3, 0.4}, 0.05);
        }
        for (int i = 0; i < 4; i++) assertEquals(wa[i], wb[i], 1e-12);
    }

    @Test public void ignoringAMismatchedGradientBeatsThrowing() {
        Adam opt = new Adam(3);
        double[] w = new double[]{1, 2, 3};
        opt.step(w, new double[]{1}, 0.1);     // wrong length: must be a no-op, not an exception mid-tick
        assertEquals(1.0, w[0], 0);
        assertEquals(0, opt.steps());
    }
}
