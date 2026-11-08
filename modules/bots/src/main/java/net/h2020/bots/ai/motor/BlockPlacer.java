package net.h2020.bots.ai.motor;

import net.h2020.api.stats.CombatStats;
import net.h2020.api.bot.AimIntent;
import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.util.AIMath;
import net.h2020.bots.ai.world.Shape;
import net.h2020.bots.ai.world.WorldView;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

/**
 * Places one block into a target cell like a player: pick a visible support face in reach, hold a block, move the
 * crosshair onto that face (PRECISE), right-click only when the client's ray actually hits that face, verify.
 */
public final class BlockPlacer {
    public enum Result { RUNNING, DONE, FAILED }

    private static final BlockFace[] FACES = {BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST, BlockFace.UP};

    private final BotContext ctx;
    private int tx, ty, tz;
    private Inv.Pred blockPred = Inv.BLOCK;
    private int ticks, clicks, verifyWait, aimWait;
    private Block support;
    private BlockFace face;
    /** The current support is interactive (bed/chest): the click has to be made while sneaking. */
    private boolean sneakFor;
    /** Ladders and other non-solid items may go in a cell our own body covers; a block may not. */
    private boolean ignoreEntities;
    private Location aimPoint;
    private boolean active;
    private boolean needSight;
    private int hiddenTicks;
    /** Aim profile for the placement: PRECISE for measured building, FLICK for the placements made inside a fight. */
    private boolean fast;

    public BlockPlacer(BotContext ctx) { this.ctx = ctx; }

    private int supportTries;
    private final java.util.Set<Long> triedFaces = new java.util.HashSet<Long>();

    public void start(int x, int y, int z, Inv.Pred blocks) {
        tx = x; ty = y; tz = z;
        blockPred = blocks == null ? Inv.BLOCK : blocks;
        ticks = 0; clicks = 0; verifyWait = 0; support = null; face = null; aimPoint = null; active = true;
        aimWait = 0; hiddenTicks = 0;
    }

    public boolean active() { return active; }

    /** Only use support faces the eye can see, and give a hidden cell up fast instead of staring at it. */
    public void setNeedSight(boolean v) { needSight = v; }

    /**
     * In a fight a placement has about a third of a second to land, so the crosshair goes there in one movement
     * (FLICK) and the click does not wait for the aim to settle: the client's own ray on the face is the gate, and it
     * is the same check that would have been made after the wait anyway.
     */
    public void setFast(boolean v) { fast = v; }

    public void setIgnoreEntities(boolean v) { ignoreEntities = v; }

    public static boolean interactable(Material m) {
        switch (m) {
            case BED_BLOCK: case CHEST: case TRAPPED_CHEST: case ENDER_CHEST: case WORKBENCH: case FURNACE:
            case BURNING_FURNACE: case ENCHANTMENT_TABLE: case ANVIL: case BREWING_STAND: case WOODEN_DOOR:
            case TRAP_DOOR: case FENCE_GATE: case LEVER: case STONE_BUTTON: case WOOD_BUTTON: case BEACON:
            case HOPPER: case DISPENSER: case DROPPER: case NOTE_BLOCK: case JUKEBOX: case CAKE_BLOCK:
            case DIODE_BLOCK_OFF: case DIODE_BLOCK_ON: case IRON_TRAPDOOR: case TNT: case COMMAND:
                return true;
            default:
                return false;
        }
    }

    /** Best support block + face for placing into (x,y,z) from the eye, or null. Result[0]=Block, [1]=BlockFace. */
    public static Object[] findSupport(WorldView wv, int x, int y, int z, Location eye, double reach) {
        return findSupport(wv, x, y, z, eye, reach, false);
    }

    /**
     * As above; with {@code needSight} only faces the eye can actually see count (blocks placed a moment ago hide
     * the floor behind them), and Result[2] is a visible point on that face to aim at.
     */
    public static Object[] findSupport(WorldView wv, int x, int y, int z, Location eye, double reach, boolean needSight) {
        return findSupport(wv, x, y, z, eye, reach, needSight, null);
    }

    /**
     * @param avoid packed (block, face) pairs already tried for this cell: a face that failed once (occluded, out of
     *              the aim model's reach) will fail again, and re-picking it is why a placement could burn its whole
     *              timeout choosing the same corner
     */
    public static Object[] findSupport(WorldView wv, int x, int y, int z, Location eye, double reach, boolean needSight,
                                       java.util.Set<Long> avoid) {
        Object[] best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockFace f : FACES) {
            // support is the neighbour opposite to f: clicking support's face f places into (x,y,z)
            int sx = x - f.getModX(), sy = y - f.getModY(), sz = z - f.getModZ();
            byte s = wv.shape(sx, sy, sz);
            if (s != Shape.FULL && s != Shape.HIGH && s != Shape.LOW) continue;
            Material m = wv.type(sx, sy, sz);
            // an interactive support (a bed, a chest) is placeable while sneaking - right-clicking it without shift
            // opens it instead, which is why the blocks on top of the bed were never going down
            boolean sneakFor = interactable(m);
            double hx = sx + 0.5 + f.getModX() * 0.5, hy = sy + 0.5 + f.getModY() * 0.5, hz = sz + 0.5 + f.getModZ() * 0.5;
            if (s == Shape.LOW && f == BlockFace.UP) hy = sy + wv.height(sx, sy, sz);
            // must face the eye
            double ex = eye.getX() - hx, ey = eye.getY() - hy, ez = eye.getZ() - hz;
            double dot = ex * f.getModX() + ey * f.getModY() + ez * f.getModZ();
            if (dot <= 0.05) continue;
            double d = Math.sqrt(ex * ex + ey * ey + ez * ez);
            if (d > reach) continue;
            long key = packFace(sx, sy, sz, f);
            if (avoid != null && avoid.contains(key)) continue;
            // how far the hand has to travel: a face behind the head costs a full turn, and the turn is what the
            // aim model cannot finish inside the placement's patience
            double yawRad = Math.toRadians(eye.getYaw()), pitchRad = Math.toRadians(eye.getPitch());
            double lx = -Math.sin(yawRad) * Math.cos(pitchRad), ly = -Math.sin(pitchRad), lz = Math.cos(yawRad) * Math.cos(pitchRad);
            double align = lx * f.getModX() + ly * f.getModY() + lz * f.getModZ();      // 1 = dead ahead, -1 = behind
            double turnCost = (1.0 - align) * 1.6;                                      // up to 3.2 blocks of "cost"
            double score = d + turnCost - dot * 0.3 + (f == BlockFace.DOWN ? 1.5 : 0);
            if (score < bestScore) {
                Location seen = null;
                if (needSight) {
                    seen = visiblePoint(wv, eye, sx, sy, sz, f, hy - (sy + 0.5 + f.getModY() * 0.5));
                    if (seen == null) continue;
                }
                bestScore = score;
                best = new Object[]{eye.getWorld().getBlockAt(sx, sy, sz), f, seen, sneakFor};
            }
        }
        return best;
    }

    /** Pack a block coordinate and face index into one long, for the avoid set. */
    private static long packFace(int x, int y, int z, BlockFace f) {
        int fi = f.ordinal() & 7;
        long h = ((long) (x & 0x3FFFFFF) << 38) ^ ((long) (y & 0xFFF) << 26) ^ ((long) (z & 0x3FFFFFF) << 1);
        return h | fi;
    }

    private static final double[][] FACE_SAMPLES = {{0, 0}, {0.3, 0.3}, {-0.3, 0.3}, {0.3, -0.3}, {-0.3, -0.3}};

    /** A point on face f of block (sx,sy,sz) with a clear line from the eye, or null when the face is hidden. */
    private static Location visiblePoint(WorldView wv, Location eye, int sx, int sy, int sz, BlockFace f, double yAdj) {
        for (double[] o : FACE_SAMPLES) {
            double px = sx + 0.5 + f.getModX() * 0.5, py = sy + 0.5 + f.getModY() * 0.5 + yAdj, pz = sz + 0.5 + f.getModZ() * 0.5;
            if (f.getModX() != 0) { py += o[0]; pz += o[1]; }
            else if (f.getModY() != 0) { px += o[0]; pz += o[1]; }
            else { px += o[0]; py += o[1]; }
            // end a hair outside the face so the trace stops in the (empty) target cell, not in the support itself
            double ex = px + f.getModX() * 0.02, ey = py + f.getModY() * 0.02, ez = pz + f.getModZ() * 0.02;
            if (clearLine(wv, eye.getX(), eye.getY(), eye.getZ(), ex, ey, ez)) {
                return new Location(eye.getWorld(), px, py, pz);
            }
        }
        return null;
    }

    /**
     * The crosshair's view: any collidable cell on the way (beds, slabs, ladders, not just full cubes) catches the
     * ray, so partial blocks count as blocking. The start (eye) and end cells are not checked.
     */
    private static boolean clearLine(WorldView wv, double x0, double y0, double z0, double x1, double y1, double z1) {
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) return true;
        dx /= len; dy /= len; dz /= len;
        int x = (int) Math.floor(x0), y = (int) Math.floor(y0), z = (int) Math.floor(z0);
        int ex = (int) Math.floor(x1), ey = (int) Math.floor(y1), ez = (int) Math.floor(z1);
        int stx = dx > 0 ? 1 : -1, sty = dy > 0 ? 1 : -1, stz = dz > 0 ? 1 : -1;
        double tdx = dx == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dx);
        double tdy = dy == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dy);
        double tdz = dz == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dz);
        double tmx = dx == 0 ? Double.MAX_VALUE : ((stx > 0 ? (x + 1 - x0) : (x0 - x)) * tdx);
        double tmy = dy == 0 ? Double.MAX_VALUE : ((sty > 0 ? (y + 1 - y0) : (y0 - y)) * tdy);
        double tmz = dz == 0 ? Double.MAX_VALUE : ((stz > 0 ? (z + 1 - z0) : (z0 - z)) * tdz);
        for (int steps = 0; steps < 32; steps++) {
            if (x == ex && y == ey && z == ez) return true;
            if (tmx < tmy && tmx < tmz) { if (tmx > len) return true; x += stx; tmx += tdx; }
            else if (tmy < tmz) { if (tmy > len) return true; y += sty; tmy += tdy; }
            else { if (tmz > len) return true; z += stz; tmz += tdz; }
            if (x == ex && y == ey && z == ez) return true;
            byte sh = wv.shape(x, y, z);
            if (sh != Shape.OPEN && sh != Shape.WATER) return false;
        }
        return true;
    }

    /** Cell is free of entity bounding boxes (players would block the placement). */
    public static boolean cellFree(Location cellCorner) { return cellFree(cellCorner, null); }

    /**
     * @param self the placer's own body: standing beside the cell you are filling is normal, and counting yourself as
     *             an obstruction is what made a wall placement wait out its whole timeout
     */
    public static boolean cellFree(Location cellCorner, Entity self) {
        for (Entity e : cellCorner.getWorld().getNearbyEntities(cellCorner.clone().add(0.5, 0.5, 0.5), 1.4, 1.6, 1.4)) {
            if (!(e instanceof LivingEntity)) continue;
            if (self != null && e.getUniqueId().equals(self.getUniqueId())) continue;
            Location l = e.getLocation();
            double minX = l.getX() - 0.3, maxX = l.getX() + 0.3, minY = l.getY(), maxY = l.getY() + 1.8;
            double minZ = l.getZ() - 0.3, maxZ = l.getZ() + 0.3;
            if (maxX > cellCorner.getX() && minX < cellCorner.getX() + 1 && maxY > cellCorner.getY() && minY < cellCorner.getY() + 1
                    && maxZ > cellCorner.getZ() && minZ < cellCorner.getZ() + 1) return false;
        }
        return true;
    }

    public Result tick() {
        if (!active) return Result.FAILED;
        ticks++;
        WorldView wv = ctx.world();
        if (!Shape.passable(wv.shape(tx, ty, tz))) {
            active = false;
            return Result.DONE;
        }
        if (ticks > 60) { active = false; net.h2020.api.stats.CombatStats.inc("place.fail.timeout"); return Result.FAILED; }
        if (verifyWait > 0) { verifyWait--; return Result.RUNNING; }
        if (!Inv.equip(ctx.player, ctx.c, blockPred, 1)) {
            if (!Inv.has(ctx.player, blockPred)) { active = false; net.h2020.api.stats.CombatStats.inc("place.fail.material"); return Result.FAILED; }
            return Result.RUNNING;
        }
        Location eye = ctx.eye();
        if (support == null || ticks % (fast ? 8 : 10) == 0) {
            Object[] s = findSupport(wv, tx, ty, tz, eye, 4.4, needSight, triedFaces);
            if (s == null) { active = false; net.h2020.api.stats.CombatStats.inc("place.fail.nosupport"); return Result.FAILED; }
            Block nb = (Block) s[0];
            BlockFace nf = (BlockFace) s[1];
            sneakFor = s.length > 3 && Boolean.TRUE.equals(s[3]);
            if (support == null || !nb.equals(support) || nf != face) {
                support = nb; face = nf;
                double jx = (ctx.rng.nextDouble() - 0.5) * 0.5, jy = (ctx.rng.nextDouble() - 0.5) * 0.5, jz = (ctx.rng.nextDouble() - 0.5) * 0.5;
                if (face.getModX() != 0) jx = 0;
                if (face.getModY() != 0) jy = 0;
                if (face.getModZ() != 0) jz = 0;
                aimPoint = s[2] != null ? (Location) s[2] : new Location(eye.getWorld(), support.getX() + 0.5 + face.getModX() * 0.5 + jx,
                        support.getY() + 0.5 + face.getModY() * 0.5 + jy, support.getZ() + 0.5 + face.getModZ() * 0.5 + jz);
                aimWait = 0;
            }
        }
        ctx.motor.aimPoint(Motor.P_TASK, aimPoint, fast ? AimIntent.FLICK : AimIntent.PRECISE);
        aimWait++;
        if (!ignoreEntities && !cellFree(new Location(eye.getWorld(), tx, ty, tz), ctx.player)) return Result.RUNNING;
        Block tb = ctx.c.targetedBlock();
        BlockFace tf = ctx.c.targetedFace();
        if (tb != null && tb.equals(support) && tf == face && aimWait >= (fast ? 0 : 2)) {
            // placing against a bed or a chest only works while crouched: without shift the client would use the block
            if (sneakFor && !ctx.motor.sneaking()) { ctx.motor.sneak(true); return Result.RUNNING; }
            ctx.c.rightClick();
            // remember the cell: if it is taken down within three seconds the placement was a miss, not a win
            net.h2020.bots.ai.ml.PlacementPolicy.notePlaced(tx, ty, tz, System.currentTimeMillis());
            if (sneakFor) { ctx.motor.sneak(false); net.h2020.api.stats.CombatStats.inc("place.oninteractive"); }
            clicks++;
            verifyWait = fast ? 0 : 1;
            if (clicks > 4) { active = false; net.h2020.api.stats.CombatStats.inc("place.fail.clicks"); return Result.FAILED; }
        } else if (needSight && aimWait > 3 && ctx.c.aimSettled() && ++hiddenTicks > 8) {
            // the crosshair arrived at the aim point but the client says it is on something else: the face is
            // occluded (usually by the block we just put down). Try another face before calling the cell impossible.
            if (++supportTries < 3) {
                triedFaces.add(packFace(support.getX(), support.getY(), support.getZ(), face));
                support = null;
                aimWait = 0;
                hiddenTicks = 0;
                net.h2020.api.stats.CombatStats.inc("place.retry.face");
                return Result.RUNNING;
            }
            active = false;
            net.h2020.api.stats.CombatStats.inc("place.fail.hidden");
            return Result.FAILED;
        } else if (aimWait > 20) {
            // crosshair never landed (blocked view): try another support next tick
            support = null;
            aimWait = 0;
        }
        return Result.RUNNING;
    }

    public void cancel() { active = false; }

    /** Has the click for this target already gone out? The defence uses it to start the next cell without waiting. */
    public boolean clickedThisTarget() { return clicks > 0; }

    public int targetX() { return tx; }
    public int targetY() { return ty; }
    public int targetZ() { return tz; }

    static double dist(Location a, double x, double y, double z) {
        return Math.sqrt(AIMath.hDistSq(a.getX(), a.getZ(), x, z) + (a.getY() - y) * (a.getY() - y));
    }
}
