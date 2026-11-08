package net.h2020.bots.ai.nav;

import net.h2020.bots.ai.util.AIMath;
import net.h2020.bots.ai.world.Shape;
import net.h2020.bots.ai.world.WorldView;
import org.bukkit.Material;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Incremental weighted A* over the 1.8 voxel walkability graph with action edges (walk, jump, drop, parkour, ladder,
 * swim, bridge, pillar, break). step() expands nodes until a deadline so a search can be spread over several ticks.
 * Runs on the main thread (Bukkit world reads), budgeted by PathService.
 */
public final class PathFinder {
    public enum Status { RUNNING, FOUND, PARTIAL, FAILED }

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final int[][] DIRS4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final double SQRT2 = Math.sqrt(2);

    private final PathRequest req;
    private final WorldView wv;
    private final Map<Long, PathNode> nodes = new HashMap<>(4096);
    private PathNode[] heap = new PathNode[1024];
    private int heapSize;
    private PathNode best;
    private PathNode goalNode;
    private int expanded;
    private Status status = Status.RUNNING;
    private Path result;

    public PathFinder(PathRequest req) {
        this.req = req;
        this.wv = new WorldView(req.world, true);
        PathNode start = node(req.sx, req.sy, req.sz);
        double s = wv.stand(req.sx, req.sy, req.sz);
        if (Double.isNaN(s)) { s = req.sy; start.virtualFloor = true; }
        start.stand = s;
        start.g = 0;
        start.h = heuristic(start);
        start.f = start.h * req.heuristicWeight;
        best = start;
        push(start);
    }

    public Status status() { return status; }
    public Path result() { return result; }
    public int expanded() { return expanded; }
    public org.bukkit.World world() { return req.world; }

    /** One-line summary for debugging failed searches. */
    public String describe() {
        return String.format("start %d,%d,%d [%s on %s] (stand %s) goal %.1f,%.1f,%.1f expanded %d/%d bridge<=%d",
                req.sx, req.sy, req.sz, wv.type(req.sx, req.sy, req.sz), wv.type(req.sx, req.sy - 1, req.sz), best == null ? "?" : String.valueOf(best.stand), req.gx, req.gy, req.gz,
                expanded, req.maxNodes, req.maxBridgeBlocks);
    }

    /** Expand until done or System.nanoTime() passes deadline. */
    public Status step(long deadlineNanos) {
        if (status != Status.RUNNING) return status;
        int sinceCheck = 0;
        while (heapSize > 0) {
            if (++sinceCheck >= 16) {
                sinceCheck = 0;
                if (System.nanoTime() > deadlineNanos) return status;
            }
            PathNode n = pop();
            n.closed = true;
            if (isGoal(n)) { goalNode = n; finish(n, false); return status; }
            if (n.h < best.h) best = n;
            if (++expanded >= req.maxNodes) break;
            expand(n);
        }
        if (best != null && best.parent != null) finish(best, true);
        else { status = Status.FAILED; result = null; }
        return status;
    }

    // ------------------------------------------------------------------------------------------------ graph

    private boolean isGoal(PathNode n) {
        double dx = n.x + 0.5 - req.gx, dz = n.z + 0.5 - req.gz;
        if (dx * dx + dz * dz > req.goalRadius * req.goalRadius) return false;
        return req.anyY || Math.abs(n.stand - req.gy) <= req.goalVertical;
    }

    private double heuristic(PathNode n) {
        double dx = Math.abs(n.x + 0.5 - req.gx), dz = Math.abs(n.z + 0.5 - req.gz);
        double oct = Math.max(dx, dz) + (SQRT2 - 1) * Math.min(dx, dz);
        double dy = req.gy - n.stand;
        return Math.max(0, oct - req.goalRadius) + (dy > 0 ? dy * 0.8 : -dy * 0.35);
    }

    private void expand(PathNode n) {
        int x = n.x, y = n.y, z = n.z;
        boolean fullFloor = Math.abs(n.stand - y) < 0.01;
        byte here = wv.shape(x, y, z);

        for (int d = 0; d < 8; d++) {
            int dx = DIRS[d][0], dz = DIRS[d][1];
            boolean diag = d >= 4;
            int nx = x + dx, nz = z + dz;
            if (diag) {
                // no corner cutting: both orthogonal cells must be clear for the whole body
                if (!wv.passable(x + dx, y, z) || !wv.passable(x + dx, y + 1, z)
                        || !wv.passable(x, y, z + dz) || !wv.passable(x, y + 1, z + dz)) continue;
                // a fence/wall one below a corner cell still sticks 0.5 into it (1.5 tall collision)
                if (wv.shape(x + dx, y - 1, z) == Shape.TALL || wv.shape(x, y - 1, z + dz) == Shape.TALL) continue;
            }
            double base = diag ? SQRT2 : 1.0;

            // same level (or slab step)
            double s = wv.stand(nx, y, nz);
            if (!Double.isNaN(s) && s - n.stand <= 0.6 && s - n.stand >= -0.6
                    && (!fix(NavStats.FIX_HEADROOM) || s - n.stand <= 0.01 || headroom(n, dx, dz, diag, s, false))) {
                byte fs = wv.shape(nx, y, nz);
                double c = base * (fs == Shape.WATER ? NavCosts.WATER / NavCosts.SPRINT : 1.0);
                // the bot floats (holds jump) instead of walking the bottom: prefer routes along the surface
                if (fs == Shape.WATER && wv.shape(nx, y + 1, nz) == Shape.WATER) c += 1.5;
                if (fs == Shape.WEB) c += 12;
                link(n, nx, y, nz, s, fs == Shape.WATER ? MoveType.SWIM : MoveType.WALK, c, false, -1);
                continue;
            }
            // one up: slab step or jump
            double s1 = wv.stand(nx, y + 1, nz);
            // swimming at the surface, pushing into the bank gives vanilla's 0.3 "jump out of water" boost: that
            // clears a bank up to ~1.6 above the water cell (a pool rimmed with a slab on top of the edge block),
            // not just the 1.25 of a jump from the ground
            double maxUp = fix(NavStats.FIX_WATER) && here == Shape.WATER && wv.shape(x, y + 1, z) != Shape.WATER ? 1.6 : 1.25;
            if (!Double.isNaN(s1) && s1 - n.stand <= maxUp && s1 - n.stand > 0.0 && wv.passable(x, y + 2, z)) {
                if (!diag || (wv.passable(x + dx, y + 2, z) && wv.passable(x, y + 2, z + dz))) {
                    boolean step = s1 - n.stand <= 0.6;
                    // a stair entered from its low side is two 0.5 steps: walked, not jumped (players never jump stairs)
                    if (!step && !diag && fix(NavStats.FIX_STAIRS) && wv.shape(nx, y, nz) == Shape.FULL && Math.abs(s1 - (y + 1)) < 0.01) {
                        double et = wv.entryTop(nx, y, nz, dx, dz);
                        if (!Double.isNaN(et) && et > 0.05 && y + et - n.stand <= 0.6 && 1.0 - et <= 0.6) step = true;
                    }
                    if (!fix(NavStats.FIX_HEADROOM) || headroom(n, dx, dz, diag, s1, !step)) {
                        link(n, nx, y + 1, nz, s1, step ? MoveType.WALK : MoveType.JUMP_UP, base + (step ? 0.1 : NavCosts.JUMP_ONE / NavCosts.SPRINT), false, -1);
                        continue;
                    }
                }
            }
            boolean openCol = wv.passable(nx, y, nz) && wv.passable(nx, y + 1, nz);
            // step into the top of a ladder shaft beside us and climb down it (a plain drop would be too far to
            // count, but the ladder catches the fall like it does for players)
            if (openCol && !diag && wv.shape(nx, y, nz) == Shape.OPEN && wv.shape(nx, y - 1, nz) == Shape.CLIMB
                    && climbBacking(nx, y - 1, nz)) {
                double sd = wv.stand(nx, y - 1, nz);
                if (!Double.isNaN(sd)) link(n, nx, y - 1, nz, sd, MoveType.LADDER, base + NavCosts.LADDER_DOWN / NavCosts.SPRINT, false, -1);
            }
            if (openCol) {
                // drop down
                int land = wv.landing(nx, y - 1, nz, 32);
                boolean dropped = false;
                if (land != Integer.MIN_VALUE) {
                    double ls = wv.stand(nx, land, nz);
                    if (!Double.isNaN(ls)) {
                        double fall = n.stand - ls;
                        boolean water = wv.shape(nx, land, nz) == Shape.WATER;
                        double dmg = water ? 0 : Math.max(0, Math.ceil(fall - 3.0));
                        if ((fall <= req.maxFall || water) && dmg <= req.maxFallDamage) {
                            link(n, nx, land, nz, ls, MoveType.DROP, base + NavCosts.WALK_OFF / NavCosts.SPRINT + NavCosts.fall((int) Math.round(fall)) / NavCosts.SPRINT
                                    + dmg * NavCosts.DAMAGE_PER_HP / NavCosts.SPRINT, false, -1);
                            dropped = true;
                        }
                    }
                }
                if (!dropped && req.maxBridgeBlocks > 0 && fullFloor && wv.shape(nx, y - 1, nz) == Shape.OPEN
                        && n.blocksUsed < req.maxBridgeBlocks - (diag ? 1 : 0)) {
                    // bridge out over the void; a diagonal step is a staircase of two blocks (x, then z)
                    int dir = d;
                    double c = req.bridgeCost + (diag ? 0.45 : 0);
                    if (n.bridgeDir < 0) c += 2.0;             // turning around to start bridging
                    else if (n.bridgeDir != dir) c += 3.0;     // changing bridge direction
                    PathNode b = link(n, nx, y, nz, y, MoveType.BRIDGE, c, true, dir);
                    if (b != null) b.blocksUsed = Math.max(b.blocksUsed, n.blocksUsed + (diag ? 2 : 1));
                }
                // parkour over a gap
                if (!diag && req.maxParkourGap > 0 && fullFloor && !n.virtualFloor) parkour(n, dx, dz);
            } else if (!diag && req.breakBlocks) {
                breakThrough(n, nx, nz);
            }
        }

        // ladders
        // (1.8 climbs only while the body is pushed against something solid: decorative vines hanging in the open,
        // or on other vines, cannot be climbed, so a rung needs a collidable neighbour beside the body)
        if ((here == Shape.CLIMB || wv.shape(x, y + 1, z) == Shape.CLIMB) && climbBacking(x, y, z)) {
            double su = wv.stand(x, y + 1, z);
            if (!Double.isNaN(su) && wv.passable(x, y + 2, z)) link(n, x, y + 1, z, su, MoveType.LADDER, NavCosts.LADDER_UP / NavCosts.SPRINT, false, -1);
        }
        // top of a ladder that ends below an opening (roof hatch): climb out sideways onto the floor around the hole
        if (here == Shape.CLIMB && wv.shape(x, y + 1, z) != Shape.CLIMB && wv.passable(x, y + 1, z) && wv.passable(x, y + 2, z)) {
            // stepping off over the ladder's backing block is how players do it (keep pressing W: the body lands on
            // the ladder's top edge, then steps up). Any other side needs that same landing first, which the steering
            // cannot do, so those exits are only offered when there is no backing-side one.
            boolean backed = false;
            for (int pass = 0; pass < 2 && !backed; pass++) {
                for (int[] dir : DIRS4) {
                    int ex = x + dir[0], ez = z + dir[1];
                    boolean backing = wv.supportive(ex, y, ez);
                    if (backing != (pass == 0)) continue;
                    double se = wv.stand(ex, y + 1, ez);
                    if (!Double.isNaN(se) && se - y <= 1.6) {
                        link(n, ex, y + 1, ez, se, MoveType.LADDER, (backing ? 1.15 : 2.3) * NavCosts.LADDER_UP / NavCosts.SPRINT, false, -1);
                        if (backing) backed = true;
                    }
                }
            }
        }
        if (wv.shape(x, y - 1, z) == Shape.CLIMB) {
            double sd = wv.stand(x, y - 1, z);
            if (!Double.isNaN(sd)) link(n, x, y - 1, z, sd, MoveType.LADDER, NavCosts.LADDER_DOWN / NavCosts.SPRINT, false, -1);
        }
        // swim up
        if (here == Shape.WATER && wv.shape(x, y + 1, z) == Shape.WATER) {
            double sw = wv.stand(x, y + 1, z);
            if (!Double.isNaN(sw)) link(n, x, y + 1, z, sw, MoveType.SWIM, NavCosts.WATER / NavCosts.SPRINT, false, -1);
        }
        // pillar up
        if (req.pillar && fullFloor && n.blocksUsed < req.maxBridgeBlocks && here == Shape.OPEN
                && wv.passable(x, y + 2, z) && wv.passable(x, y + 3, z) && req.gy > n.stand + 0.5) {
            PathNode p = link(n, x, y + 1, z, y + 1, MoveType.PILLAR,
                    (NavCosts.SPRINT + NavCosts.JUMP_ONE + NavCosts.PLACE) / NavCosts.SPRINT, true, -1);
            if (p != null) p.blocksUsed = Math.max(p.blocksUsed, n.blocksUsed + 1);
        }
    }

    private static boolean fix(int bit) { return (NavStats.fix & bit) != 0; }

    /**
     * Rising from n to a floor at height `to` needs the head to clear to+1.8 above the take-off column (and, on a
     * diagonal, above both corner columns): the vanilla step-up lifts the box in place before moving it, and a jump
     * bonks on anything lower. From a slab/soul sand/chest floor that can reach one cell higher than the plain y+2
     * check covers.
     */
    private boolean headroom(PathNode n, int dx, int dz, boolean diag, double to, boolean jump) {
        int need = (int) Math.floor(to + 1.8 + (jump ? 0.15 : 0.0) - 1e-3);
        for (int k = (int) Math.floor(n.stand + 1.8 - 1e-3) + 1; k <= need; k++) {
            if (!wv.passable(n.x, k, n.z)) return false;
            if (diag && (!wv.passable(n.x + dx, k, n.z) || !wv.passable(n.x, k, n.z + dz))) return false;
        }
        return true;
    }

    private boolean climbBacking(int x, int y, int z) {
        for (int[] d : DIRS4) {
            if (Shape.solid(wv.shape(x + d[0], y, z + d[1])) || Shape.solid(wv.shape(x + d[0], y + 1, z + d[1]))) return true;
        }
        return false;
    }

    private void parkour(PathNode n, int dx, int dz) {
        int x = n.x, y = n.y, z = n.z;
        if (!wv.passable(x, y + 2, z)) return;
        for (int k = 2; k <= req.maxParkourGap + 1; k++) {
            int gx = x + dx * (k - 1), gz = z + dz * (k - 1);
            if (!wv.passable(gx, y, gz) || !wv.passable(gx, y + 1, gz) || !wv.passable(gx, y + 2, gz)) return;
            if (wv.standable(gx, y, gz) || wv.standable(gx, y - 1, gz)) return; // not a gap: walking/dropping covers it
            int lx = x + dx * k, lz = z + dz * k;
            boolean gapVoid = wv.voidBelow(gx, y, gz, 24);
            double risk = gapVoid ? req.voidAversion * 3.0 * k : 0.6;
            // level or one lower landing
            for (int ly = y; ly >= y - 1; ly--) {
                double ls = wv.stand(lx, ly, lz);
                if (Double.isNaN(ls) || !wv.passable(lx, ly + 2, lz)) continue;
                double dh = ls - n.stand;
                if (dh > 0.01) continue;
                if (k == req.maxParkourGap + 1 && dh > -0.5 && k >= 4) continue;
                link(n, lx, ly, lz, ls, MoveType.PARKOUR, NavCosts.parkour(k) / NavCosts.SPRINT + risk, false, -1);
                return;
            }
            // one up only over a single-block gap
            if (k == 2) {
                double ls = wv.stand(lx, y + 1, lz);
                if (!Double.isNaN(ls) && ls - n.stand <= 1.0 && wv.passable(lx, y + 3, lz) && wv.passable(gx, y + 3, gz)) {
                    link(n, lx, y + 1, lz, ls, MoveType.PARKOUR, NavCosts.parkour(k) / NavCosts.SPRINT + NavCosts.JUMP_ONE / NavCosts.SPRINT + risk, false, -1);
                    return;
                }
            }
        }
    }

    private void breakThrough(PathNode n, int nx, int nz) {
        int y = n.y;
        if (!wv.supportive(nx, y - 1, nz)) return;
        double c = 1.0;
        for (int yy = y; yy <= y + 1; yy++) {
            byte s = wv.shape(nx, yy, nz);
            if (Shape.passable(s)) continue;
            // only player-placed blocks can be broken; a map block stops this route dead instead of pretending
            if (!req.breakable(nx, yy, nz)) return;
            Material m = wv.type(nx, yy, nz);
            double bc = breakCost(m);
            if (bc < 0) return;
            c += bc;
        }
        link(n, nx, y, nz, y, MoveType.BREAK, c, false, -1);
    }

    private double breakCost(Material m) {
        if (req.breakable != null && !req.breakable.contains(m)) return -1;
        double c = baseBreakCost(m);
        if (c < 0) return c;
        net.h2020.bots.ai.inv.Inv.Pred tool = net.h2020.bots.ai.motor.BlockBreaker.toolFor(m);
        if (tool == net.h2020.bots.ai.inv.Inv.PICKAXE && !req.hasPickaxe) c *= 5;
        else if (tool == net.h2020.bots.ai.inv.Inv.AXE && !req.hasAxe) c *= 1.5;
        return c;
    }

    private double baseBreakCost(Material m) {
        switch (m) {
            case BEDROCK: case BARRIER: case ENDER_PORTAL_FRAME: case CHEST: case TRAPPED_CHEST: case BED_BLOCK:
            case ENDER_CHEST:
                return -1;
            case WOOL: case LEAVES: case LEAVES_2: case GLASS: case STAINED_GLASS: case THIN_GLASS: return 1.5;
            case STAINED_CLAY: case HARD_CLAY: case DIRT: case GRASS: case SAND: case GRAVEL: return 2.5;
            case WOOD: case LOG: case LOG_2: return 3.5;
            case ENDER_STONE: case STONE: case COBBLESTONE: return 4.5;
            case OBSIDIAN: return 40;
            // ores: slow, and digging through them reads as "mining" on camera; only when there is no other way
            case COAL_ORE: case IRON_ORE: case GOLD_ORE: case DIAMOND_ORE: case EMERALD_ORE: case LAPIS_ORE:
            case REDSTONE_ORE: case GLOWING_REDSTONE_ORE:
                return 14;
            default: return 6;
        }
    }

    private PathNode node(int x, int y, int z) {
        long k = AIMath.pack(x, y, z);
        PathNode n = nodes.get(k);
        if (n == null) { n = new PathNode(x, y, z, k); n.g = Double.MAX_VALUE; nodes.put(k, n); }
        return n;
    }

    private PathNode link(PathNode from, int x, int y, int z, double stand, MoveType move, double cost, boolean virtual, int bridgeDir) {
        long k = AIMath.pack(x, y, z);
        if (req.forbidden.contains(k)) return null;
        PathNode n = node(x, y, z);
        if (n.closed) return null;
        double extra = 0;
        int vs = virtual && move == MoveType.BRIDGE ? 2 : wv.voidSides(x, y, z);
        extra += vs * req.voidAversion;
        if (wv.lavaNear(x, y, z)) extra += req.lavaAversion;
        if (!req.threats.isEmpty()) {
            for (Vector t : req.threats) {
                double ddx = x + 0.5 - t.getX(), ddy = y - t.getY(), ddz = z + 0.5 - t.getZ();
                double d2 = ddx * ddx + ddy * ddy + ddz * ddz;
                if (d2 < 144) extra += req.threatWeight / (1 + d2 / 9.0);
            }
        }
        double g = from.g + cost + extra;
        if (g >= n.g) return null;
        n.g = g;
        n.stand = stand;
        n.parent = from;
        n.move = move;
        n.virtualFloor = virtual;
        n.bridgeDir = bridgeDir;
        n.blocksUsed = from.blocksUsed;
        n.h = heuristic(n);
        n.f = g + n.h * req.heuristicWeight;
        if (n.heapIndex >= 0) up(n.heapIndex); else push(n);
        return n;
    }

    private void finish(PathNode end, boolean partial) {
        List<PathNode> chain = new ArrayList<>();
        for (PathNode p = end; p != null; p = p.parent) chain.add(p);
        Collections.reverse(chain);
        List<PathPoint> pts = new ArrayList<>(chain.size());
        int blocks = 0;
        for (PathNode p : chain) {
            int danger = p.move == MoveType.BRIDGE ? 2 : wv.voidSides(p.x, p.y, p.z);
            pts.add(new PathPoint(p.x, p.y, p.z, p.stand, p.move, danger));
            if (p.move == MoveType.BRIDGE || p.move == MoveType.PILLAR) blocks++;
        }
        result = new Path(PathSmoother.smooth(pts, wv), partial, blocks, end.g);
        status = partial ? Status.PARTIAL : Status.FOUND;
    }

    // ------------------------------------------------------------------------------------------------ heap

    private void push(PathNode n) {
        if (heapSize == heap.length) {
            PathNode[] nh = new PathNode[heap.length * 2];
            System.arraycopy(heap, 0, nh, 0, heapSize);
            heap = nh;
        }
        heap[heapSize] = n;
        n.heapIndex = heapSize;
        up(heapSize++);
    }

    private PathNode pop() {
        PathNode top = heap[0];
        heapSize--;
        if (heapSize > 0) {
            heap[0] = heap[heapSize];
            heap[0].heapIndex = 0;
            down(0);
        }
        heap[heapSize] = null;
        top.heapIndex = -1;
        return top;
    }

    private void up(int i) {
        PathNode n = heap[i];
        while (i > 0) {
            int p = (i - 1) >> 1;
            if (heap[p].f <= n.f) break;
            heap[i] = heap[p]; heap[i].heapIndex = i;
            i = p;
        }
        heap[i] = n; n.heapIndex = i;
    }

    private void down(int i) {
        PathNode n = heap[i];
        int half = heapSize >> 1;
        while (i < half) {
            int c = 2 * i + 1;
            if (c + 1 < heapSize && heap[c + 1].f < heap[c].f) c++;
            if (heap[c].f >= n.f) break;
            heap[i] = heap[c]; heap[i].heapIndex = i;
            i = c;
        }
        heap[i] = n; n.heapIndex = i;
    }
}
