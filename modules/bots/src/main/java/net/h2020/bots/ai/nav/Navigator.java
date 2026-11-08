package net.h2020.bots.ai.nav;

import net.h2020.api.bot.AimIntent;
import net.h2020.api.bot.BotSkill;
import net.h2020.bots.ai.BotContext;
import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.motor.BlockBreaker;
import net.h2020.bots.ai.motor.Bridger;
import net.h2020.bots.ai.motor.Motor;
import net.h2020.bots.ai.motor.Pillarer;
import net.h2020.bots.ai.util.AIMath;
import net.h2020.bots.ai.world.Shape;
import net.h2020.bots.ai.world.WorldView;
import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.List;

/**
 * Path following with human steering. Plans through PathService (time-sliced A*), then drives the keys:
 * pure-pursuit look-ahead on the smoothed polyline with personal weaving, sprint and sprint-jumping on straights,
 * slowing and crouching near void edges, jump timing for steps/parkour, ladder/swim handling, and hands action
 * edges (bridge, pillar, break) to the motor routines. Incremental replanning when the goal moves or the bot
 * deviates, and staged stuck recovery (jump, wiggle, repath).
 */
public final class Navigator {
    public enum State { IDLE, PLANNING, MOVING, ARRIVED, FAILED }

    private final BotContext ctx;
    private final Bridger bridger;
    private final net.h2020.bots.ai.motor.DiagonalBridge diagBridge;
    private boolean diagMode;
    private final Pillarer pillarer;
    private final NeoPlanner neo;
    private long lastNeoTry = -1000;
    private long lastBridgeTick = -10000;
    private boolean anyYGoal;
    private final BlockBreaker breaker;

    private State state = State.IDLE;
    private Location goal;
    private double radius;
    private NavOptions opts;
    private PathService.Ticket ticket;
    private Path path;
    private int idx;
    private long planTick = -1000;
    private int failures;
    private int replans;

    // stuck detection
    private Location stuckRef;
    private long stuckRefTick;
    private int stuckLevel;
    private boolean stuckRefHeld = true;
    private int wiggleTicks;
    private float wiggleStrafe;

    private boolean actionRunning;
    private MoveType action;
    private int actionEndIdx;
    private double weavePhase;
    private float navPitch;
    private boolean safeToGlance;
    private int jumpCooldown;
    private boolean dirty;
    private long failTick = -10000;

    public Navigator(BotContext ctx) {
        this.ctx = ctx;
        this.bridger = new Bridger(ctx);
        this.diagBridge = new net.h2020.bots.ai.motor.DiagonalBridge(ctx);
        this.pillarer = new Pillarer(ctx);
        this.neo = new NeoPlanner(ctx);
        this.breaker = new BlockBreaker(ctx);
        this.weavePhase = ctx.rng.nextDouble() * 100;
        this.navPitch = (float) AIMath.gauss(ctx.rng, 9, 6, -5, 25);
    }

    public State state() { return state; }
    public Location goal() { return goal; }
    public boolean active() { return state == State.PLANNING || state == State.MOVING; }
    public boolean arrived() { return state == State.ARRIVED; }
    public boolean failed() { return state == State.FAILED; }
    public boolean doingAction() { return actionRunning; }
    public boolean bridging() { return actionRunning && action == MoveType.BRIDGE; }
    public long lastBridgeTick() { return lastBridgeTick; }

    /** Short state for debugging, e.g. "MOVING walk#12/30" or "MOVING BRIDGE". */
    public String describe() {
        if (actionRunning) return state + " " + action;
        if (path != null && idx < path.size()) return state + " " + path.points.get(idx).move + "#" + idx + "/" + path.size();
        return String.valueOf(state);
    }
    public Bridger bridger() { return bridger; }

    /** Lifetime counters for behaviour stats: stuck episodes, failed goals, ticks spent waiting for a route. */
    public int stuckEpisodes, failuresTotal;
    public long planningTicks;
    /** Straight, safe stretch: brain may let the gaze look around without endangering the walk. */
    public boolean safeToGlance() { return safeToGlance; }
    public Path path() { return path; }

    /** Cells this bot physically got stuck on while heading to the current goal; planned around on replans. */
    private final java.util.Set<Long> stuckCells = new java.util.HashSet<>();
    private Location stuckGoal;

    /** Radius goal with the default options: "get within `radius` of here". */
    public void goTo(Location target, double radius) { goTo(target, radius, NavOptions.walk()); }

    /**
     * Baritone's GoalXZ: reach this column (within `radius`), any floor of it. Island travel, mid approaches and
     * "get off this bridge" moves all know the X/Z and not the Y, and demanding the exact Y makes the bot wander the
     * island looking for a floor it was already standing on.
     */
    public void goToXZ(Location column, double radius) {
        if (column == null) { stop(); return; }
        this.anyYGoal = true;
        try { goTo(column, Math.max(1.0, radius), NavOptions.walk()); } finally { this.anyYGoal = false; }
    }

    public void goTo(Location target, double radius, NavOptions options) {
        if (target == null) { stop(); return; }
        // (compared with the last goal even after stop(): a brain that gives up and re-sends the same target must not
        // forget where it got stuck, or it walks into the same wall forever)
        if (stuckGoal == null || stuckGoal.getWorld() != target.getWorld() || stuckGoal.distanceSquared(target) > 16.0) stuckCells.clear();
        stuckGoal = target.clone();
        boolean sameGoal = goal != null && goal.getWorld() == target.getWorld()
                && goal.distanceSquared(target) < 1.0 && Math.abs(this.radius - radius) < 0.01;
        if (sameGoal) {
            if (active()) return;
            // terminal states stick for the same goal (callers poll arrived()/failed()), unless pushed away / cooled down
            if (state == State.ARRIVED && AIMath.hDist(ctx.pos(), goal) <= radius + 1.5) return;
            if (state == State.FAILED && ctx.tick - failTick < 100) return;
        }
        boolean moved = goal == null || goal.getWorld() != target.getWorld() || goal.distanceSquared(target) > 4.0;
        this.goal = target.clone();
        this.radius = radius;
        this.opts = options == null ? NavOptions.walk() : options;
        if (state == State.MOVING && !moved) return;
        if (state != State.MOVING) { failures = 0; replans = 0; }
        if (state == State.MOVING && ctx.tick - planTick < 10) { dirty = true; return; } // rate limit replans
        plan();
    }

    /** Update a moving goal (chasing): replans only when it moved enough. */
    public void follow(Location target, double radius, NavOptions options) {
        if (state == State.FAILED && ctx.tick - failTick < 60) return;
        if (goal != null && active() && goal.getWorld() == target.getWorld()) {
            double d = goal.distance(target);
            double dist = ctx.pos().distance(target);
            if (d < Math.max(1.5, dist * 0.2)) return;
            if (ctx.tick - planTick < 10) return;
        }
        goal = null;
        goTo(target, radius, options);
    }

    public void stop() {
        if (ticket != null) ticket.cancel();
        ticket = null;
        path = null;
        goal = null;
        state = State.IDLE;
        cancelActions();
    }

    /** Ticks to stay crouched after a bridge/pillar was cut short (the body hangs over the edge, moving backwards). */
    private int settleTicks;

    /**
     * Call once per tick after the brain ran: right after a bridge or pillar was interrupted (plan change, a fight)
     * hold sneak for a moment like a player who lets go of the keys at a bridge tip, instead of sliding off it.
     */
    public void settle() {
        if (settleTicks <= 0) return;
        settleTicks--;
        // (edges are walked, not crept at: sneaking is for bridging and pillaring only)
    }

    private void cancelActions() {
        if (actionRunning && (action == MoveType.BRIDGE || action == MoveType.PILLAR)) settleTicks = 12;
        if (actionRunning) {
            bridger.cancel(); if (diagBridge.active()) diagBridge.cancel(); pillarer.cancel(); breaker.cancel();
            actionRunning = false;
        }
    }

    /** Interrupt an in-progress bridge/pillar (e.g. to fight); the next tick() will replan. */
    public void interrupt() {
        cancelActions();
        if (state == State.MOVING) { path = null; state = State.PLANNING; plan(); }
    }

    // ---------------------------------------------------------------------------------------------- planning

    private int[] startCell() {
        Location p = ctx.pos();
        WorldView wv = ctx.world();
        int x = floor(p.getX()), y = floor(p.getY() + 0.01), z = floor(p.getZ());
        // on a ladder: plan from the rung we hold, not from the floor the column ends on (that path would first send
        // us back down). Bobbing just above the top rung of a hatch counts as the top rung (its exit link starts there).
        if (wv.shape(x, y, z) == Shape.CLIMB) return new int[]{x, y, z};
        if (!ctx.c.onGround() && wv.shape(x, y - 1, z) == Shape.CLIMB) return new int[]{x, y - 1, z};
        if (!ctx.c.onGround()) {
            int land = wv.landing(x, y, z, 6);
            if (land != Integer.MIN_VALUE) y = land;
        }
        if (wv.standable(x, y, z)) return new int[]{x, y, z};
        // feet sunk into a partial block's cell (the low half of a stair, soul sand, a chest or enchanting table top
        // 0.75-0.875 up): the node we stand on is the cell above it
        if ((NavStats.fix & NavStats.FIX_START) != 0 && !wv.passable(x, y, z) && wv.standable(x, y + 1, z)
                && wv.stand(x, y + 1, z) - p.getY() <= 0.6) return new int[]{x, y + 1, z};
        // centre overhangs an edge: use the block the hitbox is actually on
        double best = 9;
        int[] res = null;
        for (int ox = -1; ox <= 1; ox++) for (int oz = -1; oz <= 1; oz++) {
            if (ox == 0 && oz == 0) continue;
            int cx = x + ox, cz = z + oz;
            for (int yy = y; yy >= y - 1; yy--) {
                if (!wv.standable(cx, yy, cz)) continue;
                double d = AIMath.hDistSq(p.getX(), p.getZ(), cx + 0.5, cz + 0.5);
                if (d < best && d < 0.8 * 0.8 * 2) { best = d; res = new int[]{cx, yy, cz}; }
            }
        }
        return res != null ? res : new int[]{x, y, z};
    }

    private void plan() {
        if (ticket != null) ticket.cancel();
        int[] s = startCell();
        PathRequest r = new PathRequest(ctx.player.getWorld(), s[0], s[1], s[2], goal.getX(), goal.getY(), goal.getZ());
        if (opts != null) r.placed = opts.placed;
        r.goalRadius = Math.max(0.6, radius);
        r.anyY = anyYGoal;
        r.goalVertical = Math.max(1.6, radius * 0.6);
        BotSkill sk = ctx.persona.skill;
        int blocks = Inv.count(ctx.player, Inv.BLOCK);
        if (opts.bridge) {
            r.maxBridgeBlocks = opts.maxBlocks >= 0 ? Math.min(opts.maxBlocks, blocks) : blocks;
            r.bridgeCost = bridgeCost(ctx.persona.bridgeStyle);
            r.pillar = opts.pillar;
        }
        r.breakBlocks = opts.breakBlocks;
        if (r.breakBlocks) {
            r.hasPickaxe = Inv.has(ctx.player, Inv.PICKAXE);
            r.hasAxe = Inv.has(ctx.player, Inv.AXE);
        }
        r.breakable = opts.breakable;
        r.maxParkourGap = opts.parkourGap >= 0 ? opts.parkourGap : (sk.overall > 0.75 ? 3 : sk.overall > 0.4 ? 2 : 1);
        r.voidAversion = 0.25 + (1 - ctx.persona.edgeComfort) * 1.4 + opts.extraVoidAversion + (opts.stroll ? 1.0 : 0);
        r.threats.addAll(opts.threats);
        if (opts.avoidEnemies) {
            Location me = ctx.pos();
            for (net.h2020.bots.ai.perception.Sighting sg : ctx.perception.all()) {
                if (!sg.known || sg.ally || sg.lastPos == null || sg.lastPos.getWorld() != me.getWorld()) continue;
                if (sg.confidence(ctx.tick) < 0.3) continue;
                Location e = sg.estimate(ctx.tick);
                if (e.distanceSquared(me) < 40 * 40) r.threats.add(e.toVector());
            }
            r.threatWeight = 2.0 + 4.0 * (1 - ctx.persona.skill.aggression);
        }
        if (stuckCells.isEmpty()) r.forbidden = opts.forbidden;
        else {
            java.util.Set<Long> f = new java.util.HashSet<>(opts.forbidden);
            f.addAll(stuckCells);
            r.forbidden = f;
        }
        r.maxNodes = opts.maxNodes;
        r.maxFall = opts.stroll ? 3 : (opts.maxFall >= 0 ? opts.maxFall : (sk.overall > 0.6 ? 6 : 4));
        r.maxFallDamage = Math.max(0, Math.min(ctx.health() - 6, opts.stroll ? 0 : (opts.maxFallDamage >= 0 ? opts.maxFallDamage : 5)));
        r.heuristicWeight = 1.25 + (1 - sk.overall) * 0.4;
        // bridging makes every block over the void cost several times a walked one, so the plain distance
        // heuristic sends A* through every corner of the island first (12000 nodes, many seconds of queued search
        // while the bot stands at its cage). Lean greedier toward the goal: islands are crossed in near-straight lines anyway.
        if (opts.bridge && r.maxBridgeBlocks > 0) r.heuristicWeight += 0.35 * r.bridgeCost;
        ticket = PathService.submit(r);
        planTick = ctx.tick;
        dirty = false;
        replans++;
        if (state != State.MOVING) state = State.PLANNING;
    }

    private static double bridgeCost(BotSkill.BridgeStyle s) {
        switch (s) {
            case NOOB: return 7.0;
            case SHIFT: return 4.0;
            case SPEED: case NINJA: return 3.0;
            case BREEZILY: return 2.6;
            default: return 4.0;
        }
    }

    private void pollTicket() {
        if (ticket == null || !ticket.done() || actionRunning) return; // never swap paths mid bridge/pillar
        Path p = ticket.path();
        ticket = null;
        if (p == null || p.size() < 2) {
            if (path == null) {
                failures++;
                if (closeEnough()) { state = State.ARRIVED; return; }
                if (failures >= 3) fail("no path x3");
                else { state = State.PLANNING; planTick = ctx.tick; } // retry after cooldown
            }
            return;
        }
        path = p;
        idx = 1;
        state = State.MOVING;
        // a replan is no progress: keep escalating stuck recovery unless we actually got somewhere (a chased target
        // moving replans every second, which otherwise reset the recovery forever while we stood at a wall)
        Location here = ctx.pos();
        if (stuckRef == null || stuckRef.getWorld() != here.getWorld() || stuckRef.distanceSquared(here) > 1.0) {
            stuckRef = here; stuckRefTick = ctx.tick; stuckLevel = 0;
        }
    }

    private boolean closeEnough() {
        if (goal == null) return true;
        Location p = ctx.pos();
        return AIMath.hDist(p, goal) <= radius && Math.abs(p.getY() - goal.getY()) < 2.0;
    }

    // ---------------------------------------------------------------------------------------------- tick

    public void tick() {
        safeToGlance = false;
        if (!actionRunning && tickNeo()) return;   // a neo owns the whole body, but never during a bridge/pillar action
        if (jumpCooldown > 0) jumpCooldown--;
        if (state == State.IDLE || state == State.ARRIVED || state == State.FAILED) { neo.cancel(); return; }
        NavStats.navTick(ctx.player.getWorld());
        if (NavStats.trace != null && NavStats.trace.equalsIgnoreCase(ctx.player.getName())) trace();
        pollTicket();
        if (state == State.PLANNING) {
            planningTicks++;
            if (ticket == null && ctx.tick - planTick > (failures > 0 ? 20 : 3)) plan();
            beeline();
            return;
        }
        if (state != State.MOVING || path == null) return;

        if (actionRunning) { tickAction(); return; }
        if (dirty && ticket == null && ctx.tick - planTick >= 10) plan();
        if (closeEnough() && (idx >= path.size() - 1 || !path.partial)) {
            state = State.ARRIVED; path = null; return;
        }
        follow();
    }

    /** A neo is a whole-body action: while one runs, nothing else touches the motor. */
    private boolean tickNeo() {
        if (!neo.active()) return false;
        NeoPlanner.Result r = neo.tick();
        if (r == NeoPlanner.Result.RUNNING) return true;
        if (r == NeoPlanner.Result.DONE) { path = null; state = State.PLANNING; return true; }
        return false;                                  // failed: let the normal walk/break behaviour take over
    }

    /**
     * Try a neo before breaking a low wall: the path wants through a 1-2 block obstacle, and going around it is both
     * faster and free of the noise a pickaxe makes. Returns true when a neo was started.
     */
    private boolean tryNeo(PathPoint blocked, PathPoint prev) {
        int dx = Integer.signum(blocked.bx - prev.bx), dz = Integer.signum(blocked.bz - prev.bz);
        for (double reach = 2.0; reach <= 4.0; reach += 0.5) {
            int tx = blocked.bx + (int) Math.round(dx * reach), tz = blocked.bz + (int) Math.round(dz * reach);
            Location landing = new Location(ctx.player.getWorld(), tx + 0.5, blocked.by, tz + 0.5);
            if (!NeoPlanner.candidate(ctx, ctx.pos(), landing)) continue;
            if (!Shape.passable(ctx.world().shape(tx, blocked.by, tz))
                    || !Shape.passable(ctx.world().shape(tx, blocked.by + 1, tz))) continue;
            if (!Shape.floor(ctx.world().shape(tx, blocked.by - 1, tz))) continue;
            neo.start(ctx.pos(), new Location(ctx.player.getWorld(), blocked.bx + 0.5, blocked.by, blocked.bz + 0.5), landing);
            return true;
        }
        return false;
    }

    private void tickAction() {
        switch (action) {
            case BRIDGE: {
                lastBridgeTick = ctx.tick;               // internal: the getter is not called every tick
                if (net.h2020.bots.ai.inv.Inv.count(ctx.player, net.h2020.bots.ai.inv.Inv.BLOCK) == 0) {
                    // no blocks and a bridge ahead: walking to the lip and stepping off is what this used to do
                    CombatStats.inc("nav.bridge.noblocks");
                    actionRunning = false;
                    failAndReplan();
                    break;
                }
                if (diagMode) {
                    net.h2020.bots.ai.motor.DiagonalBridge.Result r = diagBridge.tick();
                    if (r == net.h2020.bots.ai.motor.DiagonalBridge.Result.DONE) {
                        actionRunning = false; diagMode = false; idx = actionEndIdx + 1;
                        net.h2020.bots.ai.motor.BridgeTuning.noteDiagonalSuccess();
                    } else if (r == net.h2020.bots.ai.motor.DiagonalBridge.Result.FAILED) {
                        actionRunning = false; diagMode = false;
                        net.h2020.bots.ai.motor.BridgeTuning.noteDiagonalFailure();
                        failAndReplan();
                    }
                    break;
                }
                Bridger.Result r = bridger.tick();
                if (r == Bridger.Result.DONE) { actionRunning = false; idx = actionEndIdx + 1; }
                else if (r == Bridger.Result.FAILED) { actionRunning = false; failAndReplan(); }
                break;
            }
            case PILLAR: {
                Pillarer.Result r = pillarer.tick();
                if (r == Pillarer.Result.DONE) { actionRunning = false; idx = actionEndIdx + 1; }
                else if (r == Pillarer.Result.FAILED) { actionRunning = false; failAndReplan(); }
                break;
            }
            case BREAK: {
                BlockBreaker.Result r = breaker.tick();
                if (r == BlockBreaker.Result.DONE) {
                    actionRunning = false;
                    if (!startBreakIfNeeded(path.points.get(idx))) { /* both cells clear: walk on */ }
                } else if (r == BlockBreaker.Result.FAILED) { actionRunning = false; failAndReplan(); }
                break;
            }
            default:
                actionRunning = false;
        }
        // failAndReplan() above clears the path
        if (path != null && idx >= path.size() && !actionRunning) {
            if (path.partial && !closeEnough()) plan(); else { state = State.ARRIVED; path = null; }
        }
    }

    private void failAndReplan() {
        failures++;
        // the same bridge/pillar would be planned again from the same spot: forbid its first cell this time
        if (path != null && idx < path.size()) {
            PathPoint a = path.points.get(idx);
            if (a.move == MoveType.BRIDGE || a.move == MoveType.PILLAR) stuckCells.add(AIMath.pack(a.bx, a.by, a.bz));
        }
        path = null;
        if (failures > 5) { fail("failures>5"); return; }
        state = State.PLANNING;
        plan();
    }

    /** Is there a floor at the waypoint's own level, within the body's step? */
    private boolean standingAt(PathPoint p) {
        WorldView wv = ctx.world();
        for (int dy = 0; dy >= -2; dy--) {
            if (wv.standable(p.bx, p.by + dy, p.bz)) return true;
        }
        return false;
    }

    private void follow() {
        List<PathPoint> pts = path.points;
        Location pos = ctx.pos();
        double px = pos.getX(), pz = pos.getZ(), py = pos.getY();

        // advance waypoints
        while (idx < pts.size()) {
            PathPoint p = pts.get(idx);
            PathPoint prev = pts.get(idx - 1);
            if (reached(p, prev, px, py, pz)) idx++; else break;
        }
        if (idx >= pts.size()) {
            if (path.partial && !closeEnough()) { plan(); path = null; state = State.PLANNING; }
            else { state = State.ARRIVED; path = null; }
            return;
        }
        PathPoint p = pts.get(idx);
        PathPoint prev = pts.get(idx - 1);

        // link-level floor check: never walk onto a cell with nothing under it. The pathfinder is supposed to refuse
        // these, but a world that changed under us (a block broken, a bridge knocked out) leaves stale links, and
        // following one is a void death. Bridging and deliberate drops are exempt.
        if (p.move == MoveType.WALK && !standingAt(p)) {
            CombatStats.inc("nav.link.refused");
            idx = pts.size();                       // consume the route: replanning starts from here
            path = null;
            state = State.PLANNING;
            return;
        }

        // pressed against a wall mid-path: if it is a low one, a neo is cheaper than mining it
        if (ctx.c.collidedHorizontally() && idx >= 1 && ctx.tick - lastNeoTry > 25) {
            lastNeoTry = ctx.tick;
            if (tryNeo(p, prev)) return;
        }

        // deviation / fell off the route
        double segDist = distToSegment(px, pz, prev, p);
        if (segDist > 2.6 || py < Math.min(prev.y, p.y) - 2.5) {
            if (ticket == null && ctx.tick - planTick > 8) plan();
            if (py < Math.min(prev.y, p.y) - 2.5) { path = null; state = State.PLANNING; return; }
        }
        // goal moved a lot / periodic refresh of long paths
        if (ticket == null && ctx.tick - planTick > 200 && path.partial) plan();

        if (stuckCheck(px, py, pz)) return;

        switch (p.move) {
            case BRIDGE: case PILLAR:
                // a bridge starts as soon as the bot is on the edge of its cell in the bridge direction: waiting for
                // the exact centre costs the walk to the edge twice, once approaching and once bridging
                if (p.move == MoveType.BRIDGE) {
                    int bdx = p.bx - prev.bx, bdz = p.bz - prev.bz;
                    double along = (px - prev.x) * bdx + (pz - prev.z) * bdz;
                    double perp = Math.abs((px - prev.x) * bdz - (pz - prev.z) * bdx);
                    // start when the body is at the edge of the takeoff cell in the bridge direction: any earlier and
                    // the bridger walks the rest of the way itself, sneaking, at a third of the speed. The cell centre
                    // is the fallback, so a bot that stops on the node can never deadlock waiting for the edge.
                    if (ctx.c.onGround() && ((along > 0.42 && perp < 0.22)
                            || AIMath.hDistSq(px, pz, prev.x, prev.z) < 0.16)) { startAction(p.move, prev); return; }
                } else if (atTakeoff(prev, px, pz, 0.32)) { startAction(p.move, prev); return; }
                // pre-turn into the bridge line while still walking the last metres: the turn is the expensive part of
                // starting a bridge, and it can happen during the approach instead of after it
                if (p.move == MoveType.BRIDGE && AIMath.hDistSq(px, pz, prev.x, prev.z) < 9.0) {
                    int dx = p.bx - prev.bx, dz = p.bz - prev.bz;
                    ctx.motor.aimRotation(Motor.P_TASK, AIMath.yawOf(dx, dz), 55f,
                            net.h2020.api.bot.AimIntent.PRECISE, 0.7);
                    // and walk the last metre to the *edge* of the takeoff cell, not its centre: the bridger then
                    // starts with its eye already over the void and places on the first or second tick
                    if (AIMath.hDistSq(px, pz, prev.x, prev.z) < 1.6) {
                        double ex = prev.x + dx * 0.46, ez = prev.z + dz * 0.46;
                        double ddx = ex - px, ddz = ez - pz;
                        if (ddx * ddx + ddz * ddz < 0.36) {
                            ctx.motor.moveToward(ex, ez);
                            return;
                        }
                    }
                }
                walkPrecise(prev, px, pz);
                return;
            case BREAK:
                if (AIMath.hDistSq(px, pz, prev.x, prev.z) < 0.5) {
                    if (tryNeo(p, prev)) return;               // a wall this short is a neo, not a mining job
                    if (startBreakIfNeeded(p)) return;
                }
                steerTo(p, prev, pts, px, py, pz);
                return;
            case PARKOUR:
                parkour(p, prev, px, py, pz);
                return;
            case LADDER:
                ladder(p, prev, px, py, pz);
                return;
            case SWIM:
                ctx.motor.jump(true);
                steerTo(p, prev, pts, px, py, pz);
                return;
            case JUMP_UP:
                steerTo(p, prev, pts, px, py, pz);
                // Time the jump on the gap between our 0.6 box and the step's cell (per-axis, so diagonal approaches
                // onto a corner work too). Vanilla rises 0.42/0.75/1.0013 in the first 3 ticks and resolves Y before
                // X, so the box clears a full block cleanly if it has not reached the face after 2 ticks:
                // sprint-jump covers ~0.95 in 2 ticks, a walking jump ~0.44. Closer than that still works (the body
                // hits the face, slides up and over at the 1.25 apex), it is just slower. (In water the brain holds
                // jump to swim; the step is climbed by the client's jump-out-of-water boost.)
                Vector hv = ctx.c.velocity();
                double hs = Math.sqrt(hv.getX() * hv.getX() + hv.getZ() * hv.getZ());
                double gap = Math.max(Math.abs(px - p.x), Math.abs(pz - p.z)) - 0.8;
                boolean headroom = ctx.world().passable(floor(px), floor(py + 0.01) + 2, floor(pz));
                double window = !headroom ? 0.0 : hs > 0.24 ? 1.25 : 0.65;
                if (ctx.c.onGround() && py < p.y - 0.4 && jumpCooldown == 0
                        && (gap < window || ctx.c.collidedHorizontally())) {
                    ctx.motor.jump(true);
                    jumpCooldown = 6;
                }
                return;
            default:
                steerTo(p, prev, pts, px, py, pz);
        }
    }

    private boolean reached(PathPoint p, PathPoint prev, double px, double py, double pz) {
        double d2 = AIMath.hDistSq(px, pz, p.x, p.z);
        MoveType mv = p.move;
        if (mv == MoveType.BREAK) {
            WorldView wv = ctx.world();
            if (!wv.passable(p.bx, p.by, p.bz) || !wv.passable(p.bx, p.by + 1, p.bz)) return false;
            mv = MoveType.WALK; // dug through: an ordinary walk node now
        }
        switch (mv) {
            case BRIDGE: case PILLAR:
                return false; // only completed by their action
            case PARKOUR: case JUMP_UP:
                // up on the landing level and standing on/at the landing cell. The look-ahead steers past the node
                // (corners after a step-up), and parkour lands off-centre, so the 0.45 centre radius was often never
                // hit: the bot then circled the look-ahead point on top until stuck recovery, or (parkour) re-jumped
                // from the landing because along > 0.22 still held.
                if (ctx.c.onGround() && Math.abs(py - p.y) < 0.7 && py > p.y - 0.3 && d2 < 0.8 * 0.8) return true;
                // came up onto the next cell (a neighbouring stair, the far side of a corner) at the landing level:
                // that is the step taken; insisting on the node centre left the bot hopping beside it
                return (NavStats.fix & NavStats.FIX_LOOKAHEAD) != 0 && p.move == MoveType.JUMP_UP && ctx.c.onGround()
                        && Math.abs(py - p.y) < 0.3 && d2 < 1.5 * 1.5 && ctx.world().standable(floor(px), floor(py + 0.01), floor(pz));
            case LADDER:
                return d2 < 0.45 * 0.45 && Math.abs(py - p.y) < 0.7;
            case SWIM:
                // holding jump floats us at the surface above deep nodes: any height within the water column is fine
                if (py - p.y > 4.0 || py - p.y < -1.3) return false;
                return d2 < 0.55 * 0.55 || passedAlong(p, prev, px, pz, d2);
            case DROP:
                return d2 < 0.6 * 0.6 && py < p.y + 0.8;
            default: {
                if (Math.abs(py - p.y) > 1.3) return false;
                if (d2 < 0.55 * 0.55) return true;
                return passedAlong(p, prev, px, pz, d2);
            }
        }
    }

    /** Passed the waypoint along the segment prev -> p. */
    private static boolean passedAlong(PathPoint p, PathPoint prev, double px, double pz, double d2) {
        double sx = p.x - prev.x, sz = p.z - prev.z;
        double len2 = sx * sx + sz * sz;
        if (len2 < 1e-4) return true;
        double t = ((px - prev.x) * sx + (pz - prev.z) * sz) / len2;
        return t >= 1.0 && d2 < 1.5 * 1.5;
    }

    private boolean atTakeoff(PathPoint prev, double px, double pz, double tol) {
        return AIMath.hDistSq(px, pz, prev.x, prev.z) < tol * tol && ctx.c.onGround();
    }

    private void startAction(MoveType type, PathPoint takeoff) {
        List<PathPoint> pts = path.points;
        int end = idx;
        if (type == MoveType.BRIDGE) {
            PathPoint first = pts.get(idx);
            int dx = first.bx - takeoff.bx, dz = first.bz - takeoff.bz;
            while (end + 1 < pts.size()) {
                PathPoint n = pts.get(end + 1);
                PathPoint c = pts.get(end);
                if (n.move != MoveType.BRIDGE || n.bx - c.bx != dx || n.bz - c.bz != dz || n.by != c.by) break;
                end++;
            }
            net.h2020.api.stats.CombatStats.inc("nav.bridge.start");
            // the run may end at a higher node (a staircase up to an island or a tower): give the bridge that height
            int wantY = (int) Math.floor(pts.get(end).y);
            for (int k = end; k < Math.min(pts.size(), end + 6); k++) wantY = Math.max(wantY, (int) Math.floor(pts.get(k).y));
            // a true diagonal only takes the dedicated stance when the operator has switched it on
            if (net.h2020.bots.ai.motor.BridgeTuning.diagonalAllowed() && dx != 0 && dz != 0) {
                diagMode = true;
                neo.cancel();                          // one body owner at a time: no neo hijacking a bridge
                diagBridge.start(dx, dz, takeoff.bx, takeoff.by, takeoff.bz, end - idx + 1);
            } else {
                diagMode = false;
                bridger.start(dx, dz, takeoff.bx, takeoff.by, takeoff.bz, end - idx + 1, wantY);
            }
        } else {
            while (end + 1 < pts.size() && pts.get(end + 1).move == MoveType.PILLAR) end++;
            pillarer.start(takeoff.bx, takeoff.by, takeoff.bz, end - idx + 1);
        }
        action = type;
        actionEndIdx = end;
        actionRunning = true;
    }

    /** Start breaking the next solid cell of a BREAK waypoint. False if the way is already clear. */
    private boolean startBreakIfNeeded(PathPoint p) {
        WorldView wv = ctx.world();
        for (int yy = p.by + 1; yy >= p.by; yy--) {
            if (!Shape.passable(wv.shape(p.bx, yy, p.bz))) {
                // a map block is not something a player can dig: the route is stale, replan around it
                if (opts != null && !opts.breakable(ctx.player.getWorld(), p.bx, yy, p.bz)) {
                    // a map block: the route is stale, replan around it instead of digging at bedrock for a minute
                    if (NavStats.debug) org.bukkit.Bukkit.getLogger().info("[nav] break-map at " + p.bx + "," + yy + "," + p.bz);
                    plan();
                    return false;
                }
                breaker.start(p.bx, yy, p.bz, false);
                action = MoveType.BREAK;
                actionRunning = true;
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------- steering

    /**
     * Advanced edge heuristic: a sprint carries about a block of overshoot, so approaching a cell with the void on two
     * or more sides at full sprint is how bots walk off their own island. Brake (drop sprint, shorten the lookahead)
     * for the last couple of blocks instead of hoping the walk stops in time.
     */
    private boolean edgeBrake(PathPoint p, double px, double pz) {
        // only a genuinely narrow ledge, and only as we arrive at it: braking for every rim cell killed sprinting
        int vs = ctx.world().voidSides(p.bx, p.by, p.bz);
        if (vs < 3) return false;
        return Math.hypot(px - p.x, pz - p.z) < 1.8;
    }

    private void steerTo(PathPoint p, PathPoint prev, List<PathPoint> pts, double px, double py, double pz) {
        int danger = Math.max(p.danger, prev.danger);
        boolean brake = edgeBrake(p, px, pz);
        // a bridge is one block wide and a drop has no floor: sprinting into either is how bots fall off their own
        // bridge. The path already says what the next move is, so trust it instead of the geometry.
        if (brake) { ctx.motor.sprint(false); CombatStats.inc("nav.edgeBrake"); }
        double look = brake ? 0.7 : (danger > 0 ? 0.9 : (opts.stroll ? 1.6 : 2.2));
        double[] t = lookahead(pts, px, pz, look, ctx.c.onGround() ? py : prev.y);
        double tx = t[0], tz = t[1];
        steerX = tx; steerZ = tz;
        double dx = tx - px, dz = tz - pz;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > 1e-3 && opts.weave && danger == 0) {
            double w = ctx.persona.weave * AIMath.weave(ctx.tick * 0.05, weavePhase) * Math.min(1.0, len / 2.0);
            tx += -dz / len * w; tz += dx / len * w;
            dx = tx - px; dz = tz - pz;
        }
        // edge guard: never walk into a void we did not plan for. If the look-ahead cuts a corner over the void,
        // aim straight at the waypoint instead; if even that is unsafe, stop and crouch.
        if (!edgeSafe(px, py, pz, dx, dz, p)) {
            dx = p.x - px; dz = p.z - pz;
            danger = Math.max(danger, 1);
            if (!edgeSafe(px, py, pz, dx, dz, p)) {
                ctx.motor.move(0, 0);
                if (ticket == null && ctx.tick - planTick > 20) plan();
                return;
            }
        }
        float wantYaw = AIMath.yawOf(dx, dz);
        float pitch = navPitch + (float) (AIMath.weave(ctx.tick * 0.03, weavePhase + 3) * 4);
        double dyNext = p.y - py;
        if (dyNext < -1.5) pitch += 12;
        ctx.motor.aimRotation(Motor.P_NAV, wantYaw, pitch, AimIntent.NAVIGATE, danger > 0 ? 2.0 : 5.0);

        float yawErr = Math.abs(AIMath.angleDiff(ctx.c.yaw(), wantYaw));
        ctx.motor.moveToward(dx, dz);
        double straight = straightAhead(pts, px, pz);
        boolean sprint = opts.sprint && !opts.stroll && yawErr < 35 && ctx.player.getFoodLevel() > 6
                && (danger <= 1 || ctx.persona.edgeComfort > 0.7) && straight > 1.8;
        if (sprint) CombatStats.inc("nav.sprint");
        ctx.motor.sprint(sprint);
        // (no creep-sneaking along edges)
        safeToGlance = danger == 0 && straight > 4 && yawErr < 25;

        // sprint-jump on flat straights: the fastest way to cross an island, and the way players actually walk.
        // Never in a fight (airborne is more knockback) and never on a line with any drop beside it.
        boolean jogger = ctx.persona.sprintJumper || ctx.persona.skill.overall > 0.5;
        // sprint-jumping is how players cross a map: allowed anywhere that is not the lip of a drop, and never in a
        // fight (airborne takes more knockback)
        if (sprint && jogger && !ctx.combat.engaged() && straight > 2.2 && danger <= 1 && ctx.c.onGround()
                && yawErr < 25 && jumpCooldown == 0 && ctx.player.getFoodLevel() > 6
                && ctx.world().passable(floor(px), floor(py) + 2, floor(pz)) && Math.abs(p.y - py) < 0.5) {
            ctx.motor.jump(true);
            CombatStats.inc("nav.sprintjump");
            jumpCooldown = ctx.tick % 2 == 0 ? 8 : 10;
        }
        // bumping into a 1-block step that the plan did not mark (e.g. stairs): hop
        if (ctx.c.collidedHorizontally() && ctx.c.onGround() && jumpCooldown == 0) {
            ctx.motor.jump(true);
            jumpCooldown = 8;
        }
    }

    private void walkPrecise(PathPoint target, double px, double pz) {
        double dx = target.x - px, dz = target.z - pz;
        float wantYaw = AIMath.yawOf(dx, dz);
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d > 0.6) ctx.motor.aimRotation(Motor.P_NAV, wantYaw, navPitch + 15, AimIntent.NAVIGATE, 4.0);
        ctx.motor.moveToward(dx, dz);
        boolean edge = target.danger > 0 || ctx.world().voidSides(target.bx, target.by, target.bz) > 0;
        // (no creep-sneaking at the lip)
        if (d < 0.2) ctx.motor.move(0, 0);
    }

    private void parkour(PathPoint land, PathPoint takeoff, double px, double py, double pz) {
        double dx = land.x - takeoff.x, dz = land.z - takeoff.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        double ux = dx / len, uz = dz / len;
        double along = (px - takeoff.x) * ux + (pz - takeoff.z) * uz;
        double lat = (px - takeoff.x) * -uz + (pz - takeoff.z) * ux;
        boolean airborne = !ctx.c.onGround();
        float yaw = AIMath.yawOf(dx, dz);
        ctx.motor.aimRotation(Motor.P_NAV, yaw, navPitch + 10, AimIntent.NAVIGATE, 2.0);
        if (airborne && along > 0.3) {
            ctx.motor.move(1, 0);
            ctx.motor.sprint(true);
            return;
        }
        if (along < -1.6 || Math.abs(lat) > 0.6) {
            // get onto the take-off line first
            double tx = takeoff.x - ux * 0.4 - px, tz = takeoff.z - uz * 0.4 - pz;
            ctx.motor.moveToward(tx, tz);
            return;
        }
        ctx.motor.moveToward(dx - lat * -uz * 2, dz - lat * ux * 2);
        ctx.motor.sprint(true);
        if (along > 0.22 && ctx.c.onGround() && jumpCooldown == 0) {
            ctx.motor.jump(true);
            jumpCooldown = 6;
        }
    }

    private void ladder(PathPoint p, PathPoint prev, double px, double py, double pz) {
        double dx = p.x - px, dz = p.z - pz;
        boolean up = p.y > py - 0.1;
        WorldView wv = ctx.world();
        // face the ladder's backing block when climbing
        float yaw = AIMath.yawOf(dx, dz);
        int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        boolean exiting = up && dx * dx + dz * dz > 0.3; // climbing out of the ladder top onto a neighbouring floor
        if (!exiting) {
            for (int[] a : around) {
                if (wv.supportive(p.bx + a[0], p.by, p.bz + a[1])) { yaw = AIMath.yawOf(a[0], a[1]); break; }
            }
        }
        if (up && exiting && !ctx.c.onGround() && prev != null && wv.shape(prev.bx, prev.by, prev.bz) == Shape.CLIMB) {
            // stepping off sideways (not over the backing): first press into the backing until the body lands on the
            // ladder's top edge, then walk off from there
            for (int[] a : around) {
                if (!wv.supportive(prev.bx + a[0], prev.by, prev.bz + a[1])) continue;
                if (p.bx - prev.bx == a[0] && p.bz - prev.bz == a[1]) break; // exit is over the backing: plain W works
                ctx.motor.aimRotation(Motor.P_NAV, AIMath.yawOf(a[0], a[1]), -25, AimIntent.NAVIGATE, 6.0);
                ctx.motor.move(1, 0);
                return;
            }
        }
        ctx.motor.aimRotation(Motor.P_NAV, yaw, up ? -25 : 40, AimIntent.NAVIGATE, 6.0);
        if (up && exiting) { ctx.motor.move(1, 0); ctx.motor.moveToward(dx, dz); }
        else if (up) ctx.motor.move(1, ladderStrafe(dx, dz));
        else if (dx * dx + dz * dz > 0.1) ctx.motor.moveToward(dx, dz);
    }

    private float ladderStrafe;

    /**
     * Sideways correction while climbing: W alone keeps whatever sideways offset we grabbed the ladder with, and a
     * 0.6-wide body only fits a 1-wide hatch within +-0.2 of the column centre; off-centre the head bumps the ceiling
     * and the climb stalls under the opening. A/D toward the centre, braking early: in the air the strafe input is
     * tiny (0.02/tick) but the 0.91 drag lets the body coast ~10x its sideways speed.
     */
    private float ladderStrafe(double dx, double dz) {
        double rad = Math.toRadians(ctx.c.yaw());
        double lx = Math.cos(rad), lz = Math.sin(rad); // world direction of strafe +1 (A)
        Vector v = ctx.c.velocity();
        double err = dx * lx + dz * lz;
        double pred = err - (v.getX() * lx + v.getZ() * lz) * 10.0;
        if (ladderStrafe == 0) { if (Math.abs(pred) > 0.12) ladderStrafe = pred > 0 ? 1 : -1; }
        else if (pred * ladderStrafe < 0.04) ladderStrafe = 0;
        return ladderStrafe;
    }

    private void beeline() {
        if (goal == null) return;
        Location p = ctx.pos();
        double dx = goal.getX() - p.getX(), dz = goal.getZ() - p.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < radius || d > 40) return;
        if (!edgeSafe(p.getX(), p.getY(), p.getZ(), dx, dz, null)) return;
        WorldView wv = ctx.world();
        int ax = floor(p.getX() + dx / d * 0.8), az = floor(p.getZ() + dz / d * 0.8), ay = floor(p.getY() + 0.01);
        if (!wv.passable(ax, ay, az) || !wv.passable(ax, ay + 1, az)) return;
        ctx.motor.aimRotation(Motor.P_NAV, AIMath.yawOf(dx, dz), navPitch, AimIntent.NAVIGATE, 6.0);
        ctx.motor.moveToward(dx, dz);
    }

    /** Is walking in direction (dx,dz) for the next ~0.7 blocks safe from unplanned void drops? */
    private boolean edgeSafe(double px, double py, double pz, double dx, double dz, PathPoint target) {
        if (target != null && (target.move == MoveType.DROP || target.move == MoveType.PARKOUR || target.move == MoveType.SWIM)) return true;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-3) return true;
        Vector v = ctx.c.velocity();
        double ahead = 0.55 + Math.min(0.6, Math.sqrt(v.getX() * v.getX() + v.getZ() * v.getZ()) * 3);
        double fx = px + dx / len * ahead, fz = pz + dz / len * ahead;
        WorldView wv = ctx.world();
        int cx = floor(fx), cz = floor(fz), cy = floor(py + 0.01);
        if (wv.standable(cx, cy, cz) || wv.standable(cx, cy + 1, cz) || wv.standable(cx, cy - 1, cz)) return true;
        if (!wv.passable(cx, cy, cz)) return true; // wall, not a hole
        int land = wv.landing(cx, cy - 1, cz, 5);
        return land != Integer.MIN_VALUE;
    }

    private double[] lookahead(List<PathPoint> pts, double px, double pz, double dist, double level) {
        PathPoint prev = pts.get(idx - 1), cur = pts.get(idx);
        boolean levels = (NavStats.fix & NavStats.FIX_LOOKAHEAD) != 0;
        // projection of the bot onto the current segment
        double sx = cur.x - prev.x, sz = cur.z - prev.z;
        double len2 = sx * sx + sz * sz;
        double t = len2 < 1e-6 ? 1 : AIMath.clamp(((px - prev.x) * sx + (pz - prev.z) * sz) / len2, 0, 1);
        double ox = prev.x + sx * t, oz = prev.z + sz * t;
        double remain = dist;
        int i = idx;
        double cx = ox, cz = oz;
        while (true) {
            PathPoint n = pts.get(i);
            double ddx = n.x - cx, ddz = n.z - cz;
            double seg = Math.sqrt(ddx * ddx + ddz * ddz);
            if (seg >= remain) return new double[]{cx + ddx / seg * remain, cz + ddz / seg * remain};
            remain -= seg;
            cx = n.x; cz = n.z;
            if (i + 1 >= pts.size()) return new double[]{cx, cz};
            // The look-ahead is flat: never carry it past a change of level (a drop into a hole, a jump or a 1-block
            // stair step). Routes often double back on the other level (drop into the island's stairwell and walk
            // back underneath, or a turning staircase): the point beyond then lies under/over the bot, which circles
            // it on the edge instead of taking the step.
            if (levels && Math.abs(n.y - level) > 0.6) return new double[]{cx, cz};
            PathPoint nn = pts.get(i + 1);
            if (nn.special() && nn.move != MoveType.DROP) return new double[]{cx, cz}; // stop at action take-off
            i++;
        }
    }

    private double straightAhead(List<PathPoint> pts, double px, double pz) {
        PathPoint cur = pts.get(idx);
        double d = Math.sqrt(AIMath.hDistSq(px, pz, cur.x, cur.z));
        if (idx + 1 < pts.size()) {
            PathPoint n = pts.get(idx + 1);
            if (n.move == MoveType.WALK) {
                double ax = cur.x - px, az = cur.z - pz, bx = n.x - cur.x, bz = n.z - cur.z;
                double la = Math.sqrt(ax * ax + az * az), lb = Math.sqrt(bx * bx + bz * bz);
                if (la > 1e-3 && lb > 1e-3 && (ax * bx + az * bz) / (la * lb) > 0.95) d += lb;
            }
        }
        return d;
    }

    private static double distToSegment(double px, double pz, PathPoint a, PathPoint b) {
        double sx = b.x - a.x, sz = b.z - a.z;
        double len2 = sx * sx + sz * sz;
        double t = len2 < 1e-6 ? 0 : AIMath.clamp(((px - a.x) * sx + (pz - a.z) * sz) / len2, 0, 1);
        double cx = a.x + sx * t, cz = a.z + sz * t;
        return Math.sqrt(AIMath.hDistSq(px, pz, cx, cz));
    }

    /** Last steering (look-ahead) point, for the trace/stuck log. */
    private double steerX, steerZ;

    private void trace() {
        Location p = ctx.pos();
        Vector v = ctx.c.velocity();
        String wp = "-";
        if (path != null && idx < path.size()) {
            PathPoint a = path.points.get(idx);
            wp = String.format("%s#%d/%d %.1f,%.2f,%.1f", a.move, idx, path.size(), a.x, a.y, a.z);
        }
        org.bukkit.Bukkit.getLogger().info(String.format("[navtrace] %s t=%d %s pos=%.2f,%.3f,%.2f v=%.2f,%.2f,%.2f g=%s colH=%s gui=%s wp=%s steer=%.2f,%.2f stuck=%d",
                ctx.player.getName(), ctx.tick, state, p.getX(), p.getY(), p.getZ(), v.getX(), v.getY(), v.getZ(), ctx.c.onGround(),
                ctx.c.collidedHorizontally(), ctx.c.containerOpen(), wp, steerX, steerZ, stuckLevel));
    }

    /** Staged stuck recovery. Returns true when it took over the keys this tick. */
    private boolean stuckCheck(double px, double py, double pz) {
        if (wiggleTicks > 0) {
            wiggleTicks--;
            ctx.motor.move(0.0f, wiggleStrafe);
            Location wp = ctx.pos();
            // beside the void the wiggle is a plain shuffle (a strafe-jump there is a fall); no creep-sneaking
            boolean besideVoid = ctx.world().voidSides(floor(wp.getX()), floor(wp.getY() + 0.01), floor(wp.getZ())) > 0;
            if (!besideVoid && wiggleTicks % 4 == 0) ctx.motor.jump(true);
            return true;
        }
        if (stuckRef == null || stuckRef.getWorld() != ctx.player.getWorld()) { stuckRef = ctx.pos(); stuckRefTick = ctx.tick; return false; }
        // a GUI swallows the movement keys: standing still behind an open chest/shop is not being stuck
        if ((NavStats.fix & NavStats.FIX_GUI) != 0 && ctx.c.containerOpen()) { stuckRef = ctx.pos(); stuckRefTick = ctx.tick; return false; }
        if (ctx.tick - stuckRefTick < 25) return false;
        // hopping in place against a step we cannot take moves the body ~1.2 up and down: only count height we
        // actually hold (on the ground, on a ladder, in water), else such a loop never registers as stuck
        double hMoved = Math.sqrt(AIMath.hDistSq(px, pz, stuckRef.getX(), stuckRef.getZ()));
        boolean holdsHeight = ctx.c.onGround() || ctx.inLiquid()
                || ctx.world().shape(floor(px), floor(py + 0.01), floor(pz)) == Shape.CLIMB;
        double moved = hMoved + (holdsHeight && stuckRefHeld ? Math.abs(py - stuckRef.getY()) : 0);
        stuckRefHeld = holdsHeight;
        stuckRef = ctx.pos();
        stuckRefTick = ctx.tick;
        if (moved > 0.6) { stuckLevel = 0; return false; }
        stuckLevel++;
        if (stuckLevel == 2) stuckEpisodes++;
        if (stuckLevel == 2 && path != null && idx < path.size()) {
            PathPoint sp = path.points.get(idx);
            PathPoint pp = path.points.get(idx - 1);
            Vector v = ctx.c.velocity();
            NavStats.stuck(ctx.player.getName(), ctx.pos(), sp.x - px, sp.z - pz, sp.move + "#" + idx + "/" + path.size()
                    + String.format(" [%.1f,%.2f,%.1f -> %.1f,%.2f,%.1f] ground=%s colH=%s gui=%s steer=%.1f,%.1f v=%.2f,%.2f,%.2f",
                    pp.x, pp.y, pp.z, sp.x, sp.y, sp.z, ctx.c.onGround(), ctx.c.collidedHorizontally(), ctx.c.containerOpen(),
                    steerX, steerZ, v.getX(), v.getY(), v.getZ()), ctx.world());
        }
        if (stuckLevel == 1) {
            // (never hop at a bridge takeoff / beside the void: a jump there cancels the crouch's edge guard and is
            // how bots stepped off the tip of their own bridge when the takeoff spot was a hair out of reach)
            boolean edge = ctx.world().voidSides(floor(px), floor(py + 0.01), floor(pz)) > 0
                    || (path != null && idx < path.size() && (path.points.get(idx).move == MoveType.BRIDGE || path.points.get(idx).move == MoveType.PILLAR));
            if (!edge) ctx.motor.jump(true);
            return false;
        }
        if (stuckLevel == 2) { wiggleTicks = 8; wiggleStrafe = ctx.rng.nextBoolean() ? 1 : -1; return true; }
        if (stuckLevel >= 3 && path != null && idx < path.size()) {
            // the same plan keeps walking into this spot: forbid it so the replan finds another way (or breaks through)
            PathPoint sp = path.points.get(idx);
            stuckCells.add(AIMath.pack(sp.bx, sp.by, sp.bz));
            // smoothed waypoints can be many blocks ahead: also forbid the cell we are actually pushing into
            double dx = sp.x - px, dz = sp.z - pz, len = Math.sqrt(dx * dx + dz * dz);
            if (len > 1e-3) {
                int ax = floor(px + dx / len * 0.7), az = floor(pz + dz / len * 0.7), ay = floor(py + 0.01);
                if (ax != floor(px) || az != floor(pz)) {
                    stuckCells.add(AIMath.pack(ax, ay, az));
                    stuckCells.add(AIMath.pack(ax, ay + 1, az));
                }
            }
        }
        if (stuckLevel <= 5) { plan(); return false; }
        fail("stuck");
        return true;
    }

    /** -Dh2020.navdebug=true logs every navigation failure (start, goal, reason) to the console. */
    private void fail(String why) {
        failuresTotal++;
        if ("stuck".equals(why)) NavStats.stuckFail(ctx.player.getWorld());
        else if (why.startsWith("no path")) NavStats.noPath(ctx.player.getWorld());
        if (NavStats.debug) {
            Location p = ctx.pos();
            org.bukkit.Bukkit.getLogger().info(String.format("[navdebug] %s fail(%s) at %.1f,%.1f,%.1f goal %s r=%.1f bridge=%s blocks=%d",
                    ctx.player.getName(), why, p.getX(), p.getY(), p.getZ(),
                    goal == null ? "-" : String.format("%.1f,%.1f,%.1f", goal.getX(), goal.getY(), goal.getZ()),
                    radius, opts != null && opts.bridge, Inv.count(ctx.player, Inv.BLOCK)));
        }
        state = State.FAILED;
        path = null;
        failTick = ctx.tick;
        cancelActions();
    }

    private static int floor(double d) { int i = (int) d; return d < i ? i - 1 : i; }

    public double distanceToGoal() {
        return goal == null ? 0 : ctx.pos().distance(goal);
    }
}
