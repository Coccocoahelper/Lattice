package net.h2020.bots.ai.motor;

import net.h2020.bots.ai.util.AIMath;
import net.h2020.bots.ai.world.Shape;
import org.bukkit.Location;

/**
 * Ninja 2: god-bridge head with a bridge-assist crouch, placing on whatever face the corner gives.
 *
 * <p>It is a composite of two references, each doing the job it is actually good at.
 *
 * <p><b>Rotations</b> follow LibreBounce's god-bridge rotation mode (Scaffold, {@code generateGodBridgeRotations}):
 * the travel direction is snapped to 45 degree steps, and while the bot is bridging straight the head sits 45 degrees
 * off that axis, on a side that flips when the body is leaning off the block and the next cell is air. The diagonal
 * case looks straight down the snapped direction instead. Pitches are that mode's own: 73.5 straight, 75.6 diagonal,
 * 75 for the stationary pose. The head only commits to a god-bridge angle near an edge (their {@code edgeLimit} of
 * 2.5 blocks); further in it keeps the angle it had, which is why the pose reads as a carry rather than a twitch.
 *
 * <p><b>Crouch and placement</b> follow IllusionClient's BridgeAssist: sneak once the body is 0.24 past the edge, hold
 * the sneak for about 85 ms after leaving it, sneak immediately after a click that went out without one, and - the
 * part that matters on diagonals - withhold the click until the body has backed up to the edge on <i>every</i> axis it
 * is moving along, then allow one extra placement from that same stance. That is what stops a diagonal from leaving a
 * hole: the second cell of the pair is placed from the stance the first one left behind.
 *
 * <p><b>Placing while bridging</b> follows LibreBounce's scaffold: the cell to fill is the one under the body when the
 * feet are off-centre, and the click goes to whichever face reaches it, not only the face square behind. The head
 * squares up to the block for the click itself and returns to the god-bridge angle afterwards, because a corner-faced
 * click needs the crosshair on the corner.
 */
public final class Ninja2Bridge extends BridgeMethod {

    // ---- LibreBounce god-bridge rotation mode ----
    private static final float STRAIGHT_PITCH = 73.5f;
    private static final float DIAGONAL_PITCH = 75.6f;
    private static final float NORMAL_PITCH = 75f;
    /** Only commit to a god-bridge angle this close to an edge. */
    private static final float EDGE_LIMIT = 2.5f;

    // ---- IllusionClient BridgeAssist ----
    private static final double EDGE_OFFSET = 0.24;
    /** How long the sneak stays down after leaving the edge: 85 ms, the assist's default. */
    private static final int UNSNEAK_TICKS = 2;
    private static final int PLACEMENT_TICKS = 20;

    private float movingYaw;
    private boolean straight;          // travel along an axis, so the head takes the 45 degree carry
    private boolean onRightSide;

    private boolean sneaking;
    private boolean armed;
    private boolean sneakedSincePlace;
    private boolean pendingSneak;
    private boolean holdSneak;
    private int unsneakTicks;
    private int placementTicks;
    private int latchedPlacements;
    private float carriedYaw;

    @Override public Bridger.Tech tech() { return Bridger.Tech.NINJA2; }

    @Override
    protected void configure(Bridger b) {
        crouch = Crouch.TAP;
        // direction is the facing: the movement is backwards along the travel, so the pose looks back down the bridge
        movingYaw = snap45(AIMath.wrap(travelYaw + 180f));
        straight = Math.abs(mod90(movingYaw)) < 0.01f;
        onRightSide = b.ctx.rng.nextBoolean();
        carriedYaw = AIMath.wrap(movingYaw);
        offset = 0f;
        hTarget = EDGE_OFFSET;
        depth = 0.25;
        eyeH = 1.62;
        // The click needs a pitch that actually meets the support's face - that is physics. The god-bridge 73.5 is
        // the look-down angle a client wears between blocks, so it is applied by walkPitch below, not here.
        pitch = pitchFloor(facePitch(eyeH, depth, hTarget, 0f));
        wobbleAmp = 0;
        // the corner is what answers the click, so any face that reaches the next cell will do
        anyFace = true;
    }

    /**
     * The god-bridge pose, except for the tick or two where the click actually goes out: then the head squares up to
     * the support so the ray meets a face that reaches the next cell. Without that the carry angle would hold the
     * crosshair on a corner all the way across and nothing would ever place.
     */
    @Override
    public float lookYaw(Bridger b) {
        if (squaringUp(b)) return AIMath.wrap(travelYaw + 180f);
        if (!nearEdge(b) && carriedYaw != 0f) return carriedYaw;   // keep the pose between edges
        float yaw;
        if (straight) {
            updateSide(b);
            yaw = AIMath.wrap(movingYaw + (onRightSide ? 45f : -45f));
        } else {
            float axis = snap90(b.ctx.c.yaw());
            yaw = AIMath.wrap(axis + 45f);                          // their stationary pose: axis + 45, 75 degrees
        }
        carriedYaw = yaw;
        return yaw;
    }

    /**
     * When the head squares up. The carry pose sits 45 degrees off the line, which is fine while the body is well
     * inside the block but useless for a click: the ray has to be on the support's face, and the mouse takes a few
     * ticks to get there. So the square pose starts while there is still most of a block to walk, not at the lip.
     * (Measured: squaring up at the edge instead placed 20 blocks in 33 bridges; this is the fix.)
     */
    private boolean squaringUp(Bridger b) {
        // Two rules, either of which squares the head up: the crosshair is not already on a face that fills the next
        // cell (the carry pose looks 45 degrees off, so this is most of the walk), or the body is most of a block past
        // the edge. The god-bridge pose is what is left over - the tick or two after a block goes down - which is
        // exactly when it is visible on camera and out of the way of the click.
        return !b.lastRayOn || b.curH > -0.45 || b.lateTicks > 1;
    }

    /**
     * BridgeAssist's crouch state machine. The only addition is {@link #holdSneak}, which a placement that needs a
     * second sneak raises, so the body stays put until that click has landed.
     */
    @Override
    public boolean sneak(Bridger b, double h, boolean clicked, int nx, int nz) {
        boolean edge = h > EDGE_OFFSET;
        // full width past the edge: unmistakably off the block, no offset arithmetic needed
        boolean hardEdge = h > 0.3;   // half a player width: unmistakably off the block

        if (!sneaking) {
            if (edge || hardEdge) {
                sneaking = true;
                armed = true;
                sneakedSincePlace = true;
                unsneakTicks = nextDelay(b);
            }
        } else if (edge || hardEdge || holdSneak) {
            unsneakTicks = nextDelay(b);
        } else if (--unsneakTicks <= 0) {
            sneaking = false;
        }
        // a click that went out uncrouched is followed by one straight away (BridgeAssist's pendingSneak)
        if (pendingSneak) {
            pendingSneak = false;
            if (!sneaking) { sneaking = true; unsneakTicks = nextDelay(b); }
        }
        if (!sneaking) armed = false;
        holdSneak = false;                 // raised again by placementAllowed while the target needs it
        return sneaking;
    }

    /**
     * Withhold the click until the body has backed to the edge on every axis it is travelling along, then let one
     * extra placement go from that stance. On a diagonal that is the second cell of the pair; on a straight bridge
     * there is only ever one axis, so nothing is withheld.
     */
    @Override
    public boolean placementAllowed(Bridger b, double h, double lat, int nx, int nz) {
        if (placementTicks > 0) placementTicks--;
        holdSneak = needsSecondSneak(b, nx, nz);
        if (!diag) return true;
        // an axis that has run out of support still needs the body over it before a diagonal click can land
        boolean both = atEdgeOn(b, b.dirX()) && atEdgeOn(b, b.dirZ());
        if (both) {
            latchedPlacements = 1;
            return true;
        }
        if (latchedPlacements > 0 && b.ctx.c.onGround()) {
            latchedPlacements--;
            return true;
        }
        // never deadlock: a body that has stood on the block this long places anyway rather than walking into the void
        return b.ticksOnBlock() > 20;
    }

    @Override
    public void afterPlacement(Bridger b, boolean wasSneaking) {
        placementTicks = PLACEMENT_TICKS;
        latchedPlacements = 0;
        // BridgeAssist: a click made without the crouch is followed by one, so the next placement is not a bare one
        if (wasSneaking) sneakedSincePlace = true;
        else pendingSneak = true;
        sneakedSincePlace = false;
    }

    // ---- the assist's own arithmetic ----

    /** Does the click need a sneak held past this placement? True when the target cell sits outside the feet box. */
    private boolean needsSecondSneak(Bridger b, int nx, int nz) {
        Location p = b.ctx.pos();
        double fx = p.getX() - Math.floor(p.getX()), fz = p.getZ() - Math.floor(p.getZ());
        boolean insideX = nx == (int) Math.floor(p.getX());
        boolean insideZ = nz == (int) Math.floor(p.getZ());
        if (!insideX && !insideZ) return true;                       // both axes over: a diagonal needs the sneak
        if (!insideX && Math.min(fx, 1 - fx) < 0.3) return false;    // still over this axis
        if (!insideZ && Math.min(fz, 1 - fz) < 0.3) return false;
        return false;
    }

    /** Is the body over the support along one travel axis? */
    private boolean atEdgeOn(Bridger b, int axis) {
        Location p = b.ctx.pos();
        double ox = p.getX() - (b.supportX() + 0.5);
        double oz = p.getZ() - (b.supportZ() + 0.5);
        // which axis, not which sign: a negative direction used to read the other axis and report the wrong edge
        double along = axis != 0 ? ox : oz;
        return along > -0.35;
    }

    /** Their edge test: is there still floor this far along the travel direction? */
    private boolean nearEdge(Bridger b) {
        Location p = b.ctx.pos();
        for (double d = 0.5; d <= EDGE_LIMIT; d += 0.5) {
            int x = (int) Math.floor(p.getX() + vx * d);
            int z = (int) Math.floor(p.getZ() + vz * d);
            int y = (int) Math.floor(p.getY()) - 1;
            if (!Shape.floor(b.ctx.world().shape(x, y, z))) return true;
        }
        return false;
    }

    /**
     * Flip the carry side when the body is leaning off the block and the cell ahead is air: their rule exactly, and
     * the reason a god bridge can cross an edge without the head ever stopping.
     */
    private void updateSide(Bridger b) {
        if (!b.ctx.c.onGround()) return;
        Location p = b.ctx.pos();
        double fx = AIMath.dirX(AIMath.wrap(movingYaw));
        double fz = AIMath.dirZ(AIMath.wrap(movingYaw));
        int bx = (int) Math.floor(p.getX()), by = (int) Math.floor(p.getY()) - 1, bz = (int) Math.floor(p.getZ());
        boolean leaning = !Shape.floor(b.ctx.world().shape(bx, by, bz));
        boolean nextAir = !Shape.floor(b.ctx.world().shape(
                (int) Math.floor(p.getX() + fx * 0.6), by, (int) Math.floor(p.getZ() + fz * 0.6)));
        if (leaning && nextAir) onRightSide = !onRightSide;
    }

    /** Sneak length in ticks, with the assist's wander so it is never the same number twice. */
    private int nextDelay(Bridger b) {
        double ticks = UNSNEAK_TICKS + b.ctx.rng.nextGaussian() * 0.5;
        if (b.ctx.rng.nextDouble() < 0.06) ticks += 1 + b.ctx.rng.nextInt(2);   // occasional longer hold
        return Math.max(0, (int) Math.round(ticks));
    }

    private static float snap45(float yaw) { return Math.round(yaw / 45f) * 45f; }

    private static float snap90(float yaw) { return Math.round(yaw / 90f) * 90f; }

    private static float mod90(float yaw) {
        float m = ((yaw % 90f) + 90f) % 90f;
        return m > 45f ? m - 90f : m;
    }
}
