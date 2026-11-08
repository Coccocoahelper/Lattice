package net.h2020.bots.ai.combat;

import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.motor.BlockBreaker;
import net.h2020.bots.ai.motor.BlockPlacer;
import net.h2020.bots.ai.motor.Motor;
import net.h2020.bots.ai.motor.Pillarer;
import net.h2020.bots.ai.util.AIMath;
import net.h2020.bots.ai.world.Shape;
import net.h2020.bots.ai.world.WorldView;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Blocks as a fight tool, for both sides of a duel:
 * <ul>
 * <li><b>Stomp</b> ({@link Style#stomp}): a block placed into the step an opponent running at us is about to take. It
 *   kills his sprint and his momentum for a tick or two, which is the free hit. If he is airborne (he jumped to crit or
 *   to cross something) a second block goes on top of the first - the 2-block pillar - so the jump does not clear it.</li>
 * <li><b>Duel pillar</b> ({@link Style#duelPillar}): two blocks, one on top of the other, in the cell between us. Then
 *   we strafe around it: his client is still rendering us where we were (half a round trip back), so the first hit off
 *   the flank lands before he has turned. Only on ground we can circle on - never on a one-wide bridge.</li>
 * <li><b>Middle exit</b> ({@link Style#middleExit}): on a line (bridge, corridor) we dig the block out of the middle of
 *   the line between us and put a block back into the hole from our side, so the opponent has to jump a step in the
 *   middle of the line while we stand behind it: the guide's "break the middle of the line for free hits".</li>
 * <li><b>Head hitter</b> ({@link Style#headHitter}): a block placed two cells above our head from a side support, so we
 *   can jump under it while crossing: less knockback taken and more momentum, which is what players use on bridges.</li>
 * <li>The old fleeing tools stay: tower up when low and chased ({@link Pillarer}), wall the bridge behind us, cut the
 *   bridge with shears / a pickaxe.</li>
 * </ul>
 * Everything is real crosshair work through {@link BlockPlacer} / {@link BlockBreaker}, so a placement that is not
 * physically possible from where the bot stands simply fails.
 */
final class BlockTactics {
    private static final int NONE = 0, TOWER = 1, WALL = 2, CUT = 3, STOMP = 4, DUEL = 5, MIDDLE = 6, HEAD = 7,
            DRIVETHRU = 8;

    private final BotContext ctx;
    private final Style style;
    private final Pillarer pillar;
    private final BlockPlacer placer;
    private final BlockBreaker breaker;

    private int mode = NONE;
    private Player foe;
    private int[][] cells;
    private int cellIdx;
    private int phase;
    private long started;
    private long lastRamp = -10000;
    private long lastSafety = -10000;
    private long lastTower = -10000, lastWall = -10000, lastCut = -10000, lastStomp = -10000, lastDuel = -10000,
            lastMiddle = -10000, lastHead = -10000, lastRise = -10000;
    /** After towering: hold the high ground instead of chasing down. */
    long holdHighUntil = -1;
    double highY;
    /** Combat's current clicks per second (the flank keeps hitting like the rest of the fight). */
    double cps = 8;
    /** Combat's live prediction of winning this fight (input for the placement policy). */
    double winProb = 0.5;
    /** Under a head hitter: jump while moving to use it. */
    private long headHitUntil = -1;
    private long flankUntil = -1;
    private long lastDriveThru = -10000;
    private Location driveFoe;
    /** Middle exit: a head-height cell to dig after the feet cell, and whether the hole is filled back in. */
    private int[] headCell;
    private boolean refill;
    private int flankSign = 1;

    BlockTactics(BotContext ctx, Style style) {
        this.ctx = ctx;
        this.style = style;
        this.pillar = new Pillarer(ctx);
        this.placer = new BlockPlacer(ctx);
        this.breaker = new BlockBreaker(ctx);
    }

    boolean busy() { return mode != NONE; }

    void cancel() {
        if (mode == TOWER) pillar.cancel();
        if (mode == WALL || mode == STOMP || mode == DUEL || mode == HEAD) placer.cancel();
        if (mode == CUT || mode == MIDDLE) breaker.cancel();
        mode = NONE;
        foe = null;
        flankUntil = -1;
    }

    boolean holdingHigh() { return ctx.tick < holdHighUntil && ctx.pos().getY() > highY - 0.6; }

    /** A head hitter is up: jumping under it is what makes it worth the block. */
    boolean wantsJump() { return ctx.tick < headHitUntil; }

    /** Keep running the current tactic. @return true while it drives the inputs. */
    boolean tick() {
        if (ctx.tick - started > 200) { cancel(); return false; }
        switch (mode) {
            case TOWER: {
                Pillarer.Result r = pillar.tick();
                if (r == Pillarer.Result.RUNNING) return true;
                mode = NONE;
                if (r == Pillarer.Result.DONE) {
                    CombatStats.inc("block.pillar.ok");
                    highY = ctx.pos().getY();
                    holdHighUntil = ctx.tick + AIMath.range(ctx.rng, 80, 200);
                }
                return false;
            }
            case WALL: {
                BlockPlacer.Result r = placer.tick();
                if (r == BlockPlacer.Result.RUNNING) return true;
                if (r == BlockPlacer.Result.DONE && cellIdx == 0) CombatStats.inc("block.wall");
                if (++cellIdx < cells.length && r == BlockPlacer.Result.DONE) {
                    placer.setNeedSight(true);
                    placer.setFast(true);
                    placer.start(cells[cellIdx][0], cells[cellIdx][1], cells[cellIdx][2], Inv.BLOCK);
                    return true;
                }
                mode = NONE;
                return false;
            }
            case CUT: {
                BlockBreaker.Result r = breaker.tick();
                if (r == BlockBreaker.Result.RUNNING) return true;
                if (r == BlockBreaker.Result.DONE) CombatStats.inc("block.cut");
                mode = NONE;
                return false;
            }
            case STOMP: {
                BlockPlacer.Result r = placer.tick();
                if (r == BlockPlacer.Result.RUNNING) return true;
                if (r == BlockPlacer.Result.DONE) net.h2020.bots.ai.ml.PlacementPolicy.completed((int) (ctx.tick - started));
                if (r == BlockPlacer.Result.DONE) {
                    CombatStats.inc("block.stomp");
                    if (cellIdx + 1 < cells.length) {
                        cellIdx++;
                        placer.setNeedSight(true);
                        placer.setFast(true);
                        placer.start(cells[cellIdx][0], cells[cellIdx][1], cells[cellIdx][2], Inv.BLOCK);
                        return true;
                    }
                }
                mode = NONE;
                return false;
            }
            case DUEL: {
                if (phase == 0) {
                    BlockPlacer.Result r = placer.tick();
                    if (r == BlockPlacer.Result.RUNNING) return true;
                    if (r == BlockPlacer.Result.DONE && cellIdx + 1 < cells.length) {
                        cellIdx++;
                        placer.setNeedSight(true);
                        placer.setFast(true);
                        placer.start(cells[cellIdx][0], cells[cellIdx][1], cells[cellIdx][2], Inv.BLOCK);
                        return true;
                    }
                    if (cellIdx + 1 >= cells.length) {
                        CombatStats.inc("block.duelpillar");
                        phase = 1;
                        flankUntil = ctx.tick + AIMath.range(ctx.rng, 8, 16);
                        // the side the fight-flow model says he is drifting away from is the side to take
                        int open = foe == null ? 0 : net.h2020.bots.ai.ml.FightFlow.openingSide(ctx, foe, 3.0);
                        flankSign = open != 0 ? open : (ctx.rng.nextBoolean() ? 1 : -1);
                    } else {
                        mode = NONE;
                        return false;
                    }
                }
                return flank();
            }
            case MIDDLE: {
                if (phase == 0) {
                    BlockBreaker.Result r = breaker.tick();
                    if (r == BlockBreaker.Result.RUNNING) return true;
                    if (r == BlockBreaker.Result.DONE) {
                        CombatStats.inc("block.middleexit");
                        if (headCell != null) {                       // the wall is two high: open the head cell too
                            int[] h = headCell;
                            headCell = null;
                            breaker.start(h[0], h[1], h[2], false);
                            return true;
                        }
                        if (refill) {                                 // the line case fills the hole from our side
                            phase = 1;
                            placer.setNeedSight(true);
                            placer.setFast(true);
                            placer.start(cells[0][0], cells[0][1], cells[0][2], Inv.BLOCK);
                            return true;
                        }
                        mode = NONE;                                  // the wall case: the hole is the way through
                        return false;
                    }
                    mode = NONE;
                    return false;
                }
                BlockPlacer.Result r = placer.tick();
                if (r == BlockPlacer.Result.RUNNING) return true;
                if (r == BlockPlacer.Result.DONE) CombatStats.inc("block.middleexit.fill");
                mode = NONE;
                return false;
            }
            case DRIVETHRU: {
                // two short walls at their shoulders: a strafe becomes a bounce, and a hit sends them straight back
                if (foe == null || foe.isDead() || driveFoe == null
                        || foe.getLocation().distance(driveFoe) > 1.8
                        || ctx.pos().distance(foe.getLocation()) > 6.5) { cancel(); return false; }
                BlockPlacer.Result r = placer.tick();
                if (r == BlockPlacer.Result.RUNNING) return true;
                if (r == BlockPlacer.Result.DONE) {
                    CombatStats.inc("block.drivethru.place");
                    cellIdx++;
                    if (cellIdx < cells.length) {
                        placer.setNeedSight(true);
                        placer.start(cells[cellIdx][0], cells[cellIdx][1], cells[cellIdx][2], Inv.BLOCK);
                        return true;
                    }
                }
                mode = NONE;
                return false;
            }
            case HEAD: {
                BlockPlacer.Result r = placer.tick();
                if (r == BlockPlacer.Result.RUNNING) return true;
                mode = NONE;
                if (r == BlockPlacer.Result.DONE) {
                    net.h2020.bots.ai.ml.PlacementPolicy.completed((int) (ctx.tick - started));
                    CombatStats.inc("block.headhitter");
                    headHitUntil = ctx.tick + AIMath.range(ctx.rng, 25, 60);
                }
                return false;
            }
            default:
                return false;
        }
    }

    /** Strafe around the pillar we just built, crosshair on him, sword out: the free hit is on the far side. */
    private boolean flank() {
        if (foe == null || !valid(foe) || ctx.tick >= flankUntil) { mode = NONE; foe = null; return false; }
        Location me = ctx.pos();
        Location tl = ctx.posOf(foe);
        double dx = tl.getX() - me.getX(), dz = tl.getZ() - me.getZ();
        double l = Math.max(1e-3, Math.sqrt(dx * dx + dz * dz));
        dx /= l; dz /= l;
        double px = -dz * flankSign, pz = dx * flankSign;
        WorldView wv = ctx.world();
        int y = floor(me.getY() + 0.01);
        int aheadX = floor(me.getX() + px * 1.4), aheadZ = floor(me.getZ() + pz * 1.4);
        boolean ground = wv.standable(aheadX, y - 1, aheadZ) || wv.standable(aheadX, y, aheadZ);
        if (!ground) {
            flankSign = -flankSign;
            px = -px; pz = -pz;
        }
        ctx.motor.aimEntity(Motor.P_COMBAT, foe, net.h2020.api.bot.AimIntent.COMBAT);
        ctx.motor.moveToward(px * 0.9 + dx * 0.55, pz * 0.9 + dz * 0.55);
        ctx.motor.sprint(true);
        if (l < 3.4) ctx.motor.holdLeft(cps);
        return true;
    }

    /**
     * Should we use blocks right now? Starts a tactic and returns true when it does.
     * @param losing hp low and the opponent healthier
     */
    /** The fight as the placement code sees it once per tick: positions, the line we are on, what we carry. */
    private static final class Scene {
        Player target;
        double d;
        boolean losing, onLine, hotBlocks;
        Location me, tl;
        WorldView wv;
        int x, y, z, blocks;
        double hp, dy;
    }

    private Scene scene(Player target, double d, boolean losing) {
        Scene f = new Scene();
        f.target = target;
        f.d = d;
        f.losing = losing;
        f.me = ctx.pos();
        f.tl = ctx.posOf(target);
        f.wv = ctx.world();
        f.me = ctx.pos();
        f.x = floor(f.me.getX()); f.y = floor(f.me.getY() + 0.01); f.z = floor(f.me.getZ());
        f.blocks = Inv.count(ctx.player, Inv.BLOCK);
        f.hotBlocks = Inv.findHotbar(ctx.player, Inv.BLOCK) >= 0;
        f.hp = ctx.health();
        f.onLine = f.wv.voidSides(f.x, f.y, f.z) >= 2;
        f.dy = Math.abs(f.tl.getY() - f.me.getY());
        return f;
    }

    /**
     * Should we use blocks right now? The learned placement policy ranks what is worth trying in this kind of fight
     * (persona habit + what has actually won fights); the first ranked action that is physically possible starts.
     * @param losing hp low and the opponent healthier
     */
    boolean consider(Player target, double d, boolean losing) {
        if (mode != NONE) return tick();
        if (!CombatTuning.blockPlay || target == null) return false;
        if (ctx.nav.doingAction() || !ctx.c.onGround()) return false;
        Scene f = scene(target, d, losing);
        if (f.blocks < 1 || !f.hotBlocks) return false;
        int[] order = net.h2020.bots.ai.ml.PlacementPolicy.rank(ctx, style, target, d, f.onLine, losing, winProb);
        for (int i = 0; i < order.length; i++) {
            boolean started;
            switch (order[i]) {
                case net.h2020.bots.ai.ml.PlacementPolicy.STOMP: started = doStomp(f); break;
                case net.h2020.bots.ai.ml.PlacementPolicy.DUEL_PILLAR: started = doDuelPillar(f); break;
                case net.h2020.bots.ai.ml.PlacementPolicy.MIDDLE_EXIT: started = doMiddleExit(f); break;
                case net.h2020.bots.ai.ml.PlacementPolicy.HEAD_HITTER: started = doHeadHitter(f); break;
                case net.h2020.bots.ai.ml.PlacementPolicy.TOWER: started = doTower(f); break;
                case net.h2020.bots.ai.ml.PlacementPolicy.WALL: started = doWall(f); break;
                case net.h2020.bots.ai.ml.PlacementPolicy.CUT: started = doCut(f); break;
                case net.h2020.bots.ai.ml.PlacementPolicy.SHELTER: started = doShelter(f); break;
                case net.h2020.bots.ai.ml.PlacementPolicy.BACKSTEP: started = doBackstep(f); break;
                case net.h2020.bots.ai.ml.PlacementPolicy.RAMP: started = doRamp(f); break;
                case net.h2020.bots.ai.ml.PlacementPolicy.DRIVE_THRU: started = doDriveThru(f); break;
                default: started = false; break;
            }
            if (started) {
                net.h2020.bots.ai.ml.PlacementPolicy.record(order, i);
                net.h2020.bots.ai.ml.SelfStats.onBedBlock(ctx.player, ctx.tick);
                return true;
            }
        }
        return false;
    }

    /**
     * Middle exit. What the clip shows: a 1-thick wall/line between the two of you, and instead of walking around
     * the end of it (where he is waiting) you dig the block out of the <b>middle</b> of the run and step through the
     * hole - he is still watching the end of the wall when the hit comes from the middle.
     * <p>
     * Rule: the cell straight ahead is solid, exactly one block thick (air behind it), a breakable defence material,
     * and there is a run of at least three of them; the cell chosen is the one in the middle of that run nearest to
     * the opponent's lateral position. If there is no wall ahead, the same idea is applied to the line of a bridge:
     * dig out the middle of it and put a block back into the hole from our side, so they have to jump a step in the
     * middle of the line while we stand behind it.
     */
    private boolean doMiddleExit(Scene f) {
        if (f.d < 2.2 || f.d > 7.5 || f.dy > 1.5) return false;
        if (ctx.tick - lastMiddle <= 240) return false;
        int[] dir = lineDir(f.me, f.tl);
        if (dir == null) return false;

        // --- the wall case: a one-thick wall right in front of us
        int wx = f.x + dir[0], wz = f.z + dir[1];
        if (!f.wv.passable(wx, f.y, wz) && f.wv.passable(wx + dir[0], f.y, wz + dir[1])) {
            Material m = f.wv.type(wx, f.y, wz);
            if (breakable(m) && !teammateNear(wx, f.y, wz, 4.0)) {
                int[] cell = middleOfRun(f, dir, wx, wz);
                if (cell != null) {
                    lastMiddle = ctx.tick;
                    started = ctx.tick;
                    phase = 0;
                    refill = false;
                    cells = new int[][]{{cell[0], cell[1], cell[2]}};
                    headCell = !f.wv.passable(cell[0], cell[1] + 1, cell[2]) ? new int[]{cell[0], cell[1] + 1, cell[2]} : null;
                    breaker.start(cell[0], cell[1], cell[2], false);
                    mode = MIDDLE;
                    foe = f.target;
                    CombatStats.inc("block.middleexit.wall");
                    if (ctx.nav.active()) ctx.nav.stop();
                    tick();
                    return true;
                }
            }
        }

        // --- the line case: a bridge under both of us, dig the middle out and fill it from our side
        if (!f.onLine || f.d < 3.2 || f.d > 7.5) return false;
        int cx = f.x + dir[0] * 2, cz = f.z + dir[1] * 2;
        Material m = f.wv.type(cx, f.y - 1, cz);
        if (!net.h2020.api.game.PlacedRegistry.isPlaced(f.me.getWorld(), cx, f.y - 1, cz)) return false;
        if (!Shape.floor(f.wv.shape(cx, f.y - 1, cz)) || m == Material.BEDROCK
                || !f.wv.passable(cx, f.y, cz) || !f.wv.passable(cx, f.y + 1, cz)
                || !f.wv.passable(f.x + dir[0] * 3, f.y, f.z + dir[1] * 3)
                || BlockPlacer.findSupport(f.wv, cx, f.y, cz, ctx.eye(), 4.4, true) == null) return false;
        lastMiddle = ctx.tick;
        started = ctx.tick;
        phase = 0;
        refill = true;
        headCell = null;
        cells = new int[][]{{cx, f.y, cz}};
        breaker.start(cx, f.y - 1, cz, false);
        mode = MIDDLE;
        foe = f.target;
        CombatStats.inc("block.middleexit.try");
        if (ctx.nav.active()) ctx.nav.stop();
        tick();
        return true;
    }

    /** The breakable cell in the middle of the wall run around (wx,wz), biased towards where the opponent is. */
    private int[] middleOfRun(Scene f, int[] dir, int wx, int wz) {
        // lateral axis of the wall
        int lx = -dir[1], lz = dir[0];
        int lo = 0, hi = 0;
        while (lo > -4 && wallAt(f, wx + lx * (lo - 1), wz + lz * (lo - 1), dir)) lo--;
        while (hi < 4 && wallAt(f, wx + lx * (hi + 1), wz + lz * (hi + 1), dir)) hi++;
        if (hi - lo + 1 < 3) return null;
        // the opponent's lateral position relative to us, clamped into the run
        double lat = (f.tl.getX() - f.me.getX()) * lx + (f.tl.getZ() - f.me.getZ()) * lz;
        int want = (int) Math.round(lat);
        if (want < lo) want = lo;
        if (want > hi) want = hi;
        // ... but the point of the trick is the middle: stay near the centre of the run
        int mid = (lo + hi) / 2;
        if (Math.abs(want - mid) > 0) want = (want + mid) / 2;
        int x = wx + lx * want, z = wz + lz * want;
        return wallAt(f, x, z, dir) ? new int[]{x, f.y, z} : null;
    }

    /** A one-thick wall cell at feet level with air behind it. */
    private boolean wallAt(Scene f, int x, int z, int[] dir) {
        if (f.wv.passable(x, f.y, z)) return false;
        if (!net.h2020.api.game.PlacedRegistry.isPlaced(f.me.getWorld(), x, f.y, z)) return false;
        if (!f.wv.passable(x + dir[0], f.y, z + dir[1])) return false;
        return breakable(f.wv.type(x, f.y, z));
    }

    private boolean breakable(Material m) {
        switch (m) {
            case WOOL: case STAINED_CLAY: case HARD_CLAY: case WOOD: case LOG: case LOG_2: case ENDER_STONE:
            case SANDSTONE: case COBBLESTONE: case STONE: case STAINED_GLASS: case GLASS:
                return true;
            default:
                return false;
        }
    }

    /**
     * Stomp: a block into the step he is taking (a second one on top when he is airborne). The cell is the one he is
     * walking into, not the one he stands in - the fight-flow model says where he will be in three ticks.
     */
    /**
     * Drive-thru: a wall on each shoulder of the enemy, leaving the corridor between them open. The point is not the
     * block that lands - it is what the two walls take away. A player who can no longer strafe out of the line has to
     * eat the knockback straight down the corridor, and near a void edge straight back is the void. The guide's own
     * description is "forces them into a corridor where your knockback is lethal".
     */
    private boolean doDriveThru(Scene f) {
        if (f.blocks < 3 || f.d < 2.2 || f.d > 6.0 || f.dy > 1.0) return false;
        if (ctx.tick - lastDriveThru < 150 || ctx.rng.nextDouble() >= 0.4) return false;
        // worth it most when they are near an edge: the corridor turns their own strafe into a fall
        boolean edge = f.wv.voidSides((int) Math.floor(f.tl.getX()), (int) Math.floor(f.tl.getY()), (int) Math.floor(f.tl.getZ())) >= 1;
        double dx = f.tl.getX() - f.me.getX(), dz = f.tl.getZ() - f.me.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-3) return false;
        int px = (int) Math.round(-dz / len), pz = (int) Math.round(dx / len);
        int ex = (int) Math.floor(f.tl.getX()), ey = (int) Math.floor(f.tl.getY()), ez = (int) Math.floor(f.tl.getZ());
        java.util.List<int[]> build = new java.util.ArrayList<int[]>();
        for (int side : new int[]{1, -1}) {
            for (int step = 1; step <= 2; step++) {
                int x = ex + px * side * step, z = ez + pz * side * step;
                if (!Shape.floor(f.wv.shape(x, ey - 1, z))) continue;
                if (!f.wv.passable(x, ey, z) || !f.wv.passable(x, ey + 1, z)) continue;
                build.add(new int[]{x, ey, z});
            }
        }
        if (build.size() < (edge ? 3 : 4)) return false;
        cells = build.toArray(new int[build.size()][]);
        cellIdx = 0;
        foe = f.target;
        driveFoe = f.tl.clone();
        started = ctx.tick;
        lastDriveThru = ctx.tick;
        placer.setNeedSight(false);
        placer.start(cells[0][0], cells[0][1], cells[0][2], Inv.BLOCK);
        mode = DRIVETHRU;
        CombatStats.inc("block.drivethru");
        tick();
        return true;
    }

    private boolean doStomp(Scene f) {
        if (f.d < 2.3 || f.d > 4.6 || f.dy > 1.2) return false;
        if (ctx.tick - lastStomp <= 60) return false;
        if (!movingTowardMe(f.target, f.me) && f.d >= 3.1) return false;
        if (ctx.rng.nextDouble() >= style.stomp) return false;
        Location ahead = net.h2020.bots.ai.ml.FightFlow.aheadOf(ctx, f.target, f.d, 3);
        int tx = floor(ahead.getX()), ty = floor(f.tl.getY() + 0.01), tz = floor(ahead.getZ());
        int[] dir = lineDir(f.me, f.tl);
        if (dir == null) return false;
        int sx = tx + dir[0], sz = tz + dir[1];
        if (!f.wv.passable(sx, ty, sz) || !f.wv.passable(sx, ty + 1, sz)) return false;
        boolean air = !f.target.isOnGround() || f.tl.getY() - ty > 0.35;
        lastStomp = ctx.tick;
        started = ctx.tick;
        cellIdx = 0;
        cells = air ? new int[][]{{sx, ty, sz}, {sx, ty + 1, sz}} : new int[][]{{sx, ty, sz}};
        placer.setNeedSight(true);
        placer.start(cells[0][0], cells[0][1], cells[0][2], Inv.BLOCK);
        mode = STOMP;
        foe = f.target;
        CombatStats.inc(air ? "block.stomp.try.air" : "block.stomp.try");
        if (ctx.nav.active()) ctx.nav.stop();
        tick();
        return true;
    }

    /** Two-block duel pillar, then the strafe around it (needs room to circle: not on a one-wide line). */
    private boolean doDuelPillar(Scene f) {
        if (f.blocks < 2 || f.onLine || f.d < 2.6 || f.d > 4.8 || f.dy > 1.0) return false;
        if (ctx.tick - lastDuel <= 200 || ctx.rng.nextDouble() >= style.duelPillar) return false;
        int[] dir = lineDir(f.me, f.tl);
        if (dir == null) return false;
        int cx = f.x + dir[0], cz = f.z + dir[1];
        if (!f.wv.passable(cx, f.y, cz) || !f.wv.passable(cx, f.y + 1, cz)
                || !Shape.floor(f.wv.shape(cx, f.y - 1, cz))
                || !f.wv.standable(f.x - dir[0], f.y - 1, f.z - dir[1])) return false;
        lastDuel = ctx.tick;
        started = ctx.tick;
        cellIdx = 0;
        phase = 0;
        cells = new int[][]{{cx, f.y, cz}, {cx, f.y + 1, cz}};
        placer.setNeedSight(true);
        placer.start(cells[0][0], cells[0][1], cells[0][2], Inv.BLOCK);
        mode = DUEL;
        foe = f.target;
        CombatStats.inc("block.duelpillar.try");
        if (ctx.nav.active()) ctx.nav.stop();
        tick();
        return true;
    }

    /** Head hitter: a block two above our own head on a contested line, from a side support. */
    private boolean doHeadHitter(Scene f) {
        if (f.d < 3.5 || f.dy > 1.5) return false;
        if (ctx.tick - lastHead <= 300 || ctx.rng.nextDouble() >= style.headHitter) return false;
        if (!f.onLine && !corridor(f.wv, f.x, f.y, f.z)) return false;
        int hy = f.y + 3;
        if (!f.wv.passable(f.x, hy, f.z)) return false;
        if (BlockPlacer.findSupport(f.wv, f.x, hy, f.z, ctx.eye(), 4.4, true) == null) return false;
        if (!BlockPlacer.cellFree(new Location(f.me.getWorld(), f.x, hy, f.z))) return false;
        lastHead = ctx.tick;
        started = ctx.tick;
        cells = new int[][]{{f.x, hy, f.z}};
        placer.setNeedSight(true);
        placer.start(f.x, hy, f.z, Inv.BLOCK);
        mode = HEAD;
        CombatStats.inc("block.headhitter.try");
        tick();
        return true;
    }

    /** Tower: re-establish height when they got above us, or get out of melee when low and chased. */
    private boolean doTower(Scene f) {
        if (!style.blockIn || f.onLine) return false;
        double up = f.tl.getY() - f.me.getY();
        // 1) they are above us: stack back up to their level
        if (up > 1.6 && up < 4.5 && f.d < 7 && f.blocks >= 2 && ctx.tick - lastRise > 300
                && Math.abs(f.tl.getX() - f.me.getX()) + Math.abs(f.tl.getZ() - f.me.getZ()) < 6) {
            boolean room = true;
            for (int i = 2; i <= (int) Math.ceil(up) + 2; i++) if (!f.wv.passable(f.x, f.y + i, f.z)) { room = false; break; }
            if (room) {
                lastRise = ctx.tick;
                started = ctx.tick;
                pillar.start(f.x, f.y, f.z, (int) Math.ceil(up) + 1);
                mode = TOWER;
                CombatStats.inc("block.rise");
                ctx.nav.stop();
                tick();
                return true;
            }
            return false;
        }
        // 2) low and chased: tower up and heal / bow from the top
        if (!f.losing || f.hp > 8 || f.d < 2.2 || f.d > 7 || f.blocks < 4 || ctx.tick - lastTower <= 600 || f.dy > 1.5) return false;
        boolean room = true;
        for (int i = 2; i <= 5; i++) if (!f.wv.passable(f.x, f.y + i, f.z)) { room = false; break; }
        if (!room || ctx.rng.nextDouble() >= 0.25) return false;
        lastTower = ctx.tick;
        started = ctx.tick;
        pillar.start(f.x, f.y, f.z, AIMath.range(ctx.rng, 3, 4));
        mode = TOWER;
        CombatStats.inc("block.pillar");
        ctx.nav.stop();
        tick();
        return true;
    }

    /** Wall: two blocks in the bridge cell between us, what a chaser has to stop and break. */
    /**
     * Shelter: a two-high wall between me and a shooter, built while the arrow or fireball is still travelling. Same
     * placement machinery as the wall, but it is triggered by ranged pressure rather than by a melee line, and it can
     * be built at any distance inside bow range.
     */
    private boolean doShelter(Scene f) {
        if (f.blocks < 2 || f.d < 3.0 || f.d > 14 || f.dy > 2.0) return false;
        if (ctx.tick - lastWall <= 60 || ctx.rng.nextDouble() >= 0.35) return false;
        int[] dir = lineDir(f.me, f.tl);
        if (dir == null) return false;
        int bx = f.x + dir[0], bz = f.z + dir[1];
        if (!Shape.floor(f.wv.shape(bx, f.y - 1, bz))) return false;
        if (!f.wv.passable(bx, f.y, bz) || !f.wv.passable(bx, f.y + 1, bz)) return false;
        lastWall = ctx.tick;
        started = ctx.tick;
        cells = new int[][]{{bx, f.y, bz}, {bx, f.y + 1, bz}};
        cellIdx = 0;
        placer.setNeedSight(true);
        placer.start(bx, f.y, bz, Inv.BLOCK);
        mode = WALL;
        ctx.nav.stop();
        CombatStats.inc("block.shelter");
        return true;
    }

    /**
     * Ramp: one block placed at the feet in front, jumped on, for high ground in a fight. The oldest trick in Bed Wars
     * after the block-hit: you get the angle, they have to look up.
     */
    /**
     * Safety block: put one block between the body and the void before the fight starts, so a knockback lands on
     * something. This is the cheapest void-death prevention there is, and the one players actually do at an edge.
     */
    public boolean safetyBlock(Location me, Location target) {
        if (!CombatTuning.blockPlay || target == null) return false;
        if (ctx.tick - lastSafety <= 80) return false;
        if (!Inv.holding(ctx.player, Inv.BLOCK) && !Inv.has(ctx.player, Inv.BLOCK)) return false;
        if (!ctx.c.onGround() || ctx.nav.doingAction()) return false;
        int[] dir = lineDir(me, target);                     // toward the opponent
        if (dir == null) return false;
        int bx = (int) Math.floor(me.getX()) - dir[0];        // one cell away from him, toward the void
        int bz = (int) Math.floor(me.getZ()) - dir[1];
        int by = (int) Math.floor(me.getY());
        if (!Shape.passable(ctx.world().shape(bx, by, bz))) return false;   // already something there
        lastSafety = ctx.tick;
        cells = new int[][]{{bx, by, bz}};
        cellIdx = 0;
        placer.setNeedSight(false);
        placer.start(bx, by, bz, Inv.BLOCK);
        mode = WALL;
        CombatStats.inc("block.safety");
        return true;
    }

    private boolean doRamp(Scene f) {
        if (f.blocks < 1 || f.d < 2.2 || f.d > 6.5 || f.dy > 1.0) return false;
        if (ctx.tick - lastRamp <= 80 || ctx.rng.nextDouble() >= 0.4) return false;
        int[] dir = lineDir(f.me, f.tl);
        if (dir == null) return false;
        int bx = f.x + dir[0], bz = f.z + dir[1];
        // the target cell is the one in front at the feet, and it needs a floor under it
        if (!Shape.floor(f.wv.shape(bx, f.y - 1, bz))) return false;
        if (!f.wv.passable(bx, f.y, bz) || !f.wv.passable(bx, f.y + 1, bz)) return false;
        if (Shape.floor(f.wv.shape(f.x, f.y, f.z))) return false;                // already standing on something high
        lastRamp = ctx.tick;
        started = ctx.tick;
        cells = new int[][]{{bx, f.y, bz}};
        cellIdx = 0;
        placer.setNeedSight(true);
        placer.start(bx, f.y, bz, Inv.BLOCK);
        mode = WALL;
        ctx.nav.stop();
        CombatStats.inc("block.ramp");
        return true;
    }

    /**
     * Backstep: block the chaser behind me while backing out of a fight - the placement that turns a retreat into a
     * live chase instead of a free hit.
     */
    private boolean doBackstep(Scene f) {
        if (f.blocks < 1 || f.d < 2.5 || f.d > 7) return false;
        if (ctx.tick - lastWall <= 80 || ctx.rng.nextDouble() >= 0.4) return false;
        int[] dir = lineDir(f.tl, f.me);                 // away from the target
        if (dir == null) return false;
        int bx = f.x + dir[0], bz = f.z + dir[1];
        if (!Shape.floor(f.wv.shape(bx, f.y - 1, bz))) return false;
        if (!f.wv.passable(bx, f.y, bz)) return false;
        lastWall = ctx.tick;
        started = ctx.tick;
        cells = new int[][]{{bx, f.y, bz}};
        cellIdx = 0;
        placer.setNeedSight(true);
        placer.start(bx, f.y, bz, Inv.BLOCK);
        mode = WALL;
        ctx.nav.stop();
        CombatStats.inc("block.backstep");
        return true;
    }

    private boolean doWall(Scene f) {
        if (!style.bridgeBlock || !f.onLine || f.d < 2.8 || f.d > 8 || f.blocks < 2 || f.dy > 1.2) return false;
        if (ctx.tick - lastWall <= 200 || ctx.rng.nextDouble() >= 0.2) return false;
        int[] dir = lineDir(f.me, f.tl);
        if (dir == null) return false;
        int bx = f.x + dir[0], bz = f.z + dir[1];
        if (!Shape.floor(f.wv.shape(bx, f.y - 1, bz)) || !f.wv.passable(bx, f.y, bz) || !f.wv.passable(bx, f.y + 1, bz)) return false;
        lastWall = ctx.tick;
        started = ctx.tick;
        cells = new int[][]{{bx, f.y, bz}, {bx, f.y + 1, bz}};
        cellIdx = 0;
        placer.setNeedSight(true);
        placer.start(bx, f.y, bz, Inv.BLOCK);
        mode = WALL;
        ctx.nav.stop();
        tick();
        return true;
    }

    /** Cut: break the bridge block behind us (fast with shears on wool, a pickaxe on clay and stone). */
    private boolean doCut(Scene f) {
        if (!style.bridgeBlock || !f.onLine || f.d < 3.5 || f.d > 8 || f.dy > 1.2) return false;
        if (ctx.tick - lastCut <= 200 || ctx.rng.nextDouble() >= 0.15) return false;
        int[] dir = lineDir(f.me, f.tl);
        if (dir == null) return false;
        int cx = f.x + dir[0] * 2, cz = f.z + dir[1] * 2;
        Material m = f.wv.type(cx, f.y - 1, cz);
        if (!net.h2020.api.game.PlacedRegistry.isPlaced(f.me.getWorld(), cx, f.y - 1, cz)) return false;
        // never shear a teammate into the void - the enemy behind him is not worth it
        if (teammateNear(cx, f.y - 1, cz, 6.0)) { CombatStats.inc("block.cut.teammate"); return false; }
        if (!Shape.floor(f.wv.shape(cx, f.y - 1, cz)) || !fastToBreak(m)) return false;
        lastCut = ctx.tick;
        started = ctx.tick;
        breaker.start(cx, f.y - 1, cz, false);
        mode = CUT;
        ctx.nav.stop();
        tick();
        return true;
    }

    /** Dominant horizontal axis from us to them (the line we are both on), or null when it is a true diagonal. */
    private int[] lineDir(Location me, Location tl) {
        double dx = tl.getX() - me.getX(), dz = tl.getZ() - me.getZ();
        if (Math.abs(dx) > Math.abs(dz) * 2) return new int[]{(int) Math.signum(dx), 0};
        if (Math.abs(dz) > Math.abs(dx) * 2) return new int[]{0, (int) Math.signum(dz)};
        return null;
    }

    /** He is on the ground and his velocity points at us (he is running in). */
    private boolean movingTowardMe(Player target, Location me) {
        net.h2020.bots.ai.perception.Sighting s = ctx.perception.get(target);
        if (s == null || s.lastPos == null) return false;
        double dx = me.getX() - s.lastPos.getX(), dz = me.getZ() - s.lastPos.getZ();
        double l = Math.sqrt(dx * dx + dz * dz);
        if (l < 1e-3) return false;
        return s.lastVel.getX() * dx / l + s.lastVel.getZ() * dz / l > 0.045;
    }

    /** Is a teammate standing on / next to this cell (or on the line just behind it)? Then do not shear it. */
    private boolean teammateNear(int x, int y, int z, double r) {
        Location cell = new Location(ctx.player.getWorld(), x + 0.5, y + 0.5, z + 0.5);
        for (net.h2020.bots.ai.perception.Sighting s : ctx.perception.all()) {
            if (!s.ally || s.lastPos == null) continue;
            Location e = s.estimate(ctx.tick);
            if (e == null || e.getWorld() != cell.getWorld()) continue;
            if (e.distance(cell) < r) return true;
            // also anybody within a couple of blocks beyond the cut would drop with it
            if (e.distance(ctx.pos()) < r + 3 && Math.abs(e.getY() - cell.getY()) < 2.5) return true;
        }
        return false;
    }

    /** A one-wide corridor: solid on two opposite sides at our level, open above. */
    private boolean corridor(WorldView wv, int x, int y, int z) {
        int solid = 0;
        if (!wv.passable(x + 1, y, z)) solid++;
        if (!wv.passable(x - 1, y, z)) solid++;
        if (!wv.passable(x, y, z + 1)) solid++;
        if (!wv.passable(x, y, z - 1)) solid++;
        return solid >= 2 && wv.passable(x, y + 1, z) && wv.passable(x, y + 2, z);
    }

    private boolean fastToBreak(Material m) {
        if (m == Material.WOOL) return Inv.findHotbar(ctx.player, Inv.SHEARS) >= 0;
        if (m == Material.STAINED_CLAY || m == Material.HARD_CLAY || m == Material.SANDSTONE || m == Material.COBBLESTONE || m == Material.STONE)
            return Inv.findHotbar(ctx.player, Inv.PICKAXE) >= 0;
        return m == Material.WOOD && Inv.findHotbar(ctx.player, Inv.AXE) >= 0;
    }

    private boolean valid(Player p) {
        return p != null && p.isOnline() && !p.isDead() && p.getWorld() == ctx.player.getWorld();
    }

    private static int floor(double d) { int i = (int) d; return d < i ? i - 1 : i; }
}
