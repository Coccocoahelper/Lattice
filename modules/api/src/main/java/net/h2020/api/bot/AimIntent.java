package net.h2020.api.bot;

/** Why the bot is moving its mouse — shapes speed/precision of the human aim model. */
public enum AimIntent {
    /** Casual looking around / glancing at a sound. Slow, imprecise, saccade-like. */
    GLANCE,
    /** Navigating: look roughly where you're walking. */
    NAVIGATE,
    /** Combat tracking: fast, reactive, overshoots on direction changes. */
    COMBAT,
    /** Precise block targeting (bridging, placing, breaking beds, opening chests). Slower final approach. */
    PRECISE,
    /** Flick snap (e.g. turning to face an attacker from behind). */
    FLICK
}
