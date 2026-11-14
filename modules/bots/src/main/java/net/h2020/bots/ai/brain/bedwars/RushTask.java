package net.h2020.bots.ai.brain.bedwars;

import net.h2020.api.game.TeamColor;
import net.h2020.bots.ai.brain.BedwarsBrain;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.motor.BlockBreaker;
import net.h2020.bots.ai.motor.BlockPlacer;
import net.h2020.bots.ai.motor.Bridger;
import net.h2020.bots.ai.world.Shape;
import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.world.WorldView;
import net.h2020.bots.ai.nav.NavOptions;
import net.h2020.bots.ai.util.AIMath;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.Set;

/**
 * Rush an enemy base: bridge to their island, walk/dig to the bed and break it, tunnelling through the defense
 * with whatever the crosshair hits (shears for wool, pickaxe for end stone/clay, axe for wood). Afterwards hang
 * around briefly for the final kills (combat interrupts take care of the fighting).
 */
public final class RushTask extends BwTask {
    private enum Phase { TRAVEL, FOLLOW, EXTEND, APPROACH, TNT, FLEE, BREAK, AFTER }

    static final Set<Material> DEFENSE = EnumSet.of(Material.WOOL, Material.STAINED_CLAY, Material.HARD_CLAY,
            Material.ENDER_STONE, Material.WOOD, Material.STAINED_GLASS, Material.GLASS, Material.LADDER, Material.OBSIDIAN);

    public final TeamColor target;
    private Phase phase = Phase.TRAVEL;
    private long phaseStart;
    private final BlockBreaker breaker;
    private boolean calledOut;
    private final int afterLen;
    private final BlockPlacer placer;
    private boolean tntUsed;
    private Location fleeFrom;
    /** Straight-line extension across the last gap, with the blocks we have left. */
    private Bridger bridger;
    private long lastEgg = -1000;
    private long lastPearl = -1000;
    private long lastFollow = -1000;
    private Player followLeader;
    /** Cells beside the tunnel we sealed while a defender was coming (the "block in" at a bed). */
    private int covered;
    private long lastCover = -100;

    public RushTask(BedwarsBrain brain, TeamColor target) {
        super(brain);
        this.target = target;
        this.breaker = new BlockBreaker(ctx);
        this.placer = new BlockPlacer(ctx);
        this.phaseStart = ctx.tick;
        this.afterLen = AIMath.range(ctx.rng, 60, 200);
    }

    @Override public String name() { return "rush-" + target.name().toLowerCase(); }

    @Override public org.bukkit.Location goal() { return view.bedLocation(target); }

    /** Did the rush end with that team's bed gone? */
    public boolean brokeBed() { return !view.bedAlive(target); }

    private void phase(Phase p) { phase = p; phaseStart = ctx.tick; if (p == Phase.BREAK) covered = 0; }

    /**
     * The bed is still there and we are close enough that turning around for a fair fight would throw the trip away.
     * The brain uses this to let a rusher walk past players that are not stopping it.
     */
    public boolean committed() {
        Location bed = view.bedLocation(target);
        if (bed == null || !view.bedAlive(target)) return false;
        return ctx.eye().distance(bed.clone().add(0.5, 0.3, 0.5)) < 22;
    }

    /** Any known enemy within `r` blocks (the defender coming back for us). */
    private boolean enemyNear(double r) {
        Location me = ctx.pos();
        for (net.h2020.bots.ai.perception.Sighting s : ctx.perception.all()) {
            if (!s.known || s.ally || s.lastPos == null) continue;
            Location e = s.estimate(ctx.tick);
            if (e != null && e.getWorld() == me.getWorld() && e.distance(me) < r) return true;
        }
        return false;
    }

    /** Last tick this task either moved a meaningful distance or put a block down. */
    private long lastProgress = -1000;
    /** Tick the navigator first came back with no path, or -1. */
    private long noPathSince = -1;
    private double lastPX = Double.NaN, lastPZ = Double.NaN;

    @Override
    public boolean tick() {
        Location bed = view.bedLocation(target);
        if (bed == null) return false;
        Location bedC = bed.clone().add(0.5, 0.3, 0.5);
        long inPhase = ctx.tick - phaseStart;
        boolean alive = view.bedAlive(target);
        if (!alive && phase != Phase.AFTER) {
            phase(Phase.AFTER);
            // the number this whole path exists to move: a bed that is gone because this bot went and took it
            net.h2020.api.stats.CombatStats.inc("bed.broken");
            net.h2020.api.stats.CombatStats.inc("bed.broken." + target.name().toLowerCase(java.util.Locale.ROOT));
            ctx.chat.callout(ctx.persona.toxic ? "ez bed" : "bed broken", 0.25);
        }
        double eyeDist = ctx.eye().distance(bedC);
        // stall guard: travelling or bridging with no movement and no placement for three seconds is a stuck task,
        // and a stuck task is a statue - the one thing a player never is
        double nx = ctx.pos().getX(), nz = ctx.pos().getZ();
        if (Double.isNaN(lastPX)) { lastPX = nx; lastPZ = nz; lastProgress = ctx.tick; }
        else if (Math.hypot(nx - lastPX, nz - lastPZ) > 0.2) { lastPX = nx; lastPZ = nz; lastProgress = ctx.tick; }
        if ((phase == Phase.TRAVEL || phase == Phase.EXTEND || phase == Phase.FOLLOW)
                && ctx.tick - lastProgress > 60 && !ctx.combat.engaged() && !ctx.c.containerOpen()) {
            net.h2020.api.stats.CombatStats.inc("rush.stall");
            // Why it stalled, not just that it did: the phase, the blocks left, whether a route existed, how far the
            // bed still is, and whether the bot was airborne. A statue has a cause and this names it.
            net.h2020.api.stats.CombatStats.inc("rush.stall." + phase.name().toLowerCase(java.util.Locale.ROOT)
                    + (brain.blocks() < 1 ? ".noblocks" : "")
                    + (ctx.nav.path() == null ? ".nopath" : ".path")
                    + (ctx.c.onGround() ? ".ground" : ".air"));
            if (net.h2020.api.stats.CombatStats.debug) {
                org.bukkit.Bukkit.getLogger().info("[rushstall] " + ctx.player.getName() + " phase=" + phase
                        + " blocks=" + brain.blocks() + " path=" + (ctx.nav.path() == null ? "none" : "yes")
                        + " bedDist=" + Math.round(eyeDist) + " ground=" + ctx.c.onGround()
                        + " hp=" + Math.round(ctx.health()));
            }
            return false;                     // the brain re-decides immediately
        }
        switch (phase) {
            case TRAVEL: {
                if (!calledOut && brain.teamMode()) { calledOut = true; ctx.chat.callout("rushing " + target.displayName.toLowerCase(), 0.4); }
                if (eyeDist < 6) { phase(Phase.APPROACH); return true; }
                // a teammate is already bridging toward the same bed: walk behind their bridge instead of building
                // a second one (TeamPlay.bridgeLeader only names somebody who is actually ahead and laying blocks)
                TeamPlay.Assignment as = TeamPlay.bridging(brain, target);
                if (as.role == TeamPlay.Role.FOLLOW && as.leader != null) {
                    followLeader = as.leader;
                    phase(Phase.FOLLOW);
                    return true;
                }
                // low on blocks is not a reason to turn around any more: with two blocks left the last gap is still
                // cheaper than walking back, so keep going and let the extension place them (below)
                if (brain.blocks() < 1 && ctx.nav.path() == null && inPhase > 40) return false;
                // the blocks ran out and the bed is still a bridge away: one emerald buys a bridge (bridge egg)
                if (brain.blocks() < 4 && throwBridgeEgg(bedC)) return true;
                // 4 emeralds buy a pearl straight onto the bed island when the bridge is the problem
                if (brain.blocks() < 8 && pearlToBed(bedC)) return true;
                NavOptions o = NavOptions.bridging(brain.blocks());
                o.maxNodes = 12000;
                o.breakBlocks = true;
                o.breakable = DEFENSE;
                o.extraVoidAversion = brain.finalLife() ? 0.8 : 0;
                o.avoidEnemies = false;
                ctx.nav.goTo(bedC, 3.5, o);
                ctx.nav.tick();
                // No route at all is different from a route that failed: goTo quietly does nothing when the search
                // finds nothing, failed() stays false, and the bot stood on the spot until the stall guard fired
                // three seconds later. Measured: 35 of 45 rushes in one game were exactly this. Build the last
                // stretch ourselves, and if that is not possible hand the decision back now.
                if (ctx.nav.path() == null && !ctx.nav.failed()) {
                    // Planning is asynchronous: the first tick after goTo has no path yet, which is normal. Give the
                    // search two seconds, then treat an empty answer as "no route" rather than standing there.
                    if (noPathSince < 0) noPathSince = ctx.tick;
                    if (ctx.tick - noPathSince < 40) { ctx.nav.tick(); return true; }
                    net.h2020.api.stats.CombatStats.inc("rush.nopath");
                    noPathSince = -1;
                    if (startExtend(bedC)) { net.h2020.api.stats.CombatStats.inc("rush.nopath.extend"); return true; }
                    return false;
                }
                noPathSince = -1;
                if (ctx.nav.failed()) {
                    net.h2020.api.stats.CombatStats.inc("rush.navfail");
                    // no route the navigator likes: if the bed is a short straight line away and we have the blocks,
                    // extend to it ourselves (the "block extension to reach the island" when low on blocks)
                    if (startExtend(bedC)) return true;
                    return false;
                }
                if (inPhase > 20 * 120) return false;
                return true;
            }
            case FOLLOW: {
                if (eyeDist < 6) { phase(Phase.APPROACH); return true; }
                // follow the one leader; re-check the assignment every second so a dead leader is replaced, but never
                // flip mid-bridge more often than that
                if (ctx.tick - lastFollow > 20) {
                    lastFollow = ctx.tick;
                    TeamPlay.Assignment as = TeamPlay.bridging(brain, target);
                    if (as.role == TeamPlay.Role.LEAD) { phase(Phase.TRAVEL); return true; }   // we are the leader now
                    if (as.role == TeamPlay.Role.FOLLOW && as.leader != null) followLeader = as.leader;
                }
                Player leader = followLeader;
                if (leader == null || leader.isDead() || leader.getWorld() != ctx.player.getWorld() || inPhase > 20 * 30) {
                    phase(Phase.TRAVEL);
                    return true;
                }
                NavOptions o = NavOptions.walk();
                o.bridge = false;
                o.avoidEnemies = false;
                ctx.nav.follow(leader.getLocation(), 2.4, o);
                ctx.nav.tick();
                if (ctx.nav.failed()) { phase(Phase.TRAVEL); return true; }
                return true;
            }
            case EXTEND: {
                if (bridger == null) { phase(Phase.TRAVEL); return true; }
                Bridger.Result r = bridger.tick();
                if (r == Bridger.Result.RUNNING) return true;
                bridger = null;
                phase(Phase.TRAVEL);
                return true;
            }
            case APPROACH: {
                if (eyeDist < 4.1) {
                    ctx.nav.stop();
                    int[] cell = tntCell(bed);
                    if (cell != null) { placer.start(cell[0], cell[1], cell[2], Inv.TNT); phase(Phase.TNT); return true; }
                    startBreak(bed); phase(Phase.BREAK); return true;
                }
                NavOptions o = NavOptions.walk();
                o.breakBlocks = true;
                o.breakable = DEFENSE;
                o.parkourGap = 0;
                ctx.nav.goTo(bedC, 1.8, o);
                ctx.nav.tick();
                if (ctx.nav.failed() || inPhase > 20 * 30) return false;
                return true;
            }
            case TNT: {
                BlockPlacer.Result r = placer.tick();
                if (r == BlockPlacer.Result.RUNNING) return true;
                tntUsed = true;
                if (r == BlockPlacer.Result.DONE) { fleeFrom = bedC; phase(Phase.FLEE); }
                else phase(Phase.APPROACH);
                return true;
            }
            case FLEE: {
                // step away from the lit TNT (Hypixel TNT auto-ignites, ~2.6s fuse), then go back in
                if (inPhase > 62) { phase(Phase.APPROACH); return true; }
                Location me = ctx.pos();
                double dx = me.getX() - fleeFrom.getX(), dz = me.getZ() - fleeFrom.getZ();
                double l = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
                if (l < 5.5) {
                    Location away = me.clone().add(dx / l * 3, 0, dz / l * 3);
                    ctx.nav.goTo(away, 1.0, NavOptions.walk());
                    ctx.nav.tick();
                } else ctx.nav.stop();
                return true;
            }
            case BREAK: {
                if (eyeDist > 4.6) { phase(Phase.APPROACH); return true; }
                // a defender is coming: close the cells beside the hole first (block in) so he has to break blocks
                // while we finish the bed - the middle exit stays open, the sides get sealed
                if (covered < 2 && enemyNear(8) && ctx.tick - lastCover > 12 && Inv.has(ctx.player, Inv.BLOCK) && !placer.active()) {
                    int[] c = net.h2020.bots.ai.combat.BedCover.sideCell(ctx, bed, ctx.pos());
                    if (c != null) {
                        placer.setNeedSight(true);
                        placer.setFast(true);
                        placer.start(c[0], c[1], c[2], Inv.BLOCK);
                        covered++;
                        lastCover = ctx.tick;
                    }
                }
                if (placer.active()) {
                    BlockPlacer.Result pr = placer.tick();
                    if (pr == BlockPlacer.Result.RUNNING) return true;
                }
                BlockBreaker.Result r = breaker.tick();
                if (r != BlockBreaker.Result.RUNNING) {
                    if (!view.bedAlive(target)) { phase(Phase.AFTER); return true; }
                    startBreak(bed);
                }
                if (inPhase > 20 * 45) return false;
                return true;
            }
            case AFTER:
            default:
                ctx.nav.stop();
                return inPhase < afterLen && age() < 20 * 200;
        }
    }

    /** An air cell next to hard defense blocks (end stone / obsidian / wood) where TNT would open the bed up. */
    private int[] tntCell(Location foot) {
        if (tntUsed || !ctx.persona.tryhard || !Inv.has(ctx.player, Inv.TNT)) return null;
        WorldView wv = ctx.world();
        int bx = foot.getBlockX(), by = foot.getBlockY(), bz = foot.getBlockZ();
        boolean hard = false;
        for (int dx = -2; dx <= 2 && !hard; dx++) for (int dy = 0; dy <= 2 && !hard; dy++) for (int dz = -2; dz <= 2 && !hard; dz++) {
            Material m = wv.type(bx + dx, by + dy, bz + dz);
            hard = m == Material.ENDER_STONE || m == Material.OBSIDIAN || m == Material.WOOD;
        }
        if (!hard) return null;
        Location eye = ctx.eye();
        int[] best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++) for (int dy = 0; dy <= 2; dy++) for (int dz = -3; dz <= 3; dz++) {
            int x = bx + dx, y = by + dy, z = bz + dz;
            if (wv.shape(x, y, z) != Shape.OPEN) continue;
            if (BlockPlacer.findSupport(wv, x, y, z, eye, 4.2) == null) continue;
            if (!BlockPlacer.cellFree(new Location(eye.getWorld(), x, y, z))) continue;
            double d = (x + 0.5 - bx - 0.5) * (x - bx) + (y - by) * (y - by) + (z - bz) * (z - bz);
            if (d < bd) { bd = d; best = new int[]{x, y, z}; }
        }
        return best;
    }

    /**
     * Pearl onto the bed island: aimed at a landing spot two or three blocks off the bed (never into the defence
     * itself), thrown when the bridge is missing or we are too low on blocks to finish it.
     */
    private boolean pearlToBed(Location bedC) {
        if (ctx.tick - lastPearl < 200) return false;
        if (!Inv.has(ctx.player, Inv.PEARL)) return false;
        Location me = ctx.pos();
        double gap = me.distance(bedC);
        if (gap < 8 || gap > 30) return false;
        WorldView wv = ctx.world();
        double dx = me.getX() - bedC.getX(), dz = me.getZ() - bedC.getZ();
        double l = Math.max(1e-3, Math.sqrt(dx * dx + dz * dz));
        for (double off = 3.0; off <= 5.0; off += 1.0) {
            int lx = (int) Math.floor(bedC.getX() + dx / l * off), lz = (int) Math.floor(bedC.getZ() + dz / l * off);
            int land = wv.landing(lx, (int) Math.floor(bedC.getY()) + 3, lz, 6);
            if (land == Integer.MIN_VALUE) continue;
            if (!Inv.equip(ctx.player, ctx.c, Inv.PEARL, 8)) return true;
            Location spot = new Location(bedC.getWorld(), lx + 0.5, land + 0.2, lz + 0.5);
            ctx.motor.aimPoint(net.h2020.bots.ai.motor.Motor.P_TASK, spot, net.h2020.api.bot.AimIntent.FLICK, 0.6);
            if (ctx.c.aimSettled()) {
                ctx.c.rightClick();
                lastPearl = ctx.tick;
                CombatStats.inc("pearl.bed");
            }
            return true;
        }
        return false;
    }

    /**
     * A bridge egg for the last gap: thrown toward the bed when the wool is gone (the egg lays a wool line under its
     * flight, one emerald for a bridge). Aimed like a real throw, and only once per couple of seconds.
     */
    private boolean throwBridgeEgg(Location goal) {
        if (ctx.tick - lastEgg < 90 || !Inv.has(ctx.player, Inv.BRIDGE_EGG)) return false;
        if (!Inv.equip(ctx.player, ctx.c, Inv.BRIDGE_EGG, 6)) return true;   // hotbar swap landed this tick
        Location eye = ctx.eye();
        double dx = goal.getX() - eye.getX(), dz = goal.getZ() - eye.getZ();
        double l = Math.max(1e-3, Math.sqrt(dx * dx + dz * dz));
        Location aim = eye.clone().add(dx / l * 14, -2.0, dz / l * 14);
        ctx.motor.aimPoint(net.h2020.bots.ai.motor.Motor.P_TASK, aim, net.h2020.api.bot.AimIntent.PRECISE, 0.9);
        if (ctx.c.aimSettled()) {
            ctx.c.rightClick();
            lastEgg = ctx.tick;
            CombatStats.inc("bridgeegg");
        }
        return true;
    }

    /**
     * The navigator found no route (the last gap is a straight line it will not bridge with few blocks left, or the
     * path budget ran out): bridge it ourselves, one line of blocks, at most eight of them.
     * @return true when a straight extension is possible and started
     */
    private boolean startExtend(Location goal) {
        if (bridger != null || ctx.nav.doingAction()) return false;
        Location me = ctx.pos();
        double dx = goal.getX() - me.getX(), dz = goal.getZ() - me.getZ();
        int adx = 0, adz = 0;
        if (Math.abs(dx) > Math.abs(dz) * 2) adx = (int) Math.signum(dx);
        else if (Math.abs(dz) > Math.abs(dx) * 2) adz = (int) Math.signum(dz);
        else return false;                                        // true diagonal: leave it to the navigator
        int gap = (int) Math.max(Math.abs(dx), Math.abs(dz));
        int have = Inv.count(ctx.player, Inv.BLOCK);
        if (gap < 1 || gap > 12 || have < 1) return false;
        int x = (int) Math.floor(me.getX()), y = (int) Math.floor(me.getY() + 0.01), z = (int) Math.floor(me.getZ());
        bridger = new Bridger(ctx);
        bridger.start(adx, adz, x, y, z, Math.min(have, 8), (int) Math.floor(goal.getY() + 0.01));
        phase(Phase.EXTEND);
        return true;
    }

    private void startBreak(Location foot) {
        int[] head = BedDefense.head(foot.getWorld(), foot);
        // break whichever half is closer
        Location eye = ctx.eye();
        double df = eye.distanceSquared(foot.clone().add(0.5, 0.3, 0.5));
        double dh = eye.distanceSquared(new Location(foot.getWorld(), head[0] + 0.5, head[1] + 0.3, head[2] + 0.5));
        if (dh < df) breaker.start(head[0], head[1], head[2], true);
        else breaker.start(foot.getBlockX(), foot.getBlockY(), foot.getBlockZ(), true);
    }

    @Override
    public void abort() {
        super.abort();
        breaker.cancel();
        placer.cancel();
        if (bridger != null) { bridger.cancel(); bridger = null; }
    }
}
