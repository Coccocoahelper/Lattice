package net.h2020.bots.ai.motor;

import net.h2020.bots.ai.util.AIMath;
import net.h2020.bots.ai.world.Shape;

/**
 * One bridging method: the numbers it walks to, where it looks, when it crouches, and when it is allowed to place.
 *
 * <p>Every technique used to live in {@link Bridger} as a switch on an enum, which made four of them impossible to
 * read side by side and made a fifth (ninja 2) look like it had to be another switch arm. Each one is a file now:
 * {@link NoobBridge}, {@link ShiftBridge}, {@link SpeedBridge}, {@link NinjaBridge}, {@link Ninja2Bridge},
 * {@link MoonwalkBridge}, {@link BreezilyBridge}. {@code Bridger} keeps the parts every technique shares: the
 * backwards walk, the edge geometry, the ray that decides when a click lands, stacking, and recovery.
 *
 * <p>The split is deliberate about what a technique owns. It owns the pitch it walks to and how far off the line it
 * looks, so the ray still passes through the support's face (that part is physics, not style). It owns the crouch
 * policy, because that is what the eye reads as "this is a ninja bridge". It owns whether a click may go out this
 * tick, which is what makes a diagonal method safe or reckless.
 */
public abstract class BridgeMethod {

    /** How the technique holds crouch: through the whole bridge, as a tap at the edge, or never. */
    public enum Crouch { HOLD, TAP, NONE }

    // ---- tuning, filled by configure() and copied by the engine when the bridge starts ----

    public Crouch crouch = Crouch.HOLD;
    /** Degrees off the back-down-the-bridge line the head sits. Moves the ray's hit point sideways. */
    public float offset;
    /** Distance of the body centre past the support's edge where the ray lands where it should. */
    public double hTarget = 0.22;
    /** Extra eye height the ray is computed with: how far down the face the click is aimed. */
    public double depth = 0.25;
    public double eyeH = 1.62;
    /** Pitch to walk to. The engine nudges this per block; basePitch is this value at the start. */
    public float pitch;
    public float wobbleAmp, wobbleFreq, wobblePhase;
    public float breezyAmp;

    /**
     * Accept any face that reaches the next cell, not only the travel-side face. A god bridge looks at the block
     * corner rather than square at the side, so the face that answers the click is whatever the corner gives.
     */
    public boolean anyFace;

    // ---- the travel, mirrored at start so a method never has to ask ----

    protected int vx, vz;
    protected boolean diag;
    protected float travelYaw;

    public abstract Bridger.Tech tech();

    /** The file that implements a technique. */
    public static BridgeMethod of(Bridger.Tech t) {
        switch (t) {
            case NOOB: return new NoobBridge();
            case SPEED: return new SpeedBridge();
            case NINJA: return new NinjaBridge();
            case NINJA2: return new Ninja2Bridge();
            case MOONWALK: return new MoonwalkBridge();
            case BREEZILY: return new BreezilyBridge();
            case SHIFT:
            default: return new ShiftBridge();
        }
    }

    public String id() { return tech().name().toLowerCase(java.util.Locale.ROOT); }

    /** Called once per bridge, before the first tick. */
    public final void begin(Bridger b) {
        this.vx = b.dirX();
        this.vz = b.dirZ();
        this.diag = vx != 0 && vz != 0;
        this.travelYaw = AIMath.yawOf(vx, vz);
        configure(b);
    }

    /** Fill the tuning. Randomise the human bits here, once per bridge, not per tick. */
    protected abstract void configure(Bridger b);

    /** Where the head looks while walking the line. */
    public float lookYaw(Bridger b) {
        return AIMath.wrap(travelYaw + 180f + offset + wobble(b));
    }

    /** The resting-hand wobble every method with a mouse gets. */
    protected float wobble(Bridger b) {
        return wobbleAmp == 0 ? 0 : wobbleAmp * (float) Math.sin(b.ticks() * wobbleFreq + wobblePhase);
    }

    /** Cost of a key combination: 0 = this method's habit, higher = it would not press that. */
    public double habit(float f, float s) { return 0; }

    /**
     * Pitch to walk with. The engine hands over the pitch its edge geometry wants; a method may override it while the
     * body is still well inside the block, which is where a look-down technique gets its character, and must return
     * the engine's value near the edge or the click will not land.
     */
    public float walkPitch(Bridger b, float enginePitch, double h) { return enginePitch; }

    /**
     * A point to aim at instead of a yaw/pitch pair, or null. A method that places the way a scaffold does - pick the
     * cell, then look at the face that fills it - aims at the point rather than at a direction, because that is what
     * makes the click land on the face it wanted.
     */
    public org.bukkit.Location aimTarget(Bridger b) { return null; }

    /** Movement keys while walking the line. Default is the engine's line keeping. */
    public void steer(Bridger b, double err, double h) { b.lineKeep(err, h); }

    /** Should crouch be down this tick? The engine applies it. */
    public boolean sneak(Bridger b, double h, boolean clicked, int nx, int nz) {
        boolean edge = !Shape.floor(b.ctx.world().shape(nx, b.supportY() - 1, nz));
        if (edge) b.raiseSneakHold(Bridger.UNSNEAK_TICKS);
        if (b.pendingSneak()) { b.raiseSneakHold(Bridger.UNSNEAK_TICKS); b.pendingSneak(false); }
        boolean sneak;
        if (crouch == Crouch.NONE) {
            // sneakless: never crouch on purpose; shorten the step when the ray would pass the face, and keep the
            // last-instant save-crouch for a real miss
            org.bukkit.util.Vector v = b.ctx.c.velocity();
            double vAlong = Math.max(0, v.getX() * vx + v.getZ() * vz);
            double dKeys = vAlong + 0.1 * Math.max(0, b.lastProg);
            if (h + dKeys <= 0.28) sneak = false;
            else if (clicked || h + vAlong <= 0.28) { b.ctx.motor.move(0, 0); sneak = false; }
            else sneak = true;
            if (b.freePlacements() > 0 && edge) sneak = false;
        } else if (crouch == Crouch.TAP) {
            // the tap exists to stop the slide for the tick the click happens, not to hold the body still.
            // Once the block is down the key comes straight back up, so the next stretch is walked at full speed.
            org.bukkit.util.Vector v = b.ctx.c.velocity();
            double vAlong = Math.max(0, v.getX() * vx + v.getZ() * vz);
            if (clicked) {
                b.unsneakAt(b.ctx.tick + 1 + b.unsneakDelay());
                b.raiseSneakHold(0);
                net.h2020.api.stats.CombatStats.inc("bridge.unsneak." + (1 + b.unsneakDelay()));
            }
            if (b.ctx.tick < b.unsneakAt()) sneak = true;                  // the tap is still held
            else sneak = (h > -0.02) || (h + vAlong * 2.0 > 0.12);
            // if the head is off its angle the click is on hold, so the body has to be on hold too: this is the tick
            // where a tap bridger slides off the lip waiting for the aim to come back
            if (!angleHeld(b)) { sneak = true; net.h2020.api.stats.CombatStats.inc("bridge.ninja.offangle"); }
            if (b.freePlacements() > 0 && edge) sneak = false;
        } else {
            sneak = b.sneakHold() > 0;
            if (!b.forgetSneak() || h < -0.3) sneak = true;
            if (b.freePlacements() > 0 && edge) sneak = false;            // the one free diagonal placement
            org.bukkit.util.Vector v = b.ctx.c.velocity();
            double vAlong = Math.max(0, v.getX() * vx + v.getZ() * vz);
            double hNext = h + vAlong * 1.2 + 0.03;
            if (!sneak && (h > 0.16 || hNext > 0.22) && !b.forgetSneak()) sneak = true;
        }
        b.tickSneakHold();
        return sneak;
    }

    /** Is the head on the angle this method needs before a click may land? */
    public boolean angleHeld(Bridger b) { return true; }

    /** Stop after every block, the way a beginner does. */
    public boolean pausesAfterBlock() { return false; }

    /** Stop at the lip before placing. */
    public boolean hesitatesAtEdge() { return false; }

    /** Glance at the destination every couple of blocks rather than every twenty. */
    public boolean glancesOften() { return false; }

    /** Ticks on one block before the engine gives up on it. */
    public int giveUpTicks() { return 120; }

    /** Extra ticks a glance holds, for the methods that stop to look around. */
    public int glanceExtraTicks() { return 0; }

    /** Keep walking while glancing: only a held crouch makes that safe. */
    public boolean peeksWhileGlancing() { return false; }

    /** May the click go out this tick, given where the body and the head are? */
    public boolean placementAllowed(Bridger b, double h, double lat, int nx, int nz) { return true; }

    /** The engine has advanced onto a new block: the click that did it has just landed. */
    public void afterPlacement(Bridger b, boolean wasSneaking) { }

    // ---- shared arithmetic, so the pitch story is written once ----

    /** Pitch that puts the ray on the support's travel-side face when the eye is `hTarget` past the edge. */
    protected static float facePitch(double eyeH, double depth, double hTarget, float offset) {
        double off = Math.toRadians(offset);
        return (float) Math.toDegrees(Math.atan((eyeH + depth) * Math.abs(Math.cos(off)) / hTarget));
    }

    /** Clamp so the ray still meets the face: flatter than this and it passes under the block. */
    protected static float pitchFloor(float p) {
        if (p < 79.0f) { net.h2020.api.stats.CombatStats.inc("bridge.pitch.floor"); return 79.0f; }
        return AIMath.clamp(p, 79f, 89f);
    }
}
