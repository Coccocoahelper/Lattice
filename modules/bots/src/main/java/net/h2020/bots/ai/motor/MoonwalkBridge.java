package net.h2020.bots.ai.motor;

import net.h2020.bots.ai.util.AIMath;

/**
 * Diagonal like a ninja, but the crouch never comes down at all: the diagonal gives the time to place, so the body
 * keeps walking. It is the fastest of the walking methods and the one that punishes a bad edge the hardest — there is
 * no tap to save a late click, only the engine's last-instant save-crouch when the ray really would pass the face.
 */
public final class MoonwalkBridge extends BridgeMethod {

    @Override public Bridger.Tech tech() { return Bridger.Tech.MOONWALK; }

    @Override
    protected void configure(Bridger b) {
        crouch = Crouch.NONE;
        offset = b.offsetSign * (float) AIMath.range(b.ctx.rng, 40, 50);
        hTarget = 0.2;
        depth = 0.22;
        eyeH = 1.62;
        pitch = pitchFloor((float) (facePitch(eyeH, depth, hTarget, offset) + b.ctx.rng.nextGaussian() * 0.4));
        wobbleAmp = 0;
    }

    /** Diagonal habit, same as a ninja's. */
    @Override
    public double habit(float f, float s) {
        return f == -1 && s != 0 ? 0 : 0.3;
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
        return per.skill.overall > 0.78 && per.edgeComfort > 0.7;
    }
}
