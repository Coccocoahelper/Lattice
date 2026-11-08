package net.h2020.bots.ai.combat;

/** Global switches for the block-play / projectile behaviours, driven by /bot settings. */
public final class CombatTuning {
    /** Stomps, duel pillars, middle exits and head hitters are on. */
    public static volatile boolean blockPlay = true;
    /** Fireballs are deflected / dodged. */
    public static volatile boolean fireballPlay = true;
    /** Beds get broken opportunistically in the middle of a fight. */
    public static volatile boolean bedRaid = true;
    /** Fast defence building: no settling wait between blocks, cached cell plan, no idle ticks. */
    /** Keep walking between blocks while a wall is being placed (a bot that roots itself builds slowly). */
    public static volatile boolean moveWhilePlacing = true;
    public static volatile boolean fastDefence = true;

    private CombatTuning() {}
}
