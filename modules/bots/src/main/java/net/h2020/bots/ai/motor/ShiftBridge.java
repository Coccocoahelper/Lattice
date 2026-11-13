package net.h2020.bots.ai.motor;

import net.h2020.bots.ai.util.AIMath;

/**
 * The straight back bridge with shift held the entire way: one block per step, slow and safe.
 *
 * <p>The crouch never comes up, so the body cannot slide off the lip, and the head is allowed to leave the bridge
 * while the bot keeps walking — the peek at the destination is only possible because the crouch is still down.
 * That is the whole trade: half the speed of a speed bridge, and almost no way to fall.
 */
public final class ShiftBridge extends BridgeMethod {

    @Override public Bridger.Tech tech() { return Bridger.Tech.SHIFT; }

    @Override
    protected void configure(Bridger b) {
        crouch = Crouch.HOLD;
        offset = (float) AIMath.range(b.ctx.rng, -4, 4);
        hTarget = 0.25;
        depth = 0.28;
        eyeH = 1.54;
        pitch = pitchFloor((float) (facePitch(eyeH, depth, hTarget, offset) + b.ctx.rng.nextGaussian() * 0.4));
        wobbleAmp = (float) AIMath.range(b.ctx.rng, 0.4, 0.8);
        wobbleFreq = (float) AIMath.range(b.ctx.rng, 0.12, 0.28);
        wobblePhase = (float) (b.ctx.rng.nextDouble() * Math.PI * 2);
    }

    /** Backwards, S only; anything else is a correction. */
    @Override
    public double habit(float f, float s) {
        return f == -1 && s == 0 ? 0 : (f == -1 ? 0.35 : 0.9);
    }

    /** The crouch holds it on the edge, so the head can leave the bridge and the feet keep going. */
    @Override public boolean peeksWhileGlancing() { return true; }
}
