package net.h2020.api.bot;

/**
 * Personality knobs, all 0..1 unless noted. Generated per bot with a realistic distribution (most players are
 * average; a few sweats, a few complete beginners).
 */
public final class BotSkill {
    /** Overall skill; drives decision quality and defaults for the rest. */
    public final double overall;
    /** Aim precision / tracking quality. */
    public final double aim;
    /** Mean human reaction time in ms (typ. 180-320). */
    public final int reactionMs;
    /** Mean clicks per second when fighting (typ. 6-13; jitter/butterfly sweats higher). */
    public final double cps;
    /** Mouse sensitivity 0..1 as in the options slider (affects GCD of rotation deltas). */
    public final float sensitivity;
    public final BridgeStyle bridgeStyle;
    /** 0 = passive/camper, 1 = rusher. */
    public final double aggression;
    /** Peripheral awareness: how often they look around / notice people behind them. */
    public final double awareness;
    /** Chat frequency/toxicity. */
    public final double chattiness;

    public BotSkill(double overall, double aim, int reactionMs, double cps, float sensitivity, BridgeStyle bridgeStyle,
                    double aggression, double awareness, double chattiness) {
        this.overall = overall; this.aim = aim; this.reactionMs = reactionMs; this.cps = cps; this.sensitivity = sensitivity;
        this.bridgeStyle = bridgeStyle; this.aggression = aggression; this.awareness = awareness; this.chattiness = chattiness;
    }

    public enum BridgeStyle {
        /** Walk backwards, crouch at each edge (most players). */
        SHIFT,
        /** Speed bridge: crouch-tap timing, faster. */
        SPEED,
        /** Ninja bridge: diagonal-ish, unshift mid-block. */
        NINJA,
        /** Breezily / moonwalk diagonal (sweats). */
        BREEZILY,
        /** Beginner: looks down, walks forward placing slowly, often falls. */
        NOOB
    }
}
