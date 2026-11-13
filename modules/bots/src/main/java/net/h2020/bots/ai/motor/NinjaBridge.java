package net.h2020.bots.ai.motor;

import net.h2020.bots.ai.util.AIMath;

/**
 * The classic diagonal bridge: walk backwards at about 45 degrees with S and one strafe key, tapping crouch at each
 * edge. The diagonal is what buys the time to place — the body is over the void for longer than a straight bridge
 * allows — so the crouch only has to exist for the tick of the click.
 *
 * <p>The head only ever sits on one of the four canonical diagonals (45, -45, 135, -135) and the placement gate
 * refuses a click until it is there. Without that gate the ray drifts off the foot of the block and the click lands
 * on nothing, which is exactly how bots used to fall mid-diagonal.
 */
public final class NinjaBridge extends BridgeMethod {

    /** The one diagonal the head may sit on. */
    private float ninjaYaw;

    @Override public Bridger.Tech tech() { return Bridger.Tech.NINJA; }

    @Override
    protected void configure(Bridger b) {
        crouch = Crouch.TAP;
        // the travel is already a diagonal, so the head takes the canonical angle nearest the back-down-the-bridge
        // direction; anything in between drifts the ray off the face
        float back = AIMath.wrap(travelYaw + 180f);
        float[] angles = {45f, -45f, 135f, -135f};
        float best = angles[0];
        float bestDiff = Math.abs(AIMath.angleDiff(back, angles[0]));
        for (int k = 1; k < angles.length; k++) {
            float d = Math.abs(AIMath.angleDiff(back, angles[k]));
            if (d < bestDiff) { bestDiff = d; best = angles[k]; }
        }
        ninjaYaw = best;
        offset = 0f;
        hTarget = 0.22;
        depth = 0.25;
        eyeH = 1.62;
        // 79 degrees lands the ray on the face at this technique's edge distance; a few tenths of jitter is human
        pitch = (float) (79.0 + b.ctx.rng.nextGaussian() * 0.25);
        // half the usual wobble, and never off the four angles
        wobbleAmp = 1.2f;
        wobbleFreq = (float) AIMath.range(b.ctx.rng, 0.12, 0.28);
        wobblePhase = (float) (b.ctx.rng.nextDouble() * Math.PI * 2);
    }

    @Override
    public float lookYaw(Bridger b) {
        return AIMath.wrap(ninjaYaw + Math.max(-3f, Math.min(3f, wobble(b) * 0.5f)));
    }

    /** Backwards with one strafe: the diagonal is the habit. */
    @Override
    public double habit(float f, float s) {
        return f == -1 && s != 0 ? 0 : 0.3;
    }

    /** A ninja click waits for the head to be on the diagonal; the body has to wait crouched with it. */
    @Override
    public boolean angleHeld(Bridger b) {
        return Math.abs(AIMath.angleDiff(b.ctx.c.yaw(), ninjaYaw)) <= 10f;
    }

    /** Kept for the engine: the diagonal the head is pinned to. */
    public float ninjaYaw() { return ninjaYaw; }
}
