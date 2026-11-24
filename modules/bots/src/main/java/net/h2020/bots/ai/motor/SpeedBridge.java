package net.h2020.bots.ai.motor;

import net.h2020.bots.ai.util.AIMath;

/**
 * Straight back bridge walked uncrouched, with a crouch tap only at each edge: the everyday fast bridge.
 *
 * <p>The tap exists to stop the slide for the one tick the click happens in, not to hold the body still, so it comes
 * straight back up and the next stretch is walked at full speed. The un-sneak delay is drawn from skill, which is what
 * separates a clean speed bridger from one who is visibly a tick late at every block.
 */
public final class SpeedBridge extends BridgeMethod {

    @Override public Bridger.Tech tech() { return Bridger.Tech.SPEED; }

    @Override
    protected void configure(Bridger b) {
        crouch = Crouch.TAP;
        offset = (float) AIMath.range(b.ctx.rng, -3, 3);
        hTarget = 0.21;
        depth = 0.22;
        eyeH = 1.62;
        pitch = pitchFloor((float) (facePitch(eyeH, depth, hTarget, offset) + b.ctx.rng.nextGaussian() * 0.4));
        // a small wobble: the hand never sits perfectly still on a fast bridge
        wobbleAmp = (float) AIMath.range(b.ctx.rng, 1.0, 2.6);
        wobbleFreq = (float) AIMath.range(b.ctx.rng, 0.12, 0.28);
        wobblePhase = (float) (b.ctx.rng.nextDouble() * Math.PI * 2);
    }

    /** Straight back, held. */
    @Override
    public double habit(float f, float s) {
        return f == -1 && s == 0 ? 0 : (f == -1 ? 0.35 : 0.9);
    }

    /**
     * A confident player hops this bridge. Gated on skill and edge comfort rather than on the technique alone, because a
     * sprint-jump bridge with the wrong timing is a fall into the void, and that is a visible failure.
     */
    @Override
    protected boolean techniqueAllowsSprintJump(net.h2020.bots.ai.motor.Bridger b) {
        if (b == null || b.ctx == null) return false;
        net.h2020.bots.ai.Persona per = b.ctx.persona;
        if (per == null || per.skill == null) return false;
        // the confident half of the population, and only on somebody who is comfortable near the void
        return per.skill.overall > 0.72 && per.edgeComfort > 0.62;
    }
}
