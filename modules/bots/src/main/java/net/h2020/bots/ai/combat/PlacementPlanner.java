package net.h2020.bots.ai.combat;

import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.brain.BedwarsBrain;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.motor.BlockPlacer;
import net.h2020.bots.ai.world.Shape;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Why a block goes down, before which one goes down.
 * <p>
 * The mechanics (wall, cut, pillar, stomp) and the bandit over them already answer "what can I place". This answers the
 * question above it: is the block sealing a hole in our own floor, denying the square an enemy is about to land on, or
 * buying a breath while we run. A placement with no purpose is a block wasted, and on a 1.8 generator that matters.
 */
public final class PlacementPlanner {
    public static final int NONE = 0, SEAL = 1, DENY_LANDING = 2, ESCAPE = 3, HEIGHT = 4;

    private final BotContext ctx;
    private final BedwarsBrain brain;
    private final BlockPlacer placer;
    private long nextTry;
    private int purpose;

    public PlacementPlanner(BedwarsBrain brain) {
        this.brain = brain;
        this.ctx = brain.context();
        this.placer = new BlockPlacer(ctx);
    }

    public boolean active() { return placer.active(); }
    public int purpose() { return purpose; }

    /** Run one strategic placement decision. Returns true when it owns this tick. */
    public boolean tick() {
        if (placer.active()) {
            BlockPlacer.Result r = placer.tick();
            if (r != BlockPlacer.Result.RUNNING) {
                CombatStats.inc("place.purpose." + name(purpose) + (r == BlockPlacer.Result.DONE ? ".ok" : ".fail"));
                purpose = NONE;
            }
            return true;
        }
        if (ctx.tick < nextTry || !Inv.has(ctx.player, material())) return false;
        Player enemy = brain.nearestEnemyPlayer(14);
        if (enemy == null) return false;

        // 1. seal: our own feet-level ground has a hole within three blocks and somebody is close enough to use it
        Location hole = holeNear(3);
        if (hole != null) { start(hole, SEAL); return true; }

        // 2. deny the landing: they are bridging at us and the square they would step onto is open
        if (enemyNearBridge(enemy)) {
            Location landing = landingSquare(enemy);
            if (landing != null) { start(landing, DENY_LANDING); return true; }
        }

        // 3. escape: hurt, cornered, and a block behind us breaks the chase
        if (ctx.health() <= 12 && ctx.pos().distance(enemy.getLocation()) < 6) {
            Location behind = behindMe(enemy);
            if (behind != null) { start(behind, ESCAPE); return true; }
        }
        return false;
    }

    private void start(Location at, int p) {
        // walk to a stand beside the cell first: the placer's ray needs a body position that can see the face, and
        // placing from wherever we happen to stand is why every attempt failed
        Location me = ctx.pos();
        if (me.distance(at) > 2.0) {
            Location stand = standNear(at);
            if (stand != null) {
                net.h2020.bots.ai.nav.NavOptions o = net.h2020.bots.ai.nav.NavOptions.walk();
                ctx.nav.goTo(stand, 0.5, o);
                ctx.nav.tick();
                purpose = p;
                net.h2020.api.stats.CombatStats.inc("place.purpose." + name(p) + ".walk");
                return;
            }
        }
        purpose = p;
        placer.setFast(true);
        placer.setNeedSight(true);
        placer.start(at.getBlockX(), at.getBlockY(), at.getBlockZ(), material());
        CombatStats.inc("place.purpose." + name(p) + ".start");
        nextTry = ctx.tick + 25;
    }

    /** A passable cell at our feet level, within `r`, with solid ground beside it (a hole worth filling). */
    private Location holeNear(int r) {
        Location me = ctx.pos();
        int y = me.getBlockY();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > r) continue;
                int x = me.getBlockX() + dx, z = me.getBlockZ() + dz;
                if (ctx.world().shape(x, y, z) != Shape.OPEN) continue;
                if (ctx.world().shape(x, y - 1, z) != Shape.OPEN) continue;      // already a floor: not a hole
                if (ctx.world().shape(x, y + 1, z) != Shape.OPEN) continue;      // something above: not walkable
                // only a hole on ground we own: next to solid floor and no void underneath, else it is the void
                if (ctx.world().voidSides(x, y, z) >= 3) continue;
                // it must have a face to click: a hole floating in air cannot be reached by the placer's ray
                boolean face = ctx.world().shape(x + 1, y, z) != Shape.OPEN
                        || ctx.world().shape(x - 1, y, z) != Shape.OPEN
                        || ctx.world().shape(x, y, z + 1) != Shape.OPEN
                        || ctx.world().shape(x, y, z - 1) != Shape.OPEN
                        || ctx.world().shape(x, y - 1, z) != Shape.OPEN;
                if (!face) continue;
                if (Math.hypot(dx, dz) > 2.6) continue;                 // reachable without a long walk
                return new Location(me.getWorld(), x, y, z);
            }
        }
        return null;
    }

    /** They are within reach-ish and off the ground floor (bridging or standing on a raised path). */
    private boolean enemyNearBridge(Player enemy) {
        Location me = ctx.pos(), e = enemy.getLocation();
        if (Math.abs(e.getY() - me.getY()) > 2.5) return false;
        return me.distance(e) <= 10 && !enemy.isOnGround();
    }

    /** The open square between us and them at our level: the one they would step onto. */
    private Location landingSquare(Player enemy) {
        Location me = ctx.pos(), e = enemy.getLocation();
        double dx = e.getX() - me.getX(), dz = e.getZ() - me.getZ();
        double l = Math.max(1e-3, Math.hypot(dx, dz));
        for (double step = 2.0; step <= 3.5; step += 0.5) {
            int x = (int) Math.floor(me.getX() + dx / l * step), z = (int) Math.floor(me.getZ() + dz / l * step);
            int y = me.getBlockY();
            if (ctx.world().shape(x, y, z) == Shape.OPEN && ctx.world().shape(x, y - 1, z) == Shape.OPEN
                    && ctx.world().shape(x, y + 1, z) == Shape.OPEN && ctx.world().voidSides(x, y, z) <= 2) {
                return new Location(me.getWorld(), x, y, z);
            }
        }
        return null;
    }

    /** A block placed behind us, between us and the void, to stop the chase for a tick. */
    private Location behindMe(Player enemy) {
        Location me = ctx.pos();
        double dx = me.getX() - enemy.getLocation().getX(), dz = me.getZ() - enemy.getLocation().getZ();
        double l = Math.max(1e-3, Math.hypot(dx, dz));
        int x = (int) Math.floor(me.getX() + dx / l), z = (int) Math.floor(me.getZ() + dz / l);
        int y = me.getBlockY();
        if (ctx.world().shape(x, y, z) == Shape.OPEN && ctx.world().shape(x, y + 1, z) == Shape.OPEN) {
            return new Location(me.getWorld(), x, y, z);
        }
        return null;
    }

    /** Is there ground behind us, away from the enemy, that we could still retreat to? */
    private boolean exitOpen() {
        Location me = ctx.pos();
        Player enemy = brain.nearestEnemyPlayer(14);
        double dx = 1, dz = 0;
        if (enemy != null) {
            dx = me.getX() - enemy.getLocation().getX();
            dz = me.getZ() - enemy.getLocation().getZ();
            double l = Math.max(1e-3, Math.hypot(dx, dz));
            dx /= l; dz /= l;
        }
        int x = (int) Math.floor(me.getX() + dx), z = (int) Math.floor(me.getZ() + dz);
        int y = me.getBlockY();
        return ctx.world().standable(x, y - 1, z) || ctx.world().standable(x, y, z);
    }

    /** A standable cell next to the target, so the placer has a line to a face. */
    private Location standNear(Location at) {
        int x = at.getBlockX(), y = at.getBlockY(), z = at.getBlockZ();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            int sx = x + d[0], sz = z + d[1];
            if (ctx.world().shape(sx, y, sz) == Shape.OPEN && ctx.world().shape(sx, y + 1, sz) == Shape.OPEN
                    && ctx.world().shape(sx, y - 1, sz) != Shape.OPEN) {
                return new Location(ctx.pos().getWorld(), sx + 0.5, y, sz + 0.5);
            }
        }
        return null;
    }

    private Inv.Pred material() {
        if (Inv.has(ctx.player, Inv.type(Material.WOOL))) return Inv.type(Material.WOOL);
        if (Inv.has(ctx.player, Inv.type(Material.ENDER_STONE))) return Inv.type(Material.ENDER_STONE);
        return Inv.BLOCK;
    }

    public static String name(int p) {
        switch (p) {
            case SEAL: return "seal";
            case DENY_LANDING: return "landing";
            case ESCAPE: return "escape";
            case HEIGHT: return "height";
            default: return "none";
        }
    }
}
