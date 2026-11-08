package net.h2020.bots.ai.motor;

import net.h2020.api.bot.AimIntent;
import net.h2020.bots.ai.BotContext;
import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.perception.Sighting;
import net.h2020.bots.ai.util.AIMath;
import net.h2020.bots.ai.world.Shape;
import net.h2020.bots.ai.world.WorldView;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

/**
 * Human bridging over the void in a cardinal direction, with the techniques 2020 players actually used.
 * <p>
 * Geometry: the bot faces away from the travel direction (yaw + 180 + technique offset) and walks backwards. Once its
 * eye is beyond the support block's edge by h, a ray at pitch p hits the support's travel-side face when
 * h * tan(p) * cos(offset) > eyeHeight, so the pitch is derived from a technique-specific target h (~0.2-0.27) and
 * only becomes "right" at the edge; the right-click is sent only when the client's own ray (targetedBlock/Face) hits
 * that face, so placement timing comes from real physics, never from a timer. A diagonal offset also moves the ray's
 * hit point sideways (by h * tan(offset)), so diagonal/sideways techniques walk slightly off the line's centre.
 * <p>
 * What makes the techniques look different on camera:
 * <ul>
 * <li>NOOB: half-sideways (50-62 degrees off the back), shift held all the time, walks with A/D, creeps to the edge,
 *   stops after every block, hesitates and looks around, sometimes steps back and tries again.</li>
 * <li>SHIFT: straight back, shift held the whole bridge, steady S, one block per step; can peek up at the
 *   destination while still walking (the crouch keeps it on the edge).</li>
 * <li>SPEED: straight back, S held uncrouched, a short crouch tap only at each edge, small yaw wobble.</li>
 * <li>NINJA: diagonal (about 45 degrees), S + A/D, crouch taps at the edge.</li>
 * <li>MOONWALK: diagonal like ninja but never crouches (the diagonal gives the time to place).</li>
 * <li>BREEZILY: nearly straight back, never crouches, S held while alternating A and D: the body zig-zags.</li>
 * </ul>
 * The technique comes from the persona and is adjusted per bridge (short gaps, long bridges, enemies close). Timing
 * jitter, late clicks caught by a last-instant crouch, step-backs, look-ups at the destination and glances toward
 * noises keep it from looking scripted.
 */
public final class Bridger {
    public enum Result { RUNNING, DONE, FAILED }

    /** Concrete technique for one bridge. */
    public enum Tech { NOOB, SHIFT, SPEED, NINJA, MOONWALK, BREEZILY }

    private enum Crouch { HOLD, TAP, NONE }

    private enum Phase { TURN, MOVE, GLANCE, PAUSE, STEPBACK, RECOVER }

    private final BotContext ctx;
    private Tech tech;
    private Crouch crouch;
    private int dx, dz;               // the axis this block is placed along (alternates on a diagonal bridge)
    private int vx, vz;               // the direction we are travelling (both non-zero = diagonal staircase)
    private boolean diag;
    private int flip;
    /** Ticks left of the diagonal's second placement, made from the same crouched stance instead of walking first. */
    private int duoTicks;
    private int lastStepDx, lastStepDz;   // the axis the last block advanced, for rollback
    private long startTick;               // when this bridge started (for the launch-time stat)
    private boolean launched;
    /** Ticks from start() to the first block going down, accumulated for /bot ml. */
    private static long launchTicks, launchCount, turnTicks, turnCount;
    /** Where the launch time goes, for the first block only: turn ticks, walking ticks, waiting-with-ray-down ticks. */
    private static long firstTurn, firstWalk, firstWait;
    private int preTurn, preWalk, preWait;
    private int sx, sy, sz;           // current support block
    private int needed, placed;
    private Phase phase;
    private int phaseTicks, ticks, ticksOnBlock;
    private float travelYaw, offset, pitch, basePitch;
    /** The one diagonal a ninja bridge is allowed to look down: 45, -45, 135 or -135, nothing in between. */
    private float ninjaYaw;
    private double hTarget, latTarget, lastProg;
    private int unsneakDelay;
    /** Tick the crouch comes up after a tap click; set from skill at each placement. */
    private long unsneakAt = -100;
    private int clickCooldown, clickWait;
    private boolean rayWasOn;
    private int blocksUntilGlance;
    private boolean forgetSneak;
    private boolean active;
    private int offsetSign = 1;
    private int pauseLen;
    private Location glancePoint;
    private float glanceYaw, glancePitch;
    private boolean peek;              // shift bridgers keep walking while looking up
    private boolean pressured;
    private double sloppy;
    private float wobbleAmp, wobbleFreq, wobblePhase;
    private float lastF = -1, lastS = 0;
    private int breezyDir = 1;
    private double breezyAmp;
    private boolean hesitated;
    private int lateTicks;
    private long lastGlanceTick = -1000;
    private boolean unconfirmed;       // support is our client's predicted block, not yet confirmed by the server
    private int invOpenTicks, swapWait;
    private boolean stacking;
    /** 0 = jump, 1 = place, 2 = check the landing. */
    private int stackPhase;
    /** The motor that performs the step up (the pillar already does jump-place with server lag in mind). */
    private Pillarer rise;
    /** Feet height when the step up started: the rise is measured against this, not against bookkeeping. */
    private double feetBefore;
    private int stackTicks;
    /** Deliberate stacks this bridge: 1-2 for a rush, 2-3 when somebody is coming (the "2-stacking" of the guide). */
    private int stacks, stackGoal;
    /** Height this bridge is trying to reach (Integer.MIN_VALUE = a flat bridge, the old behaviour). */
    private int climbY = Integer.MIN_VALUE;
    /** How many blocks go down horizontally before the step up: an N-stack is N+1 forward then one up. */
    private int stackRun = 3;
    /** Set when a step up just happened: the next block tells us whether the bridge carried on from the new level. */
    private boolean riseCheck;
    /** After a step up the bot lands on the new block before it walks anywhere. */
    private int settleTicks;
    /** Sneak is held for 80 ms after leaving the edge (the realistic un-sneak delay), not for the whole bridge. */
    private static final int UNSNEAK_TICKS = 4;
    private int sneakHold;
    /** Clicked without sneaking at an edge: sneak right after the click (place-then-sneak). */
    private boolean pendingSneak;
    /** Placements allowed without sneaking first: a diagonal gives one, straight gives none. */
    private int freePlacements;
    private boolean wasSneaking;
    /** Tick of the last step up, so a fall right after one can be attributed to stacking. */
    private long lastStackTick = -1000;
    /** How many blocks went down since the last stack: "2 forward, 1 up" instead of stacking at every edge. */
    private int lastStackPlaced = -100;

    public Bridger(BotContext ctx) { this.ctx = ctx; }

    public boolean active() { return active; }
    public int placed() { return placed; }
    public int needed() { return needed; }
    public int dirX() { return dx; }
    public int dirZ() { return dz; }
    public Tech tech() { return tech; }

    /** Start bridging `count` blocks in (dx,dz) from the support block under (fx,fy-1,fz). */
    public void start(int dx, int dz, int fx, int fy, int fz, int count) { start(dx, dz, fx, fy, fz, count, Integer.MIN_VALUE); }

    /**
     * Bridge toward a destination that is <i>higher</i>: the same bridge, but it steps up every `stackRun` blocks until
     * it reaches targetY. Without a target height a diagonal bridge stays flat however steep the map is, which is why
     * the bots never staircased toward mid.
     */
    public void start(int dx, int dz, int fx, int fy, int fz, int count, int targetY) {
        this.climbY = targetY;
        if (targetY != Integer.MIN_VALUE && targetY > fy) CombatStats.inc("bridge.climb");
        this.vx = dx; this.vz = dz;
        this.diag = dx != 0 && dz != 0;
        // a diagonal bridge is a staircase: every block shares a face with the last one, the body walks diagonally
        this.flip = 1;
        if (diag) {
            // a diagonal bridge is a staircase of two axes; starting on the axis whose cell is already supported keeps
            // the first block from hanging over the corner with nothing under it
            this.dx = vx; this.dz = 0;
            // start on the axis whose first cell already has floor under it, so the corner is never a hole
            if (!Shape.floor(ctx.world().shape(fx + vx, fy - 1, fz))) { this.dx = 0; this.dz = dz; }
        } else { this.dx = dx; this.dz = dz; }
        this.lastStepDx = this.dx; this.lastStepDz = this.dz;
        this.sneakHold = 0; this.pendingSneak = false; this.wasSneaking = false;
        this.freePlacements = diag ? 1 : 0;
        this.sx = fx; this.sy = fy - 1; this.sz = fz;
        this.needed = diag ? Math.max(2, count * 2) : count; this.placed = 0;
        CombatStats.inc("bridge.start");
        this.startTick = ctx.tick;
        this.launched = false;
        this.preTurn = 0; this.preWalk = 0; this.preWait = 0;
        if (diag) {
            CombatStats.inc("bridge.diag");
            stepLog("diag", ctx.pos());
        }
        this.pressured = underPressure();
        this.tech = chosenTech(count, pressured);
        if (diag && this.tech != Tech.NINJA && ctx.persona.skill.overall > 0.35
                && ctx.rng.nextDouble() < 0.6) {
            this.tech = Tech.NINJA;          // a diagonal wants the tap, not a held shift
            CombatStats.inc("bridge.ninja");
        }
        this.travelYaw = AIMath.yawOf(vx, vz);
        this.phase = Phase.TURN; this.phaseTicks = 0; this.ticks = 0; this.ticksOnBlock = 0;
        this.unsneakDelay = 0; this.clickCooldown = 0; this.clickWait = 0; this.rayWasOn = false;
        this.offsetSign = ctx.rng.nextBoolean() ? 1 : -1;
        double o = ctx.persona.skill.overall;
        this.sloppy = 0.03 + 0.2 * (1 - o) + (pressured ? 0.08 : 0);
        configure();
        // eyes back down the bridge early when somebody is already on it (guide: turn around while bridging)
        this.blocksUntilGlance = enemyBehind() != null ? AIMath.range(ctx.rng, 1, 3) : nextGlanceBlocks();
        this.forgetSneak = false;
        this.hesitated = false;
        this.lateTicks = 0;
        this.unconfirmed = false;
        this.swapWait = 0; this.invOpenTicks = 0;
        this.lastF = -1; this.lastS = 0;
        this.breezyDir = ctx.rng.nextBoolean() ? 1 : -1;
        this.peek = false;
        this.stacking = false;
        this.stackTicks = 0;
        this.stacks = 0;
        this.lastStackPlaced = -100;
        // how many blocks to stack on the way: high ground is worth it when somebody is on the same bridge, and the
        // better the player the more often he bothers to 2-stack a rush (guide: never 1-stack a contested bridge)
        // stacking is not a two-block thing: as many as the situation asks for. A contested bridge gets a real
        // stack (2-4 up), a rush that needs height gets more, a quiet bridge may get none.
        // an N-stack is N+1 blocks forward and then one up, so 2-stacking is 3 forward and 3-stacking is 4 forward.
        // Good players stack more often and in longer runs; a pressured bridge always gets at least one step up.
        this.stackRun = AIMath.range(ctx.rng, 3, 4);                   // 3 = 2-stacking, 4 = 3-stacking
        // no cap: a bridge that wants height keeps taking it for as long as the situation justifies a rise (enemy
        // above, pressure on the line, a climb to a higher destination). The old goal of one or two rises is what
        // stopped bots from ever building up to an island or to build height.
        this.stackGoal = Integer.MAX_VALUE;
        // a bridge to a higher destination is a climb, not an opportunistic stack: as many steps as the height needs
        if (this.climbY != Integer.MIN_VALUE && this.climbY > this.sy + 1) {
            this.stackGoal = Math.max(this.stackGoal, this.climbY - this.sy);
            this.stackRun = 3;
        }
        this.active = true;
    }

    public void cancel() { active = false; }

    /** Someone close by, or we were hit recently: bridge faster and sloppier. */
    private boolean underPressure() {
        if (ctx.perception.enemiesWithin(14) > 0) return true;
        for (Sighting s : ctx.perception.all()) if (ctx.tick - s.lastHurtMeTick < 80) return true;
        return false;
    }

    /**
     * SHIFT and SPEED are disabled, not deleted: if the choice lands on one of them it becomes the tapping diagonal
     * instead. The enum values, their configure() branches and their behaviour all stay exactly as they were, so
     * {@link BridgeTuning#shiftSpeedEnabled} brings them back without a code change.
     */
    private Tech sanction(Tech t) {
        if (BridgeTuning.shiftSpeedEnabled) return t;
        if (t == Tech.SHIFT || t == Tech.SPEED) {
            CombatStats.inc("bridge.tech.disabled");
            return Tech.NINJA;
        }
        return t;
    }

    /** Persona technique, adjusted to the situation (players switch: a quick tap for a 2-block gap, safe on long ones). */
    private Tech chooseTech(int count, boolean pressure) {
        Tech base = ctx.persona.bridgeTech;
        double o = ctx.persona.skill.overall;
        double r = ctx.rng.nextDouble();
        switch (base) {
            case SHIFT:
                if (pressure && r < 0.1 + 0.5 * o) return Tech.SPEED;        // panicking: let go of shift to go faster
                if (count <= 2 && r < 0.45 * o) return Tech.SPEED;           // short gap: a couple of quick taps
                return Tech.SHIFT;
            case SPEED:
                if (count >= 14 && !pressure && r < 0.25) return Tech.SHIFT; // long bridge: play it safe
                if (pressure && o > 0.6 && r < 0.2) return Tech.NINJA;
                return Tech.SPEED;
            case NINJA:
            case MOONWALK:
                if (count <= 2 && r < 0.35) return Tech.SPEED;               // not worth setting up the diagonal
                if (count >= 16 && !pressure && r < 0.2) return Tech.SPEED;
                return base;
            case BREEZILY:
                if (count <= 2 && r < 0.4) return Tech.SPEED;
                if (count >= 18 && !pressure && r < 0.12) return Tech.NINJA;
                return Tech.BREEZILY;
            case NOOB:
            default:
                return base == null ? Tech.SHIFT : base;
        }
    }

    /** The persona's technique after the disabled ones have been mapped away. */
    private Tech chosenTech(int count, boolean pressure) {
        return sanction(chooseTech(count, pressure));
    }

    public static long launchedBridges() { return launchCount; }

    public static double averageLaunchTicks() {
        return launchCount == 0 ? 0 : launchTicks / (double) launchCount;
    }

    /** How long a bridge takes to get its first block down (/bot ml). */
    public static String launchReport() {
        if (launchCount == 0) return "§6[ml] §ebridge launch: §fno bridges yet";
        return String.format(java.util.Locale.ROOT,
                "§6[ml] §ebridge launch: §f%.1f§7 ticks to the first block on average, §f%.1f§7 in the turn (n=%d)",
                launchTicks / (double) launchCount, turnTicks / (double) Math.max(1, launchCount),
                launchCount) + String.format(java.util.Locale.ROOT,
                " §7(first block: turn %.1f, walk %.1f, waiting at the edge %.1f)",
                firstTurn / (double) launchCount, firstWalk / (double) launchCount, firstWait / (double) launchCount);
    }

    private void configure() {
        double eyeH, depth;
        switch (tech) {
            case NOOB:
                crouch = Crouch.HOLD;
                offset = offsetSign * (float) AIMath.range(ctx.rng, 50, 62);
                hTarget = 0.26; depth = 0.45; eyeH = 1.54;
                break;
            case SHIFT:
                crouch = Crouch.HOLD;
                offset = (float) AIMath.range(ctx.rng, -4, 4);
                hTarget = 0.25; depth = 0.28; eyeH = 1.54;
                break;
            case SPEED:
                crouch = Crouch.TAP;
                offset = (float) AIMath.range(ctx.rng, -3, 3);
                hTarget = 0.21; depth = 0.22; eyeH = 1.62;
                break;
            case NINJA:
                crouch = Crouch.TAP;
                // ninja bridging only ever looks down one of four diagonals: 45, -45, 135, -135. The travel is already
                // one of them, so the head takes the canonical angle nearest the back-down-the-bridge direction and
                // the placement gate refuses a click until the head is actually there.
                float back = AIMath.wrap(travelYaw + 180f);
                float[] ninjaAngles = {45f, -45f, 135f, -135f};
                float bestAngle = ninjaAngles[0];
                float bestDiff = Math.abs(AIMath.angleDiff(back, bestAngle));
                for (int k = 1; k < ninjaAngles.length; k++) {
                    float d = Math.abs(AIMath.angleDiff(back, ninjaAngles[k]));
                    if (d < bestDiff) { bestDiff = d; bestAngle = ninjaAngles[k]; }
                }
                ninjaYaw = bestAngle;
                offset = 0f;
                hTarget = 0.22; depth = 0.25; eyeH = 1.62;
                break;
            case MOONWALK:
                crouch = Crouch.NONE;
                offset = offsetSign * (float) AIMath.range(ctx.rng, 40, 50);
                hTarget = 0.2; depth = 0.22; eyeH = 1.62;
                break;
            case BREEZILY:
            default:
                crouch = Crouch.NONE;
                offset = offsetSign * (float) AIMath.range(ctx.rng, 10, 22);
                hTarget = 0.2; depth = 0.2; eyeH = 1.62;
                break;
        }
        double off = Math.toRadians(offset);
        // stand off-centre so the sideways-drifting ray still lands on the face (see class doc)
        latTarget = AIMath.clamp(hTarget * Math.tan(off) * 0.75, -0.28, 0.28);
        double p = Math.toDegrees(Math.atan((eyeH + depth) * Math.abs(Math.cos(off)) / hTarget));
        p += ctx.rng.nextGaussian() * 0.4;
        // crouching beginners start a bit too flat and nudge down at the edge (the clamp keeps them on the block)
        if (crouch == Crouch.HOLD) p -= Math.abs(ctx.rng.nextGaussian()) * 2.5 * (1 - ctx.persona.skill.overall);
        if (p < 79.0) { CombatStats.inc("bridge.pitch.floor"); p = 79.0; }   // flatter than this and the ray misses
        pitch = (float) AIMath.clamp(p, 79, 89);
        if (tech == Tech.NINJA) {
            // 79 degrees is the angle that lands the ray on the face at the ninja technique's edge distance; a few
            // tenths of jitter keeps it human without losing the click
            pitch = (float) (79.0 + ctx.rng.nextGaussian() * 0.25);
        }
        basePitch = pitch;
        wobbleAmp = tech == Tech.SPEED ? (float) AIMath.range(ctx.rng, 1.0, 2.6) : (tech == Tech.SHIFT ? 0.6f : 0f);
        wobbleFreq = (float) AIMath.range(ctx.rng, 0.12, 0.28);
        wobblePhase = (float) (ctx.rng.nextDouble() * Math.PI * 2);
        breezyAmp = AIMath.range(ctx.rng, 0.1, 0.2);
    }

    private int nextGlanceBlocks() {
        double aw = ctx.persona.skill.awareness;
        double o = ctx.persona.skill.overall;
        if (tech == Tech.NOOB) return AIMath.range(ctx.rng, 2, 4);
        // good players do not stop mid-bridge to sightsee: glances are rare, and only when somebody is actually
        // behind them (the look-back that matters) or when they are being pressured
        int lo = (int) (18 + (1 - aw) * 14), hi = (int) (30 + (1 - aw) * 25);
        if (o > 0.6) { lo += 10; hi += 20; }
        if (pressured) { lo = Math.max(6, lo - 8); hi = Math.max(12, hi - 10); }
        return AIMath.range(ctx.rng, lo, hi);
    }

    private float lookYaw() {
        float w = wobbleAmp == 0 ? 0 : wobbleAmp * (float) Math.sin(ticks * wobbleFreq + wobblePhase);
        if (tech == Tech.NINJA) {
            // a ninja head sits on the diagonal: half the usual wobble, and never off the four angles
            return AIMath.wrap(ninjaYaw + Math.max(-3f, Math.min(3f, w * 0.5f)));
        }
        return AIMath.wrap(travelYaw + 180f + offset + w);
    }

    /** Ninja placement is gated on the head actually being on one of the four diagonal angles (within ~10 degrees). */
    private boolean ninjaAngleOk() {
        return tech != Tech.NINJA || Math.abs(AIMath.angleDiff(ctx.c.yaw(), ninjaYaw)) <= 10f;
    }

    /** Signed distance of the body centre past the support's travel-side edge (negative = still on the block). */
    private double pastEdge(Location p) {
        double along = (p.getX() - (sx + 0.5)) * vx + (p.getZ() - (sz + 0.5)) * vz;
        return along - 0.5;
    }

    /** Lateral error of the body centre from the bridge line. */
    private double lateral(Location p) {
        // lateral axis = travel rotated 90deg: (-dz, dx)
        return (p.getX() - (sx + 0.5)) * -vz + (p.getZ() - (sz + 0.5)) * vx;
    }

    public Result tick() {
        if (!active) return Result.FAILED;
        ticks++;
        phaseTicks++;
        ticksOnBlock++;
        WorldView wv = ctx.world();
        Location pos = ctx.pos();

        // fell off?
        if (pos.getY() < sy + 1 - 0.7) {
            if (ctx.tick - lastStackTick < 40) CombatStats.inc("bridge.stack.fell");
            active = false;
            return Result.FAILED;
        }
        // a chest left open (late window from a cancelled loot) freezes keys and mouse: close it
        if (ctx.c.containerOpen() && (!Inv.ownInventoryOpen(ctx.player, ctx.c) || ++invOpenTicks > 8)) { ctx.c.closeWindow(); invOpenTicks = 0; }
        else if (!ctx.c.containerOpen()) invOpenTicks = 0;
        // support advanced (we or someone placed the next block; our own client sees its block before the server does)
        int nx = sx + dx, nz = sz + dz;
        if (unconfirmed) {
            if (Shape.floor(wv.shape(sx, sy, sz))) unconfirmed = false;
            else if (!clientPredicted(sx, sy, sz)) {
                // the server never put it there (rejected): it was a ghost block, back to the real support
                sx -= lastStepDx; sz -= lastStepDz; placed--; unconfirmed = false;
                nx = sx + dx; nz = sz + dz;
            }
        }
        boolean serverHas = Shape.floor(wv.shape(nx, sy, nz));
        if (serverHas || clientPredicted(nx, sy, nz)) {
            unconfirmed = !serverHas;
            lastStepDx = dx; lastStepDz = dz;
            sx = nx; sz = nz;
            placed++;
            if (!wasSneaking) pendingSneak = true;                 // ninja timing: sneak after this click
            if (freePlacements > 0) freePlacements--;
            if (!Shape.floor(wv.shape(nx + dx, sy - 1, nz + dz))) sneakHold = UNSNEAK_TICKS;   // still on the lip
            if (riseCheck) { riseCheck = false; CombatStats.inc("bridge.stack.continue"); }
            if (!launched) {
                launched = true;
                launchTicks += ctx.tick - startTick;
                launchCount++;
                firstTurn += preTurn; firstWalk += preWalk; firstWait += preWait;
            }
            if (diag) {
                // next block goes along the other axis, so the staircase alternates x and z
                if (flip > 0) { dx = 0; dz = vz; } else { dx = vx; dz = 0; }
                flip = -flip;
                // diagonals are placed two at a time: the second block goes down from this same stance, so the bot
                // does not lose a whole walk-to-the-edge cycle for every block (the 2026 movement guide's tempo)
                // DISABLED: the second block of a diagonal pair needs the far face of the block just placed, and from
                // on top of it that face is not in the ray (measured: 184 attempts, 0 landed, 152 timed out). The
                // real technique backs up and places over the void instead; that is a stance change, not a flag.
                duoTicks = 0;
            }
            ticksOnBlock = 0;
            rayWasOn = false;
            clickWait = 0;
            lateTicks = 0;
            hesitated = false;
            // crouch-tap bridgers let go of shift a beat after the click; shift bridgers never do
            // how long shift stays down after the click: 1 tick for a clean hand, more for a sloppier one, plus the
            // occasional extra beat. Release-on-the-click is instant and reads as a machine.
            if (crouch == Crouch.TAP) {
                double skill = ctx.persona.skill.overall;
                int base = skill >= 0.70 ? 1 : skill >= 0.40 ? 2 : 3;
                unsneakDelay = base + (ctx.rng.nextDouble() < 0.25 ? 1 : 0);
            } else {
                unsneakDelay = 0;
            }
            // small re-aim drift between blocks, like a hand resting on the mouse
            if (ctx.rng.nextDouble() < 0.35) pitch = (float) AIMath.clamp(basePitch + ctx.rng.nextGaussian() * (0.25 + 0.4 * sloppy), 79, 89);
            if (placed >= needed && !unconfirmed) {
                if (riseCheck) { riseCheck = false; CombatStats.inc("bridge.stack.stalled"); }
                active = false;
                return Result.DONE;
            }
            blocksUntilGlance--;
            if (tech == Tech.NOOB) {
                phase = Phase.PAUSE; phaseTicks = 0;
                pauseLen = AIMath.range(ctx.rng, pressured ? 2 : 4, pressured ? 8 : 16);
            }
            forgetSneak = tech == Tech.NOOB && ctx.rng.nextDouble() < 0.025 * (1.0 - ctx.persona.skill.overall);
            nx = sx + dx; nz = sz + dz;
        }
        if (placed >= needed && !unconfirmed) { active = false; return Result.DONE; }
        if (phase == Phase.RECOVER) return recover(pos);
        if (ticksOnBlock > (tech == Tech.NOOB ? 240 : 120)) return giveUp();
        double h = pastEdge(pos);
        if (!launched && phase == Phase.MOVE) {
            preWalk++;
            if (h > -0.3) preWait++;                 // already at the edge: any extra tick here is the ray/pitch
        }
        double lat = lateral(pos);
        if (!Inv.holding(ctx.player, Inv.BLOCK)) {
            if (!Inv.has(ctx.player, Inv.BLOCK)) return giveUp();
            // stack ran out. Refilling the hotbar means opening the inventory, which lets go of shift: first crouch-step
            // back onto the block like a player would
            if (Inv.findHotbar(ctx.player, Inv.BLOCK) < 0 && h > -0.3) {
                ctx.motor.sneak(true);
                ctx.motor.move(1, 0);
                return Result.RUNNING;
            }
            // (a hotbar swap is a window click the server applies one ping later: wait for it instead of clicking
            // again every tick, which just swaps the stack back and forth)
            if (swapWait > 0 && Inv.findHotbar(ctx.player, Inv.BLOCK) < 0) { swapWait--; ctx.motor.sneak(true); return Result.RUNNING; }
            if (!Inv.equip(ctx.player, ctx.c, Inv.BLOCK, 1)) {
                swapWait = 8;
                ctx.motor.sneak(true);
                return Result.RUNNING;
            }
        }

        switch (phase) {
            case TURN: {
                // the aim only has to be good enough to hit the support's face once the eye passes the edge, so the
                // turn is a hard flick on the first ticks and the backing-up starts on the very next tick: the rest of
                // the correction happens on the way out. (This is the difference between a ~700 ms and a ~200 ms
                // launch, and it is what "start building the moment you decide to bridge" looks like.)
                turnTicks++;
                if (!launched) preTurn++;
                float flick = phaseTicks < 3 ? 0.95f : 0.6f;
                ctx.motor.aimRotation(Motor.P_TASK, lookYaw(), pitch, AimIntent.PRECISE, flick);
                ctx.motor.sneak(crouch == Crouch.HOLD || h > -0.35);
                if ((phaseTicks >= 1 && aimError() < 70) || phaseTicks >= 2) { phase = Phase.MOVE; phaseTicks = 0; }
                return Result.RUNNING;
            }
            case PAUSE: {
                // beginners stop after each block (and sometimes at the edge), sometimes peeking around
                ctx.motor.sneak(true);
                if (pauseLen > 12 && phaseTicks > 3 && phaseTicks < pauseLen - 4) {
                    if (phaseTicks == 4) { glanceYaw = AIMath.wrap(ctx.c.yaw() + (float) ctx.rng.nextGaussian() * 50f); glancePitch = (float) AIMath.range(ctx.rng, 15, 50); }
                    ctx.motor.aimRotation(Motor.P_TASK, glanceYaw, glancePitch, AimIntent.GLANCE, 4);
                } else {
                    ctx.motor.aimRotation(Motor.P_TASK, lookYaw(), pitch, AimIntent.PRECISE, 0.6);
                }
                if (phaseTicks > pauseLen) { phase = blocksUntilGlance <= 0 ? Phase.GLANCE : Phase.MOVE; phaseTicks = 0; }
                return Result.RUNNING;
            }
            case STEPBACK: {
                // missed the face: crouch-step back onto the block, then walk out again
                ctx.motor.sneak(true);
                ctx.motor.aimRotation(Motor.P_TASK, lookYaw(), pitch, AimIntent.PRECISE, 0.6);
                ctx.motor.move(1, 0);
                if (phaseTicks >= pauseLen || h < -0.3) { phase = Phase.MOVE; phaseTicks = 0; }
                return Result.RUNNING;
            }
            case GLANCE: {
                boolean safeStand = crouch == Crouch.HOLD || h > -0.15;
                ctx.motor.sneak(safeStand);
                if (glancePoint != null) ctx.motor.aimPoint(Motor.P_TASK, glancePoint, AimIntent.GLANCE, 1.0);
                else {
                    if (phaseTicks == 1) {
                        glanceYaw = AIMath.wrap(travelYaw + (float) ctx.rng.nextGaussian() * 15f);
                        glancePitch = (float) AIMath.range(ctx.rng, 5, 25);
                    }
                    ctx.motor.aimRotation(Motor.P_TASK, glanceYaw, glancePitch, AimIntent.GLANCE, 4);
                }
                // a shift bridger keeps backing up while looking: the crouch holds it on the edge
                if (peek) steer(lat - latTarget, h);
                int len = peek ? 8 : 10 + (tech == Tech.NOOB ? 10 : 0);
                if (phaseTicks > len) {
                    phase = Phase.TURN; phaseTicks = 0; blocksUntilGlance = nextGlanceBlocks(); peek = false;
                }
                return Result.RUNNING;
            }
            default:
                break;
        }

        // MOVE
        boolean mayGlance = ctx.tick - lastGlanceTick > 50 && !unconfirmed;
        if (h < -0.25 && mayGlance && ctx.perception.glanceDue()) {
            // heard something (someone bridging at us?): look up from the bridge toward it
            glancePoint = ctx.perception.takeGlance();
            phase = Phase.GLANCE; phaseTicks = 0; peek = false; lastGlanceTick = ctx.tick;
            return Result.RUNNING;
        }
        if (blocksUntilGlance <= 0 && h < -0.25 && mayGlance && (enemyBehind() != null || ctx.persona.skill.overall < 0.55)) {
            CombatStats.inc("bridge.glance");
            glancePoint = enemyBehind(); phase = Phase.GLANCE; phaseTicks = 0; lastGlanceTick = ctx.tick;
            peek = tech == Tech.SHIFT && ctx.rng.nextDouble() < 0.7;
            return Result.RUNNING;
        }
        // beginners stop at the edge to think about it
        if (tech == Tech.NOOB && !hesitated && h > -0.05) {
            hesitated = true;
            double r = ctx.rng.nextDouble();
            if (r < 0.12) { phase = Phase.STEPBACK; phaseTicks = 0; pauseLen = AIMath.range(ctx.rng, 3, 6); return Result.RUNNING; }
            if (r < 0.35) { phase = Phase.PAUSE; phaseTicks = 0; pauseLen = AIMath.range(ctx.rng, 8, 26); return Result.RUNNING; }
        }
        // crouch only once the eye is nearly past the edge: sneaking for the whole approach is a third of the walking
        // speed, and the approach is the entire remaining delay (measured: 13 ticks of "walk" vs 2 of "turn")
        ctx.motor.sneak(h > -0.12 || crouch == Crouch.HOLD);
        // pre-aim the pitch the eye will need once it is past the edge (the mouse converges in a few ticks; aiming
        // at "the pitch for where I am right now" means arriving at the edge with the wrong angle and waiting there)
        float wantPitch = h < -0.22 ? placePitch(pos, 0.06) : placePitch(pos, h);
        ctx.motor.aimRotation(Motor.P_TASK, lookYaw(), wantPitch, AimIntent.PRECISE, h < -0.22 ? 0.6 : 0.35);
        if (Math.abs(AIMath.angleDiff(ctx.c.yaw(), lookYaw())) > 50) {
            // the mouse is somewhere else (flicked to a hit, looking at someone): don't walk blind over the void
            ctx.motor.move(0, 0);
            return Result.RUNNING;
        }
        boolean clicked = false;
        // place when the client's ray is on the support's travel-side face
        if (clickCooldown > 0) clickCooldown--;
        Block tb = ctx.c.targetedBlock();
        BlockFace tf = ctx.c.targetedFace();
        BlockFace want = faceOf(dx, dz);
        boolean rayOn = tb != null && tf == want && tb.getX() == sx && tb.getY() == sy && tb.getZ() == sz;

        if (settleTicks > 0) {
            settleTicks--;
            ctx.motor.move(0, 0);
            ctx.motor.sneak(true);
            return Result.RUNNING;
        }

        if (shouldStack(h, pos)) {
            stacking = true;
            stackPhase = 0;
            feetBefore = pos.getY();
            rise = null;
            stackTicks = 0;
            ctx.motor.sneak(true);
            ctx.motor.aimRotation(Motor.P_TASK, lookYaw(), pitch, AimIntent.PRECISE, 0.35);
            return Result.RUNNING;
        }

        if (stacking) {
            stackTicks++;
            ctx.motor.move(0, 0);
            ctx.motor.sneak(false);                                  // a crouched client does not leave the ground
            if (rise == null) {
                rise = new Pillarer(ctx);
                rise.start((int) Math.floor(pos.getX()), (int) Math.floor(pos.getY()),
                        (int) Math.floor(pos.getZ()), 1);
                feetBefore = pos.getY();
            }
            Pillarer.Result r = rise.tick();
            if (r == Pillarer.Result.DONE) {
                rise = null;
                stacking = false;
                stackTicks = 0;
                // the pillar motor counts the block it placed, not where the body ended up: verify the rise physically
                // before the bridge is allowed to continue at the new level
                if (pos.getY() < feetBefore + 0.9) {
                    CombatStats.inc("bridge.stack.notup");
                    ctx.motor.jump(true);                             // one more hop onto the block we just placed
                    settleTicks = 3;
                    return Result.RUNNING;
                }
                sy = (int) Math.floor(pos.getY() - 0.05);             // the bridge continues from the block under the feet
                stacks++;
                lastStackPlaced = placed;
                riseCheck = true;
                settleTicks = 3;
                lastStackTick = ctx.tick;
                CombatStats.inc("bridge.stack");
                CombatStats.inc(stackRun >= 4 ? "bridge.stack.run4" : "bridge.stack.run3");
                stepLog("stack", ctx.pos());
                return Result.RUNNING;
            }
            if (r == Pillarer.Result.FAILED) {
                rise = null;
                stacking = false;
                stackTicks = 0;
                ctx.motor.sneak(true);
                CombatStats.inc("bridge.stack.failland");
                return Result.RUNNING;
            }
            if (stackTicks > 60) {                                    // never leave the stack half-done
                rise.cancel();
                rise = null;
                stacking = false;
                stackTicks = 0;
                CombatStats.inc("bridge.stack.abort");
            }
            return Result.RUNNING;
        }

        if (settleTicks > 0) {
            settleTicks--;
            ctx.motor.move(0, 0);
            ctx.motor.sneak(true);
            return Result.RUNNING;
        }

        if (shouldStack(h, pos)) {
            stacking = true;
            stackPhase = 0;
            feetBefore = pos.getY();
            stackTicks = 0;
            ctx.motor.sneak(true);
            ctx.motor.aimRotation(Motor.P_TASK, lookYaw(), pitch, AimIntent.PRECISE, 0.35);
            return Result.RUNNING;
        }

        // on a diagonal the body has to be on the line before the click: a block placed from a drifted position leaves
        // a hole in the staircase
        boolean onLine = !diag || Math.abs(lat - latTarget) < (duoTicks > 0 ? 0.30 : 0.18);
        if (rayOn && onLine && !ninjaAngleOk()) CombatStats.inc("bridge.ninja.offangle");
        if (rayOn && onLine && ninjaAngleOk() && wv.passable(nx, sy, nz)) {
            if (!rayWasOn) {
                rayWasOn = true;
                // reaction: usually on the beat, sometimes a tick or two late (then the last-instant crouch saves it)
                // (sneakless bridgers live on that timing: only their sloppiness shows, as a save-crouch)
                clickWait = ctx.rng.nextDouble() < sloppy ? AIMath.range(ctx.rng, 1, 3)
                        : (crouch != Crouch.NONE && ctx.rng.nextDouble() < 0.25 ? 1 : 0);
            }
            if (clickWait > 0) clickWait--;
            else if (clickCooldown == 0) {
                ctx.c.rightClick();
                clicked = true;
                clickCooldown = Math.max(1, AIMath.msToTicks(ctx.rng, ctx.persona.bridgeClickMs) - 1);
            }
        }
        if (duoTicks > 0) {
            // second half of the pair: stay crouched on the block and aim at the face the next cell needs
            if (clickCooldown == 0) duoTicks--;   // the click delay runs first; the window is time-to-click, not ticks
            // the guide's tempo: back up to the edge of the block first, crouched, then place from there - the
            // backward step is what puts the far face of the new block inside the ray
            // step onto the block just placed: only from on top of it is the side face the next cell needs visible
            ctx.motor.move(duoTicks > 11 ? 1 : 0, 0);
            ctx.motor.sneak(true);
            Location facePt = new Location(ctx.player.getWorld(), sx + 0.5 + dx * 0.55, sy + 0.5 + 0.35, sz + 0.5 + dz * 0.55);
            ctx.motor.aimPoint(Motor.P_TASK, facePt, AimIntent.PRECISE);
            if (clicked) { duoTicks = 0; CombatStats.inc("bridge.duo.ok"); }
            else if (duoTicks == 0) CombatStats.inc("bridge.duo.miss");
        } else {
            steer(lat - latTarget, h);
        }
        sneakPolicy(h, clicked, nx, nz);

        // overshot the face and still nothing placed: step back and try again (crouching styles are held by the clamp)
        if (h > hTarget + 0.02 && ctx.motor.sneaking()) lateTicks++; else lateTicks = 0;
        if (lateTicks > 6 && ctx.rng.nextDouble() < 0.3) {
            lateTicks = 0;
            phase = Phase.STEPBACK; phaseTicks = 0; pauseLen = AIMath.range(ctx.rng, 2, 4);
        }
        return Result.RUNNING;
    }

    /** True when we are already at least as high as every enemy we can see. */
    private boolean alreadyHighest() {
        Location me = ctx.pos();
        for (Sighting st : ctx.perception.all()) {
            if (!st.known || st.ally || st.lastPos == null) continue;
            Location e = st.estimate(ctx.tick);
            if (e == null || e.getWorld() != me.getWorld()) continue;
            if (e.getY() >= me.getY() - 0.5) return false;
        }
        return true;
    }

    /** Somebody is standing above us on this line (the reason to stack right now rather than two blocks later). */
    private boolean enemyAbove() {
        Location me = ctx.pos();
        for (Sighting s : ctx.perception.all()) {
            if (!s.known || s.ally || s.lastPos == null) continue;
            Location e = s.estimate(ctx.tick);
            if (e == null || e.getWorld() != me.getWorld()) continue;
            double dy = e.getY() - me.getY();
            if (dy < 1.2 || dy > 6) continue;
            if (e.distance(me) < 16) return true;
        }
        return false;
    }

    /** Eye level of a known enemy behind us on the line we are bridging away from (null if nobody is there). */
    private Location enemyBehind() {
        Location me = ctx.pos();
        Location best = null;
        double bd = Double.MAX_VALUE;
        for (Sighting s : ctx.perception.all()) {
            if (!s.known || s.ally || s.lastPos == null) continue;
            Location e = s.estimate(ctx.tick);
            if (e == null || e.getWorld() != me.getWorld()) continue;
            double ex = e.getX() - me.getX(), ez = e.getZ() - me.getZ();
            double d = Math.sqrt(ex * ex + ez * ez);
            if (d < 2 || d > 24) continue;
            double behind = (ex * vx + ez * vz) / d;   // -1 = straight behind us
            if (behind > -0.25) continue;
            if (d < bd) { bd = d; best = e.clone().add(0, 1.2, 0); }
        }
        return best;
    }

    /** Legit players stack up during a rush or when a nearby enemy forces the pace, by jumping and placing a block
     * underneath their feet instead of just walking the bridge line. This is a high-pressure, short-lived tactic, not
     * a constant pillar. */
    private boolean shouldStack(double h, Location pos) {
        // a climb ignores the opportunistic rules: it has to gain the height it was started for
        if (climbY != Integer.MIN_VALUE && sy < climbY - 1) {
            if (placed - lastStackPlaced < stackRun) return false;
            if (!ctx.c.onGround() || placed < 1 || !Inv.holding(ctx.player, Inv.BLOCK)) return false;
            double cx = pos.getX() - (sx + 0.5), cz = pos.getZ() - (sz + 0.5);
            if (cx * cx + cz * cz > 0.20) return false;
            if (h > 0.02 || h < -0.6) return false;
            return true;
        }
        if (stacks >= stackGoal) return false;   // uncapped in practice: stackGoal is Integer.MAX_VALUE
        // the pattern is stackRun blocks forward then one up (an N-stack is N+1 forward); somebody standing above us
        // shortens that to right now
        if (placed - lastStackPlaced < stackRun && !enemyAbove()) return false;
        // uncapped does not mean pointless: with nobody above us and nobody to climb to, height is just blocks spent
        if (climbY == Integer.MIN_VALUE && !enemyAbove() && alreadyHighest()) {
            CombatStats.inc("bridge.stack.noNeed");
            return false;
        }
        if (ctx.perception.enemiesWithin(22) == 0) return false;
        if (!ctx.c.onGround()) return false;
        // the body has to be over the block it is standing on: a jump-place made from past the edge lands in the void,
        // which is exactly how bots used to fall while stacking
        double ox = pos.getX() - (sx + 0.5), oz = pos.getZ() - (sz + 0.5);
        if (ox * ox + oz * oz > 0.20) return false;                 // within ~0.45 of the support centre
        if (h > 0.02 || h < -0.6) return false;
        if (placed < 1 || !Inv.holding(ctx.player, Inv.BLOCK)) return false;
        // a planned stack goes up as soon as the body is at the edge; the opportunistic one stays a reaction
        if (stackGoal > 0) return ctx.rng.nextDouble() < (pressured ? 0.55 : 0.35);
        return ctx.rng.nextDouble() < 0.12;
    }

    /**
     * The technique pitch only lines the ray up with the face at the target edge distance. Overshooting (lag, a late
     * crouch) the ray passes under the support (or into the block below it) and no click ever lands, so the player
     * nudges the mouse toward the face like anyone would.
     */
    private float placePitch(Location pos, double h) {
        // (crouching at the edge clamp the body stops near 0.3: a slightly-off pitch shows as lingering there)
        if (h <= hTarget + 0.04 && !(lateTicks > 3 && h > hTarget - 0.02)) return pitch;
        double drop = pos.getY() + 1.58 - (sy + 0.5);
        double p = Math.toDegrees(Math.atan(drop * Math.cos(Math.toRadians(offset)) / h));
        if (p < 79.0) { CombatStats.inc("bridge.pitch.floor"); p = 79.0; }
        return (float) AIMath.clamp(p, 79, 89);
    }

    /** Giving up mid-bridge: first crouch-walk back onto the support, never just let go of shift over the void. */
    private Result giveUp() {
        if (riseCheck) { riseCheck = false; CombatStats.inc("bridge.stack.stalled"); }
        phase = Phase.RECOVER;
        phaseTicks = 0;
        // this tick too: one tick without shift at the edge is all it takes to slide off
        ctx.motor.sneak(true);
        ctx.motor.move(0, 0);
        return Result.RUNNING;
    }

    /** One line per staircase step, so a replay can be cut around it without guessing. */
    private void stepLog(String kind, Location at) {
        if (at == null) return;
        org.bukkit.Bukkit.getLogger().info("[Bots] bridge.step kind=" + kind + " bot=" + ctx.player.getName()
                + " at=" + String.format("%.1f,%.1f,%.1f", at.getX(), at.getY(), at.getZ())
                + " t=" + System.currentTimeMillis());
    }

    private Result recover(Location pos) {
        double h = pastEdge(pos);
        ctx.motor.sneak(true);
        double tx = sx + 0.5 - pos.getX(), tz = sz + 0.5 - pos.getZ();
        if (h < -0.2 || phaseTicks > 40) { ctx.motor.move(0, 0); active = false; return Result.FAILED; }
        ctx.motor.moveToward(tx, tz);
        return Result.RUNNING;
    }

    /**
     * Key choice. Candidates are S, S+A, S+D, A, D, W+A, W+D; each technique has its habitual keys (S for straight
     * back, S + one strafe for diagonals, strafing for half-sideways beginners) and only corrects with another
     * combo when the body has drifted off the line, holding a choice for a few ticks like fingers do. Breezily
     * alternates A and D on purpose.
     */
    private void steer(double err, double h) {
        float yaw = ctx.c.yaw();
        double rad = Math.toRadians(yaw);
        double fx = -Math.sin(rad), fz = Math.cos(rad);       // look dir
        double lx = Math.cos(rad), lz = Math.sin(rad);        // left (A)
        double latX = -vz, latZ = vx;

        if (tech == Tech.BREEZILY) {
            // S held, A/D swapped whenever the body has swung past its amplitude on the current side
            double latA = ((-fx + lx) * latX + (-fz + lz) * latZ), latD = ((-fx - lx) * latX + (-fz - lz) * latZ);
            if (latA * latD < 0) {                       // the two diagonals drift to opposite sides, as they should
                int toPlus = latA > 0 ? 1 : -1;          // key that drifts toward +lateral
                if (err > breezyAmp) breezyDir = -toPlus;
                else if (err < -breezyAmp) breezyDir = toPlus;
                setKeys(-1, breezyDir);
                return;
            }
            // mouse not where it should be (both diagonals drift the same way): plain line keeping below
        }

        float[][] opts = {{-1, 0}, {-1, 1}, {-1, -1}, {0, 1}, {0, -1}, {1, 1}, {1, -1}};
        float bf = -1, bs = 0;
        double best = Double.MAX_VALUE;
        for (float[] o : opts) {
            double mx = o[0] * fx + o[1] * lx, mz = o[0] * fz + o[1] * lz;
            double len = Math.sqrt(mx * mx + mz * mz);
            mx /= len; mz /= len;
            double prog = mx * vx + mz * vz;
            if (prog < 0.3) continue;
            double latV = mx * latX + mz * latZ;
            double predicted = err + latV * 0.35;
            double score = Math.abs(predicted) * 5.0 - prog * 1.2 + habit(o[0], o[1]);
            if (o[0] != lastF || o[1] != lastS) score += 0.45; // fingers stay on the keys they hold
            if (score < best) { best = score; bf = o[0]; bs = o[1]; }
        }
        // beginners creep the last stretch before the edge
        if (tech == Tech.NOOB && h > -0.15 && ctx.tick % 2 == 0) { ctx.motor.move(0, 0); return; }
        setKeys(bf, bs);
    }

    private double habit(float f, float s) {
        switch (tech) {
            case SHIFT: case SPEED:
                return f == -1 && s == 0 ? 0 : (f == -1 ? 0.35 : 0.9);
            case NINJA: case MOONWALK:
                return f == -1 && s != 0 ? 0 : 0.3;
            default:
                return 0;
        }
    }

    private boolean clientPredicted(int x, int y, int z) {
        return ctx.c instanceof net.h2020.bots.client.VirtualClient
                && ((net.h2020.bots.client.VirtualClient) ctx.c).phys.isPredicted(x, y, z);
    }

    private double aimError() {
        double ey = AIMath.angleDiff(ctx.c.yaw(), lookYaw()), ep = ctx.c.pitch() - pitch;
        return Math.sqrt(ey * ey + ep * ep);
    }

    private void setKeys(float f, float s) {
        lastF = f; lastS = s;
        double rad = Math.toRadians(ctx.c.yaw());
        double mx = f * -Math.sin(rad) + s * Math.cos(rad), mz = f * Math.cos(rad) + s * Math.sin(rad);
        double len = Math.sqrt(mx * mx + mz * mz);
        lastProg = len < 1e-6 ? 0 : (mx * dx + mz * dz) / len;
        ctx.motor.move(f, s);
    }

    /**
     * Sneak discipline, modelled on a client bridge assist (IllusionClient's BridgeAssist): sneak only while the next
     * cell is an open edge, hold the sneak for 80 ms after leaving that edge, sneak again right after a click that was
     * made without sneaking, and allow a free placement (the diagonal case) before sneak is required at all.
     * <p>
     * The persona's crouch style still decides the *feel*: a SHIFT bridger holds shift for the whole bridge, a TAP
     * bridger only around the edge, and the sneakless styles (MOONWALK/BREEZILY) keep their stutter and save-crouch.
     */
    private void sneakPolicy(double h, boolean clicked, int nx, int nz) {
        boolean edge = !Shape.floor(ctx.world().shape(nx, sy - 1, nz));
        if (edge) sneakHold = Math.max(sneakHold, UNSNEAK_TICKS);
        if (pendingSneak) { sneakHold = Math.max(sneakHold, UNSNEAK_TICKS); pendingSneak = false; }
        boolean sneak;
        if (crouch == Crouch.NONE) {
            // sneakless: never crouch on purpose; short the step when the ray would pass the face, and keep the
            // last-instant save-crouch for a real miss
            org.bukkit.util.Vector v = ctx.c.velocity();
            double vAlong = Math.max(0, v.getX() * vx + v.getZ() * vz);
            double dKeys = vAlong + 0.1 * Math.max(0, lastProg);
            if (h + dKeys <= 0.28) sneak = false;
            else if (clicked || h + vAlong <= 0.28) { ctx.motor.move(0, 0); sneak = false; }
            else sneak = true;
            if (freePlacements > 0 && edge) sneak = false;
        } else if (crouch == Crouch.TAP) {
            // ninja timing: the tap exists to stop the slide for the tick the click happens, not to hold the body still.
            // Once the block is down the key comes straight back up, so the next stretch is walked at full speed.
            org.bukkit.util.Vector v = ctx.c.velocity();
            double vAlong = Math.max(0, v.getX() * vx + v.getZ() * vz);
            if (clicked) {
                unsneakAt = ctx.tick + 1 + unsneakDelay;
                sneakHold = 0;
                net.h2020.api.stats.CombatStats.inc("bridge.unsneak." + (1 + unsneakDelay));
            }
            if (ctx.tick < unsneakAt) sneak = true;                       // the tap is still held
            else sneak = (h > -0.02) || (h + vAlong * 2.0 > 0.12);
            // if the head is off the diagonal the click is on hold, so the body has to be on hold too: this is the
            // tick where a tap bridger slides off the lip waiting for the aim to come back
            if (!ninjaAngleOk()) sneak = true;
            if (freePlacements > 0 && edge) sneak = false;
        } else {
            sneak = sneakHold > 0;
            if (crouch == Crouch.HOLD && (!forgetSneak || h < -0.3)) sneak = true;
            if (freePlacements > 0 && edge) sneak = false;           // the one free diagonal placement
            org.bukkit.util.Vector v = ctx.c.velocity();
            double vAlong = Math.max(0, v.getX() * vx + v.getZ() * vz);
            double hNext = h + vAlong * 1.2 + 0.03;
            if (!sneak && (h > 0.16 || hNext > 0.22) && !(tech == Tech.NOOB && forgetSneak)) sneak = true;
        }
        if (sneakHold > 0) sneakHold--;
        ctx.motor.sneak(sneak);
        wasSneaking = sneak;
    }

    public static BlockFace faceOf(int dx, int dz) {
        if (dx > 0) return BlockFace.EAST;
        if (dx < 0) return BlockFace.WEST;
        if (dz > 0) return BlockFace.SOUTH;
        return BlockFace.NORTH;
    }
}
