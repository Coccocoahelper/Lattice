package net.h2020.api.core;

/**
 * Switches that change how the bots behave and that commands (which live in core) need to flip without depending on
 * the bots plugin. The bots read these; nothing else stores its own copy.
 */
public final class DemoFlags {
    /** Nobody retreats, so a fight runs long enough to film a full combo. */
    public static volatile boolean sparMode;
    /** Keep every bot under Resistance so combos can be watched. */
    public static volatile boolean resistanceOn;
    public static volatile int resistanceAmp = 2;
    /** Zest tapping on/off (see the bots plugin for what the sequence is). */
    public static volatile boolean zestEnabled = true;
    private DemoFlags() {}
}
