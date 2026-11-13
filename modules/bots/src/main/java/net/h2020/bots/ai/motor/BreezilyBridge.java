package net.h2020.bots.ai.motor;

import net.h2020.bots.ai.util.AIMath;

/**
 * Nearly straight back and never crouching: S held while A and D swap, so the body zig-zags down the line. The sway is
 * what keeps it on the block - each half-step drifts sideways, and the swap happens when the body has swung past its
 * amplitude, so the feet keep coming back over the support without a crouch.
 *
 * <p>The key choice is deliberately unlike every other method: instead of picking the keys that hold the line, it
 * alternates the two diagonals and only uses the sway amplitude to decide when to swap. If the mouse is not where it
 * should be both diagonals drift the same way, and the file falls back to the engine's line keeping.
 */
public final class BreezilyBridge extends BridgeMethod {

    private int dir = 1;

    @Override public Bridger.Tech tech() { return Bridger.Tech.BREEZILY; }

    @Override
    protected void configure(Bridger b) {
        crouch = Crouch.NONE;
        offset = b.offsetSign * (float) AIMath.range(b.ctx.rng, 10, 22);
        hTarget = 0.2;
        depth = 0.2;
        eyeH = 1.62;
        pitch = pitchFloor((float) (facePitch(eyeH, depth, hTarget, offset) + b.ctx.rng.nextGaussian() * 0.4));
        breezyAmp = (float) AIMath.range(b.ctx.rng, 0.1, 0.2);
        dir = b.ctx.rng.nextBoolean() ? 1 : -1;
        wobbleAmp = 0;
    }

    /**
     * S held with A/D alternating: each combo drifts to one side, and the side swaps when the body has swung past the
     * amplitude. Correcting is not this method's habit; swaying is.
     */
    @Override
    public void steer(Bridger b, double err, double h) {
        double rad = Math.toRadians(b.ctx.c.yaw());
        double fx = -Math.sin(rad), fz = Math.cos(rad);      // look direction
        double lx = Math.cos(rad), lz = Math.sin(rad);       // left (A)
        double latX = -vz, latZ = vx;                        // lateral axis of the bridge

        double latA = ((-fx + lx) * latX + (-fz + lz) * latZ);
        double latD = ((-fx - lx) * latX + (-fz - lz) * latZ);
        if (latA * latD < 0) {                               // the two diagonals drift to opposite sides, as they should
            int toPlus = latA > 0 ? 1 : -1;                  // key that drifts toward +lateral
            if (err > breezyAmp) dir = -toPlus;
            else if (err < -breezyAmp) dir = toPlus;
            b.keys(-1, dir);
            return;
        }
        // both diagonals drift the same way: the mouse is somewhere else, so hold the line instead of swaying into it
        b.lineKeep(err, h);
    }
}
