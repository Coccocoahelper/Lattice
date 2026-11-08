package net.h2020.bots.ai.combat;

import net.h2020.api.stats.CombatStats;

/**
 * Zest tapping: the combo where the clicks land in tight bursts around the moment the target's damage immunity ends,
 * with a W-tap every third hit to re-set sprint and a strafe on the others.
 * <p>
 * In 1.8 the "attack delay" is the half-second of invulnerability after a hit. A perfect combo clicks just before that
 * window closes - early enough that the packet arrives as it opens, late enough that it is not eaten - which is what
 * the burst models. Everything here is in ticks (20 per second) and every number is tunable at runtime.
 */
public final class ZestTap {
    /** Off switch: one flag turns the whole sequence back into ordinary clicking. */
    public static boolean enabled() { return net.h2020.api.core.DemoFlags.zestEnabled; }
    /** Kept for call sites that still assign it; the real switch is DemoFlags. */
    public static void setEnabled(boolean on) { net.h2020.api.core.DemoFlags.zestEnabled = on; }
    /** Set while a bot is fighting with zest on: the motor refuses every jump request in that state. */
    public static volatile boolean grounded = false;

    // ---- tunables (ticks; 1 tick = 50 ms) ----
    /** How many ticks before the immunity ends the first click of a burst may land (50 ms by default). */
    public static volatile int burstBefore = 1;
    /** How many ticks after it ends the last click of a burst may still land (50 ms). */
    public static volatile int burstAfter = 1;
    /** W-tap length (150 ms). */
    public static volatile int wtapTicks = 3;
    /** How long before the reset the W-tap starts (75 ms, rounded to two ticks). */
    public static volatile int wtapLead = 2;
    /** Strafe length (250 ms). */
    public static volatile int strafeTicks = 5;
    /** Every Nth hit (counting from 1) is the W-tap hit. */
    public static volatile int every = 3;
    /** Immunity after a landed hit, in ticks (10 = the 1.8 half second). */
    public static volatile int immunityTicks = 10;

    private int index;          // hits landed in this fight
    private int strafeDir = 1;
    private long lastHit = -1000;
    private long burstAt = -1000;   // when the current burst opens
    private long wtapUntil = -1000;
    private long strafeUntil = -1000;

    public void reset() {
        index = 0;
        strafeDir = 1;
        lastHit = -1000;
        burstAt = -1000;
        wtapUntil = -1000;
        strafeUntil = -1000;
    }

    /** Our hit landed at `tick`: schedule the next burst, the W-tap or the strafe. */
    public void onHit(long tick) {
        lastHit = tick;
        index++;
        long reset = tick + immunityTicks;                 // immunity ends here
        burstAt = reset - burstBefore;
        boolean wtap = every > 1 ? index % every == 1 : true;   // every=1 means every hit is a tap
        if (wtap) {
            wtapUntil = reset + Math.max(0, wtapTicks - wtapLead);
            CombatStats.inc("zest.wtap");
        } else {
            strafeDir = -strafeDir;
            strafeUntil = reset + strafeTicks;
            CombatStats.inc("zest.strafe");
        }
    }

    /** Is the crosshair allowed to click this tick? */
    public boolean inBurst(long tick) {
        if (!enabled()) return true;
        if (lastHit < 0) return true;                       // first hit of the fight: click freely
        long reset = lastHit + immunityTicks;
        return tick >= reset - burstBefore && tick <= reset + burstAfter;
    }

    /** W-tap window: forward input is released, so the sprint resets with the hit. */
    public boolean wtapping(long tick) {
        return enabled() && tick >= burstAt && tick <= wtapUntil;
    }

    public boolean strafing(long tick) {
        return enabled() && tick <= strafeUntil;
    }

    public int strafeDir() { return strafeDir; }

    public int index() { return index; }

    public static String describe() {
        return "zest " + (enabled() ? "on" : "off") + ": burst " + burstBefore + "/" + burstAfter + " ticks around the "
                + immunityTicks + "-tick reset, W-tap " + wtapTicks + " ticks starting " + wtapLead
                + " before it, strafe " + strafeTicks + " ticks, every " + every + "th hit";
    }
}
