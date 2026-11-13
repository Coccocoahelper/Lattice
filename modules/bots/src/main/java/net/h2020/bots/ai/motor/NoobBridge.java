package net.h2020.bots.ai.motor;

import net.h2020.bots.ai.util.AIMath;

/**
 * The half-sideways beginner: shift held for the whole bridge, walking sideways rather than backwards, creeping the
 * last stretch to the edge, stopping after every block to think about it, and occasionally stepping back to try again.
 *
 * <p>It exists to be beaten. Everything that makes it slow is deliberate: {@link #pausesAfterBlock()} puts a pause in
 * after each placement, {@link #hesitatesAtEdge()} stops it at the lip, and the pitch starts too flat and is nudged
 * down late, which is why a beginner's bridge has a visible hesitation at every block.
 */
public final class NoobBridge extends BridgeMethod {

    @Override public Bridger.Tech tech() { return Bridger.Tech.NOOB; }

    @Override
    protected void configure(Bridger b) {
        crouch = Crouch.HOLD;
        offset = b.offsetSign * (float) AIMath.range(b.ctx.rng, 50, 62);
        hTarget = 0.26;
        depth = 0.45;
        eyeH = 1.54;
        double p = facePitch(eyeH, depth, hTarget, offset);
        p += b.ctx.rng.nextGaussian() * 0.4;
        // starts a bit too flat and corrects at the edge; the clamp keeps it on the block
        p -= Math.abs(b.ctx.rng.nextGaussian()) * 2.5 * (1 - b.ctx.persona.skill.overall);
        pitch = pitchFloor((float) p);
        wobbleAmp = 0;
    }

    /**
     * Creeps the last stretch to the edge, a key press every other tick. This is the beginner's tell: the approach
     * takes twice as long as the placement.
     */
    @Override
    public void steer(Bridger b, double err, double h) {
        if (h > -0.15 && b.ctx.tick % 2 == 0) { b.ctx.motor.move(0, 0); return; }
        b.lineKeep(err, h);
    }

    /** Stops after every block. */
    @Override public boolean pausesAfterBlock() { return true; }

    /** Stops at the lip before placing. */
    @Override public boolean hesitatesAtEdge() { return true; }

    /** Beginners look around constantly; the engine keeps a short glance counter for this method. */
    @Override public boolean glancesOften() { return true; }

    /** Three minutes on one block before it gives up, because a beginner really does stand there. */
    @Override public int giveUpTicks() { return 240; }

    /** Long pauses get a look around, so the glance is that much longer. */
    @Override public int glanceExtraTicks() { return 10; }
}
