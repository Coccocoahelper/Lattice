package net.h2020.bots.ai.combat;

import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.motor.BlockBreaker;
import net.h2020.bots.ai.motor.BlockPlacer;
import net.h2020.bots.ai.motor.Motor;
import net.h2020.bots.ai.world.Shape;
import net.h2020.bots.ai.world.WorldView;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Breaking a bed in the middle of a fight, when the defender is busy with somebody else (or out of reach for a couple
 * of seconds). Runs inside Combat: it aims at the bed with a real tool, digs both halves like a player and, if the
 * defender starts coming back, closes the two cells beside the tunnel mouth first so he cannot reach around the hole
 * (the same "block in" a person does when he commits to a bed).
 * <p>
 * It never starts while we are taking hits, below the bed, without a tool for the material in the way, or when the bed
 * is more than five blocks away: those are the cases where running at the bed is just feeding a kill.
 */
final class BedRaid {
    private final BotContext ctx;
    private final Style style;
    private final BlockBreaker breaker;
    private final BlockPlacer placer;

    private boolean active;
    private Location bed;
    private int[][] seal;
    private int sealIdx = -1;
    private long lastSeal = -10000;
    private Location target;
    private long started;
    private long cooldownUntil;
    private int flanks;
    private long lastFlank;

    BedRaid(BotContext ctx, Style style) {
        this.ctx = ctx;
        this.style = style;
        this.breaker = new BlockBreaker(ctx);
        this.placer = new BlockPlacer(ctx);
    }

    boolean active() { return active; }

    void cancel() {
        active = false;
        breaker.cancel();
        placer.cancel();
    }

    /** The bed this raid is working on (null when idle). */
    Location bed() { return bed; }

    /**
     * Try to start a raid on `bedFoot`.
     * @param sinceHurt ticks since we last took a hit
     * @param nearestEnemy distance to the closest known enemy (99 if none)
     */
    boolean consider(Location bedFoot, long sinceHurt, double nearestEnemy) {
        if (active || !CombatTuning.bedRaid || bedFoot == null) return false;
        if (ctx.tick < cooldownUntil) return false;
        if (!style.bedRaider) return false;
        if (!Inv.has(ctx.player, Inv.BLOCK) || !toolOk(bedFoot, 2)) return false;
        Location eye = ctx.eye();
        if (eye.getWorld() != bedFoot.getWorld()) return false;
        if (eye.distance(bedFoot.clone().add(0.5, 0.3, 0.5)) > 5.0) return false;
        // only while the defender has his hands full: no fresh hits on us, or he is far
        if (sinceHurt < 14 && nearestEnemy < 4.5) return false;
        bed = bedFoot.clone();
        active = true;
        started = ctx.tick;
        flanks = 0;
        lastFlank = -100L;
        // bed block-off: put a wall between the defender and the bed before digging, so he cannot step in behind us
        target = bed.clone();                        // so tick() never runs with no dig target while the wall goes up
        seal = sealPlan(bed, nearestEnemy);
        sealIdx = seal == null ? -1 : 0;
        if (sealIdx >= 0) {
            placer.setNeedSight(true);
            placer.start(seal[0][0], seal[0][1], seal[0][2], Inv.BLOCK);
            CombatStats.inc("bed.raid.seal");
        } else {
            startHalf();
        }
        CombatStats.inc("bed.raid");
        return true;
    }

    /**
     * The block-off wall: three cells across the defender's path to the bed, one and a half blocks out from the bed
     * centre. It is the guide's "bed blockoff" - the defender is walled away from his own bed while it is broken, which
     * is what stops him stepping in behind us and comboing us off the hole.
     */
    private int[][] sealPlan(Location bedFoot, double nearestEnemy) {
        if (nearestEnemy > 8.0) { CombatStats.inc("bed.raid.seal.far"); return null; }
        if (ctx.tick - lastSeal < 240) return null;
        if (Inv.count(ctx.player, Inv.BLOCK) < 5) { CombatStats.inc("bed.raid.seal.noblocks"); return null; }
        Location c = net.h2020.bots.ai.combat.BedCover.centre(bedFoot);
        Player foe = null;
        double bd = 9.0;
        for (Player p : ctx.player.getWorld().getPlayers()) {
            if (p == ctx.player || p.isDead() || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            if (sameTeam(ctx.player, p)) continue;
            double dd = p.getLocation().distance(c);
            if (dd < bd) { bd = dd; foe = p; }
        }
        if (foe == null) { CombatStats.inc("bed.raid.seal.nofoe"); return null; }
        double dx = foe.getLocation().getX() - c.getX(), dz = foe.getLocation().getZ() - c.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-3) return null;
        int ax = (int) Math.floor(c.getX() + dx / len * 1.5), az = (int) Math.floor(c.getZ() + dz / len * 1.5);
        int ay = (int) Math.floor(c.getY());
        int px = (int) Math.round(-dz / len), pz = (int) Math.round(dx / len);
        WorldView wv = ctx.world();
        java.util.List<int[]> cells = new java.util.ArrayList<int[]>();
        for (int off : new int[]{-1, 0, 1}) {
            int x = ax + px * off, z = az + pz * off;
            if (wv.type(x, ay, z) == Material.BED_BLOCK) continue;
            if (!wv.passable(x, ay, z) || !wv.passable(x, ay + 1, z)) continue;
            if (!net.h2020.bots.ai.world.Shape.floor(wv.shape(x, ay - 1, z))) continue;
            cells.add(new int[]{x, ay, z});
        }
        if (cells.size() < 2) { CombatStats.inc("bed.raid.seal.nocells"); return null; }
        lastSeal = ctx.tick;
        return cells.toArray(new int[cells.size()][]);
    }

    private boolean sameTeam(Player a, Player b) {
        net.h2020.api.game.GameManager gm = net.h2020.api.H2020.find(net.h2020.api.game.GameManager.class);
        net.h2020.api.game.Game g = gm == null ? null : gm.gameOf(a);
        if (g == null) return false;
        net.h2020.api.game.GameTeam ta = g.teamOf(a), tb = g.teamOf(b);
        return ta != null && ta == tb;
    }

    /** Pick the closer half of the bed and start digging it. */
    private void startHalf() {
        int[] head = net.h2020.bots.ai.brain.bedwars.BedDefense.head(bed.getWorld(), bed);
        Location eye = ctx.eye();
        double df = eye.distanceSquared(bed.clone().add(0.5, 0.3, 0.5));
        double dh = eye.distanceSquared(new Location(bed.getWorld(), head[0] + 0.5, head[1] + 0.3, head[2] + 0.5));
        if (dh < df) target = new Location(bed.getWorld(), head[0], head[1], head[2]);
        else target = bed.clone();
        breaker.start(target.getBlockX(), target.getBlockY(), target.getBlockZ(), true);
    }

    /** @return true while the raid drives the inputs. */
    boolean tick(long sinceHurt, double enemyDist) {
        if (!active) return false;
        if (ctx.tick - started > 20 * 30) { abort(false); return false; }
        WorldView wv = ctx.world();
        Material m = wv.type(bed.getBlockX(), bed.getBlockY(), bed.getBlockZ());
        boolean alive = m == Material.BED_BLOCK;
        if (!alive) {
            CombatStats.inc("bed.raid.ok");
            cooldownUntil = ctx.tick + 200;
            cancel();
            return false;
        }
        // still laying the block-off wall: the bed waits
        if (sealIdx >= 0) {
            BlockPlacer.Result r = placer.tick();
            if (r == BlockPlacer.Result.RUNNING) return true;
            if (r == BlockPlacer.Result.DONE) {
                sealIdx++;
                if (sealIdx < seal.length) {
                    placer.start(seal[sealIdx][0], seal[sealIdx][1], seal[sealIdx][2], Inv.BLOCK);
                    return true;
                }
                CombatStats.inc("bed.raid.seal.ok");
                sealIdx = -1;
                startHalf();
                return true;
            }
            // that cell would not go down: try the next one before giving up on the wall
            sealIdx++;
            if (sealIdx < seal.length) {
                placer.start(seal[sealIdx][0], seal[sealIdx][1], seal[sealIdx][2], Inv.BLOCK);
                return true;
            }
            sealIdx = -1;
            startHalf();
        }
        // he came back for us: plant a block beside the hole, then fight (the brain takes over from here)
        if (sinceHurt < 3 && enemyDist < 3.2) { abort(false); return false; }
        if (enemyDist < 7 && flanks < 2 && ctx.tick - lastFlank > 12 && Inv.has(ctx.player, Inv.BLOCK) && !placer.active()) {
            int[] c = BedCover.sideCell(ctx, bed, ctx.pos());
            if (c != null) {
                placer.setNeedSight(true);
                placer.start(c[0], c[1], c[2], Inv.BLOCK);
                flanks++;
                lastFlank = ctx.tick;
                CombatStats.inc("bed.raid.cover");
            }
        }
        if (placer.active()) {
            BlockPlacer.Result r = placer.tick();
            if (r == BlockPlacer.Result.RUNNING) return true;
        }
        if (!breaker.active()) {
            // the half we dug out is gone: go for the other one, or dig whatever is in the way
            if (target != null && (wv.type(target.getBlockX(), target.getBlockY(), target.getBlockZ()) == Material.AIR
                    || Shape.passable(wv.shape(target.getBlockX(), target.getBlockY(), target.getBlockZ())))) {
                int[] head = net.h2020.bots.ai.brain.bedwars.BedDefense.head(bed.getWorld(), bed);
                Location other = target.getBlockX() == bed.getBlockX() && target.getBlockZ() == bed.getBlockZ()
                        ? new Location(bed.getWorld(), head[0], head[1], head[2])
                        : bed.clone();
                target = other;
                breaker.start(other.getBlockX(), other.getBlockY(), other.getBlockZ(), true);
            } else {
                breaker.start(target.getBlockX(), target.getBlockY(), target.getBlockZ(), true);
            }
        }
        BlockBreaker.Result r = breaker.tick();
        if (r == BlockBreaker.Result.FAILED) { abort(false); return false; }
        // mining a bed is not a freeze: keep moving, and above all keep off the line the defender is swinging down.
        // The crosshair is re-aimed every tick, so the dig continues while the body slides sideways or backs out.
        moveWhileMining(bed);
        ctx.motor.aimPoint(Motor.P_TASK, target.clone().add(0.5, 0.5, 0.5), net.h2020.api.bot.AimIntent.PRECISE);
        // the eye stays on the bed; no sword-swapping while digging
        return r == BlockBreaker.Result.RUNNING || breaker.active();
    }

    /** Nearest player who is not on our team, within `r`. */
    private org.bukkit.entity.Player nearestThreat(double r) {
        net.h2020.api.game.GameManager gm = net.h2020.api.H2020.find(net.h2020.api.game.GameManager.class);
        net.h2020.api.game.Game g = gm == null ? null : gm.gameOf(ctx.player);
        net.h2020.api.game.GameTeam mine = g == null ? null : g.teamOf(ctx.player);
        org.bukkit.entity.Player best = null;
        double bd = r;
        for (org.bukkit.entity.Player p : ctx.player.getWorld().getPlayers()) {
            if (p == ctx.player || p.isDead()) continue;
            if (g != null && mine != null && g.teamOf(p) == mine) continue;
            double d = ctx.pos().distance(p.getLocation());
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    /** Strafe around the dig when somebody is close, or step away if they are on top of us. */
    private void moveWhileMining(Location bed) {
        org.bukkit.entity.Player threat = nearestThreat(6.0);
        if (threat == null) {
            // nobody near: a slow sideways shuffle so the body is not a statue on the bed
            if (ctx.tick % 12 == 0) {
                double a = ctx.tick * 0.35;
                ctx.motor.moveToward(Math.cos(a) * 0.6, Math.sin(a) * 0.6);
            }
            return;
        }
        Location me = ctx.pos(), e = threat.getLocation();
        double tx = target.getX() + 0.5, tz = target.getZ() + 0.5;
        double toBlock = Math.hypot(tx - me.getX(), tz - me.getZ());
        double ex = me.getX() - e.getX(), ez = me.getZ() - e.getZ();
        double el = Math.max(1e-3, Math.hypot(ex, ez));
        if (toBlock > 3.4) {
            // drifted out of reach of the bed: the dig matters more than the dodge
            ctx.motor.moveToward(tx - me.getX(), tz - me.getZ());
            net.h2020.api.stats.CombatStats.inc("bed.raid.reach");
            return;
        }
        if (el < 2.4) {
            // they are on top of us: back out of their swing, still facing the bed
            ctx.motor.moveToward(ex / el, ez / el);
            net.h2020.api.stats.CombatStats.inc("bed.raid.back");
            return;
        }
        // circle the dig, side chosen to stay inside the bed's reach
        double px = -ez / el, pz = ex / el;
        if (Math.hypot(tx - (me.getX() + px), tz - (me.getZ() + pz)) > 3.6) { px = -px; pz = -pz; }
        ctx.motor.moveToward(px, pz);
        net.h2020.api.stats.CombatStats.inc("bed.raid.strafe");
    }

    private void abort(boolean done) {
        breaker.cancel();
        placer.cancel();
        active = false;
        cooldownUntil = ctx.tick + (done ? 60 : 100);
    }

    /**
     * Do we have a tool that digs the material around this bed? Wool needs shears, end stone / clay / obsidian a
     * pickaxe, wood an axe; with none of them the defense would take ten times as long, so no raid.
     */
    private boolean toolOk(Location bedFoot, int radius) {
        WorldView wv = ctx.world();
        int bx = bedFoot.getBlockX(), by = bedFoot.getBlockY(), bz = bedFoot.getBlockZ();
        boolean wool = false, hard = false, wood = false;
        for (int dx = -radius; dx <= radius; dx++) for (int dy = 0; dy <= radius; dy++) for (int dz = -radius; dz <= radius; dz++) {
            Material m = wv.type(bx + dx, by + dy, bz + dz);
            if (m == Material.WOOL) wool = true;
            else if (m == Material.ENDER_STONE || m == Material.STAINED_CLAY || m == Material.HARD_CLAY
                    || m == Material.OBSIDIAN || m == Material.SANDSTONE || m == Material.STONE) hard = true;
            else if (m == Material.WOOD || m == Material.LOG || m == Material.LOG_2) wood = true;
        }
        if (wool && Inv.has(ctx.player, Inv.SHEARS)) return true;
        if (hard && Inv.has(ctx.player, Inv.PICKAXE)) return true;
        if (wood && Inv.has(ctx.player, Inv.AXE)) return true;
        return !wool && !hard && !wood;
    }

    private static int floor(double d) { int i = (int) d; return d < i ? i - 1 : i; }
}
