package net.h2020.bots.input;

import java.util.Random;

/**
 * Left mouse button timeline. Produces, per client tick, the number of button presses since the previous tick
 * (vanilla processes every queued press with clickMouse) and whether the button is physically down at tick time
 * (vanilla sendClickBlockToController(isKeyDown)).
 *
 * Human clicking: log-normal-ish inter-click intervals around 1000/cps, slow drift of the rhythm, occasional
 * double clicks (switch bounce / butterfly) and short pauses; press duration 40-110 ms. The AUTOCLICKER cheat swaps
 * this for a metronome at 18-25 CPS with very low variance and short presses.
 */
public final class ClickScheduler {
    private final Random rnd;
    private final double variability; // coefficient of variation of intervals (skill dependent)

    private boolean held;
    private double cps;
    private boolean autoClicker;
    private double autoCps;

    private double nextPress = Double.NaN;
    private double releaseAt = -1.0D;
    private double drift = 1.0D;
    private int queued;

    // results of the last tick
    public int presses;
    public boolean keyDown;
    public double lastIntervalMs;

    public ClickScheduler(Random rnd, double skillOverall) {
        this.rnd = rnd;
        this.variability = 0.30D - 0.12D * Math.max(0.0D, Math.min(1.0D, skillOverall));
    }

    public void setAutoClicker(boolean on) {
        if (on && !autoClicker) autoCps = 18.0D + rnd.nextDouble() * 7.0D;
        autoClicker = on;
    }

    public double effectiveCps() { return autoClicker && cps > 0.0D ? autoCps : cps; }

    /** cps <= 0 means the button is simply held down (mining). */
    public void setHeld(boolean held, double cps, double now) {
        if (held && !this.held) nextPress = now + rnd.nextDouble() * 25.0D;
        if (!held) nextPress = Double.NaN;
        this.held = held;
        this.cps = cps;
    }

    public boolean held() { return held; }

    /** A single click this tick. */
    public void click(double now) {
        queued++;
        releaseAt = Math.max(releaseAt, now + holdDuration(200.0D));
    }

    public void releaseAll() {
        held = false;
        queued = 0;
        releaseAt = -1.0D;
        nextPress = Double.NaN;
    }

    /** Advance to client time {@code now} (ms). */
    public void tick(double now) {
        presses = queued;
        queued = 0;
        boolean continuous = held && cps <= 0.0D;
        if (held && cps > 0.0D) {
            if (Double.isNaN(nextPress)) nextPress = now;
            int guard = 0;
            while (nextPress <= now && guard++ < 8) {
                presses++;
                double interval = nextInterval();
                releaseAt = Math.max(releaseAt, nextPress + holdDuration(interval));
                lastIntervalMs = interval;
                nextPress += interval;
            }
        }
        keyDown = continuous || now < releaseAt;
    }

    private double nextInterval() {
        if (autoClicker) {
            double mean = 1000.0D / autoCps;
            return Math.max(20.0D, mean * (1.0D + rnd.nextGaussian() * 0.05D));
        }
        double mean = 1000.0D / Math.max(1.0D, cps * drift);
        // slow rhythm drift
        drift += rnd.nextGaussian() * 0.025D + (1.0D - drift) * 0.06D;
        drift = Math.max(0.78D, Math.min(1.22D, drift));
        double r = rnd.nextDouble();
        if (r < 0.025D) return 25.0D + rnd.nextDouble() * 35.0D;            // double click
        if (r < 0.045D) return mean + 150.0D + rnd.nextDouble() * 300.0D;  // hesitation
        double sigma = variability;
        double v = mean * Math.exp(rnd.nextGaussian() * sigma - sigma * sigma / 2.0D);
        return Math.max(35.0D, v);
    }

    private double holdDuration(double interval) {
        if (autoClicker) return 12.0D + rnd.nextDouble() * 18.0D;
        double h = 68.0D + rnd.nextGaussian() * 18.0D;
        return Math.max(18.0D, Math.min(h, interval * 0.75D));
    }
}
