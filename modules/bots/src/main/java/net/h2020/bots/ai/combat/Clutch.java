package net.h2020.bots.ai.combat;

import net.h2020.api.stats.CombatStats;
import net.h2020.api.bot.AimIntent;
import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.motor.BlockPlacer;
import net.h2020.bots.ai.motor.Motor;
import net.h2020.bots.ai.util.AIMath;
import net.h2020.bots.ai.world.Shape;
import net.h2020.bots.ai.world.WorldView;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;

/**
 * Saving yourself when you go over an edge: a block clutch (flick down-back at the side of the edge you fell from
 * and right-click, chaining a block or two outward if the knockback carried you further), a water-bucket clutch
 * before a big fall onto ground, or an ender pearl back to land when you're already falling into the void.
 * Everything is real crosshair work: the click only happens when the client's own ray hits the support face, so a
 * late reaction or a sloppy flick simply misses, like it does for people. Runs before the brain each tick and takes
 * over while it acts. Also records every void fall for /bot pvp stats (in v1 too, to compare).
 */
final class Clutch {
    private final BotContext ctx;
    private final Style style;

    // last time we stood on something
    private double groundX, groundY = Double.NaN, groundZ;
    private long groundTick;
    private long lastKnock = -1000;

    // current fall
    private boolean voidFall;     // this fall is over the void (counted)
    private long fallStart;
    private int mode;             // 0 none, 1 block, 2 water, 3 pearl
    private boolean decided;
    private long actAt;
    private int placed, clicksThisFall;
    private int clickWait;
    private Block support;
    private BlockFace face;
    private Location aimPt;
    private long pickTick;
    private Location pearlAim;
    private long lastAnticipate = -1000;
    private float lastYaw, lastPitch;
    private int ccx, ccy, ccz;
    private long clickCheckAt = -1;
    private int tx, ty, tz;
    private boolean pearlThrown;
    private long waterPlacedAt = -1;
    private boolean waterPending;
    private long pickupUntil = -1;
    private int prevSlot = -1;
    private boolean triedBlock, triedPearl;

    Clutch(BotContext ctx, Style style) { this.ctx = ctx; this.style = style; }

    void onKnock() { lastKnock = ctx.tick; }

    boolean active() { return mode != 0 || pickupUntil > ctx.tick; }

    /** Called when the bot died. */
    void onDeath(boolean voided) {
        if (voidFall) CombatStats.log(name() + " died mode=" + mode + " placed=" + placed + " t=" + (ctx.tick - fallStart));
        if (voidFall) {
            CombatStats.inc("fall.died");
            if (triedBlock) CombatStats.inc("clutch.block.try");
            if (triedPearl) CombatStats.inc("clutch.pearl.try");
        }
        reset();
    }

    private void reset() {
        voidFall = false; mode = 0; decided = false; placed = 0; clicksThisFall = 0; support = null; pearlThrown = false;
        triedBlock = false; triedPearl = false; waterPending = false;
    }

    /** @return true when the clutch drives the inputs this tick. */
    boolean tick() {
        Location p = ctx.pos();
        WorldView wv = ctx.world();
        boolean ground = ctx.c.onGround();
        boolean liquid = ctx.inLiquid();
        if (ctx.c.tookKnockback()) lastKnock = ctx.tick;

        // water pick-up after a bucket clutch
        if (pickupUntil > ctx.tick) {
            if (waterPending) return pickUpWater(p);
        } else if (waterPending) waterPending = false;

        // good players see it coming: a hit that sends them toward a nearby edge already starts the turn
        if (ground && CombatStats.v2 && mode == 0 && ctx.c.tookKnockback() && style.clutch > 0.45 && ctx.tick - lastAnticipate > 60
                && kbTowardVoid(p, wv)
                && Inv.findHotbar(ctx.player, Inv.ANY_BLOCK) >= 0) {
            groundX = p.getX(); groundY = p.getY(); groundZ = p.getZ(); groundTick = ctx.tick;
            decided = false;
            lastAnticipate = ctx.tick;
            decide(true);
            if (mode == 1) { CombatStats.inc("clutch.anticipate"); return ctx.tick < actAt || blockClutch(p, wv); }
        }
        if (ground && mode == 1 && ctx.tick - fallStart < 3) return ctx.tick < actAt || blockClutch(p, wv); // still taking off
        if (ground || liquid) {
            if (voidFall || mode != 0) landed(p, liquid);
            if (ground) { groundX = p.getX(); groundY = p.getY(); groundZ = p.getZ(); groundTick = ctx.tick; }
            if (mode == 2 && liquid) { waterPending = true; pickupUntil = ctx.tick + 12; }
            mode = 0; decided = false;
            return waterPending && pickUpWater(p);
        }
        if (Double.isNaN(groundY) || ctx.player.isFlying() || ctx.player.isInsideVehicle()) return false;
        int fx = floor(p.getX()), fz = floor(p.getZ()), fy = floor(p.getY());
        double below = groundY - p.getY();
        boolean overVoid = wv.voidBelow(fx, fy + 1, fz, 60);

        if (!voidFall && overVoid && below > 0.6) {
            voidFall = true;
            fallStart = ctx.tick;
            CombatStats.inc("fall.void");
            if (ctx.tick - lastKnock < 30) CombatStats.inc("fall.void.knocked");
        }
        if (!CombatStats.v2) return false;

        if (!decided) {
            // decide (once) what to do, after a human reaction
            boolean knocked = ctx.tick - lastKnock < 20;
            if (overVoid && (below > 0.25 || knocked)) decide(true);
            else if (!overVoid && ctx.c.velocity().getY() < -0.5) {
                int land = wv.landing(fx, fy, fz, 40);
                if (land != Integer.MIN_VALUE && groundY - land > 7 && wv.shape(fx, land, fz) != Shape.WATER && style.waterClutch && Inv.findHotbar(ctx.player, Inv.type(Material.WATER_BUCKET)) >= 0
                        && ctx.rng.nextDouble() < 0.4 + 0.6 * style.clutch) {
                    mode = 2; decided = true; actAt = ctx.tick + style.clutchDelay;
                    prevSlot = ctx.c.heldSlot();
                    CombatStats.inc("clutch.water.try");
                }
            }
        }
        if (mode == 0 || ctx.tick < actAt) return mode != 0;
        switch (mode) {
            case 1: return blockClutch(p, wv);
            case 2: return waterClutch(p, wv);
            case 3: return pearlSave(p, wv);
            default: return false;
        }
    }

    private void decide(boolean overVoid) {
        decided = true;
        boolean blocks = Inv.findHotbar(ctx.player, Inv.ANY_BLOCK) >= 0;
        if (!blocks) {
            // the block is in the inventory, not in the hand: swap it in (the click lands a ping later, but a fall
            // with forty blocks in the bag and none in the hotbar is the most common way a bot dies to the void)
            if (Inv.has(ctx.player, Inv.ANY_BLOCK)) {
                Inv.equip(ctx.player, ctx.c, Inv.ANY_BLOCK, 1);
                CombatStats.inc("clutch.swap");
                blocks = true;
            } else {
                CombatStats.inc("clutch.noblock");
            }
        }
        boolean pearl = style.pearlSave && Inv.findHotbar(ctx.player, Inv.PEARL) >= 0;
        // a nervous beginner just falls; better players nearly always try
        if (blocks && style.clutch > 0 && ctx.rng.nextDouble() < 0.3 + 0.7 * style.clutch) {
            mode = 1;
            triedBlock = true;
            ctx.nav.stop();
        } else if (pearl) {
            mode = 3;
            triedPearl = true;
            ctx.nav.stop();
        } else { CombatStats.log(name() + " no clutch (blocks=" + blocks + " skill=" + fmt(style.clutch) + ")"); return; }
        CombatStats.log(name() + " clutch " + (mode == 1 ? "block" : "pearl") + " delay=" + style.clutchDelay + " " + where());
        prevSlot = ctx.c.heldSlot();
        // once the body is actually falling there is no time for a human reaction pause: the window is ~3 ticks
        actAt = ctx.tick + (ctx.c.velocity().getY() < -0.2 ? 0 : style.clutchDelay);
        placed = 0; clicksThisFall = 0; support = null; pearlAim = null; aimPt = null; pickTick = -100;
        if (!voidFall) fallStart = ctx.tick;
    }

    /** Degrees between the crosshair and the direction to a point. */
    double aimErr(Location pt) {
        Location eye = ctx.eye();
        float y = net.h2020.bots.ai.util.AIMath.yawTo(eye.getX(), eye.getZ(), pt.getX(), pt.getZ());
        float pi = net.h2020.bots.ai.util.AIMath.pitchTo(eye.getX(), eye.getY(), eye.getZ(), pt.getX(), pt.getY(), pt.getZ());
        double dy = net.h2020.bots.ai.util.AIMath.wrap(y - ctx.c.yaw()), dp = pi - ctx.c.pitch();
        return Math.sqrt(dy * dy + dp * dp);
    }

    private String name() { return ctx.player.getName(); }
    private static String fmt(double d) { return String.format("%.2f", d); }
    private String where() {
        Location p = ctx.pos();
        org.bukkit.util.Vector v = ctx.c.velocity();
        return "below=" + fmt(groundY - p.getY()) + " v=" + fmt(v.getX()) + "," + fmt(v.getY()) + "," + fmt(v.getZ())
                + " yaw=" + (int) ctx.c.yaw() + " pitch=" + (int) ctx.c.pitch() + " aimErr=" + (aimPt == null ? "-" : fmt(aimErr(aimPt)))
                + " held=" + ctx.c.heldSlot() + " react=" + (int) ((net.h2020.bots.client.VirtualClient) ctx.c).mouse.reactionRemainingMs();
    }

    private void landed(Location p, boolean liquid) {
        if (voidFall || mode != 0) CombatStats.log(name() + " landed mode=" + mode + " placed=" + placed + " voidFall=" + voidFall + " t=" + (ctx.tick - fallStart));
        WorldView wv = ctx.world();
        int fx = floor(p.getX()), fz = floor(p.getZ());
        boolean safe = !wv.voidBelow(fx, floor(p.getY()) + 1, fz, 60) || liquid;
        // a block that caught us before we dropped below the edge still saved a fall into the void
        if (!voidFall && placed > 0 && safe && wv.voidBelow(fx, floor(p.getY()) - 1, fz, 60)) {
            voidFall = true;
            CombatStats.inc("fall.void");
            if (ctx.tick - lastKnock < 30) CombatStats.inc("fall.void.knocked");
        }
        if (voidFall) {
            if (safe) CombatStats.inc("fall.saved");
            if (triedBlock) { CombatStats.inc("clutch.block.try"); if (safe && placed > 0) CombatStats.inc("clutch.block.ok"); }
            if (triedPearl) { CombatStats.inc("clutch.pearl.try"); if (safe && pearlThrown) CombatStats.inc("clutch.pearl.ok"); }
        }
        if (mode == 2) {
            if (liquid) CombatStats.inc("clutch.water.ok");
        }
        if (mode == 1 || mode == 3) restoreSlot();
        voidFall = false;
        triedBlock = false; triedPearl = false; pearlThrown = false;
        placed = 0;
    }

    private void restoreSlot() {
        int s = Inv.bestMeleeHotbar(ctx.player);
        if (s >= 0 && ctx.combat.engaged()) ctx.c.selectHotbar(s);
        else if (prevSlot >= 0 && prevSlot < 9) ctx.c.selectHotbar(prevSlot);
    }

    // ------------------------------------------------------------------------------------------- block clutch

    private boolean blockClutch(Location p, WorldView wv) {
        if (groundY - p.getY() > 7 || ctx.tick - fallStart > 50) {
            // too far gone: last resort pearl
            if (!triedPearl && style.pearlSave && Inv.findHotbar(ctx.player, Inv.PEARL) >= 0) {
                mode = 3; triedPearl = true;
                return true;
            }
            mode = 0;
            return false;
        }
        int bs = Inv.bestBlockHotbar(ctx.player);
        if (bs < 0) bs = Inv.findHotbar(ctx.player, Inv.ANY_BLOCK);
        if (bs < 0) { mode = 0; return false; }
        if (ctx.c.heldSlot() != bs) { ctx.c.selectHotbar(bs); }
        // drift back toward where we stood (air control is weak, but it helps)
        double bx = groundX - p.getX(), bz = groundZ - p.getZ();
        if (bx * bx + bz * bz > 0.05) ctx.motor.moveToward(bx, bz);
        if (clickWait > 0) clickWait--;

        Location eye = ctx.eye();
        if (caught(p, wv)) {
            // a block is under our landing spot already: stop clicking, just ride it out
            support = null;
            return true;
        }
        // one steady aim target (every new target restarts the hand's reaction): the outer face of the edge we fell
        // from, and from there outward along the blocks we place (a chain) / one block down when we drop past it
        if (aimPt == null) {
            if (!edgeTarget(p, wv)) {
                if (ctx.tick - pickTick >= 1) pickCell(p, wv, eye);
                if (aimPt == null && ctx.tick % 4 == 0) CombatStats.log(name() + " no edge face ground=" + fmt(groundX) + "," + fmt(groundY) + "," + fmt(groundZ));
            }
        } else if (!Shape.passable(wv.shape(tx, ty, tz))) {
            // the target cell got its block: carry on outward from it
            int nx = tx + dirX, nz = tz + dirZ;
            if (Shape.passable(wv.shape(nx, ty, nz))) setTarget(tx, ty, tz, nx, ty, nz);
        } else if (p.getY() + Math.min(0, ctx.c.velocity().getY()) < ty + 1 - 0.02) {
            // dropped past it: the same column one block lower, if the island goes down that far
            if (Shape.floor(wv.shape(tx - dirX, ty - 1, tz - dirZ)) && Shape.passable(wv.shape(tx, ty - 1, tz)))
                setTarget(tx - dirX, ty - 1, tz - dirZ, tx, ty - 1, tz);
        }
        if (aimPt != null) ctx.motor.aimPoint(Motor.P_FLICK + 5, aimPt, AimIntent.FLICK, 0.6);
        Block tb = ctx.c.targetedBlock();
        BlockFace tf = ctx.c.targetedFace();
        // right-click whenever the crosshair is on a face that would put a block under us (players spam it while
        // sweeping down), not only on the exact face we went for
        // (the click resolves against next frame's crosshair: not while the hand is still flying through)
        double turn = Math.abs(net.h2020.bots.ai.util.AIMath.wrap(ctx.c.yaw() - lastYaw)) + Math.abs(ctx.c.pitch() - lastPitch);
        lastYaw = ctx.c.yaw(); lastPitch = ctx.c.pitch();
        boolean good = tb != null && tf != null && ctx.c.heldSlot() == bs && clickWait == 0 && turn < 30;
        int cx = 0, cy = 0, cz = 0;
        if (good) {
            cx = tb.getX() + tf.getModX(); cy = tb.getY() + tf.getModY(); cz = tb.getZ() + tf.getModZ();
            good = (cx == tx && cy == ty && cz == tz) || catches(p, wv, cx, cy, cz);
            // (the click goes out with next tick's movement: our feet must still be above the block then)
            good = good && Shape.passable(wv.shape(cx, cy, cz)) && p.getY() + Math.min(0, ctx.c.velocity().getY()) >= cy + 1 - 0.02
                    && BlockPlacer.cellFree(new Location(p.getWorld(), cx, cy, cz));
        }
        if (clickCheckAt == ctx.tick) {
            CombatStats.log(name() + " placed? " + ctx.player.getWorld().getBlockAt(ccx, ccy, ccz).getType() + " at " + ccx + "," + ccy + "," + ccz);
        }
        if (good) {
            ctx.c.rightClick();
            ccx = cx; ccy = cy; ccz = cz; clickCheckAt = ctx.tick + 2;
            CombatStats.log(name() + " CLICK on " + tb.getType() + " " + tf + " -> " + cx + "," + cy + "," + cz + " turn=" + fmt(turn)
                    + " dig=" + fmt(((net.h2020.bots.client.VirtualClient) ctx.c).breakProgress()) + " " + where());
            clicksThisFall++;
            placed++;
            CombatStats.inc("clutch.block.place");
            clickWait = 2;
            support = null;
        }
        return true;
    }

    /**
     * Choose the empty cell to place into: reachable support face visible from the eye, at or a little below the
     * floor we fell from, closest to where our feet will cross its top.
     */
    private void pickCell(Location p, WorldView wv, Location eye) {
        int floorY = floor(groundY - 0.01);
        // first: one block straight under the feet. If there is solid ground below us at all, this saves the fall
        // immediately and does not depend on finding the edge face we came off.
        int ux = floor(p.getX()), uz = floor(p.getZ());
        for (int dy = 0; dy <= 2; dy++) {
            int cell = floor(p.getY()) - dy;
            if (!Shape.floor(wv.shape(ux, cell - 1, uz))) continue;
            Object[] s0 = BlockPlacer.findSupport(wv, ux, cell, uz, eye, 4.4, true);
            if (s0 == null) continue;
            aimPt = (Location) s0[2];
            tx = ux; ty = cell; tz = uz;
            dirX = 0; dirZ = 0;
            support = (Block) s0[0];
            pickTick = ctx.tick;
            CombatStats.inc("clutch.below");
            return;
        }
        Vector v = ctx.c.velocity();
        Object[] best = null;
        double bestScore = Double.MAX_VALUE;
        int bx = 0, by = 0, bz = 0;
        for (int L = floorY; L >= floorY - 2; L--) {
            double top = L + 1;
            if (p.getY() < top - 0.05) continue; // already below it: can't land on it any more
            double[] cross = crossing(p, v, top);
            if (cross == null) continue;
            int cx0 = floor(cross[0]), cz0 = floor(cross[1]);
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
                int x = cx0 + dx, z = cz0 + dz;
                if (!Shape.passable(wv.shape(x, L, z)) || wv.shape(x, L, z) == Shape.WATER) continue;
                // the cell must not intersect our body right now
                double hx = Math.max(Math.abs(p.getX() - (x + 0.5)) - 0.5, 0), hz = Math.max(Math.abs(p.getZ() - (z + 0.5)) - 0.5, 0);
                boolean overlapsXZ = hx < 0.3 && hz < 0.3;
                if (overlapsXZ && p.getY() < top + 0.02) continue;
                Object[] s = BlockPlacer.findSupport(wv, x, L, z, eye, 4.4, true);
                if (s == null) continue;
                double ddx = x + 0.5 - cross[0], ddz = z + 0.5 - cross[1];
                double score = Math.sqrt(ddx * ddx + ddz * ddz) + 0.3 * (floorY - L) + (((BlockFace) s[1]) == BlockFace.UP ? 0.3 : 0);
                // stay near where the crosshair already is
                if (aimPt != null) score += 0.5 * Math.min(3, ((Location) s[2]).distance(aimPt));
                if (score < bestScore) { bestScore = score; best = s; bx = x; by = L; bz = z; }
            }
            if (best != null && bestScore < 1.0) break; // this level catches us
        }
        pickTick = ctx.tick;
        if (best == null) {
            if (support != null || ctx.tick % 4 == 0) CombatStats.log(name() + " no cell " + where());
            support = null;
            return;
        }
        Block nb = (Block) best[0];
        BlockFace nf = (BlockFace) best[1];
        if (support != null && nb.equals(support) && nf == face) return;
        support = nb; face = nf; tx = bx; ty = by; tz = bz; pickTick = ctx.tick;
        dirX = nf.getModX(); dirZ = nf.getModZ();
        CombatStats.log(name() + " cell " + bx + "," + by + "," + bz + " on " + nf + " score=" + fmt(bestScore) + " " + where());
        Location seen = (Location) best[2];
        // sloppier players land near the edge of the face, sometimes off it
        double slop = (1 - style.clutch) * 0.25;
        aimPt = seen.clone().add(face.getModX() != 0 ? 0 : ctx.rng.nextGaussian() * slop,
                face.getModY() != 0 ? 0 : ctx.rng.nextGaussian() * slop, face.getModZ() != 0 ? 0 : ctx.rng.nextGaussian() * slop);
    }

    /** The knockback we just got carries us over the void within ~2.5 blocks. */
    private boolean kbTowardVoid(Location p, WorldView wv) {
        Vector v = ctx.c.velocity();
        double h = Math.sqrt(v.getX() * v.getX() + v.getZ() * v.getZ());
        if (h < 0.3) return false;
        int y = floor(p.getY() + 0.01);
        for (double r = 0.6; r <= 1.81; r += 0.6) {
            int x = floor(p.getX() + v.getX() / h * r), z = floor(p.getZ() + v.getZ() / h * r);
            if (wv.passable(x, y - 1, z) && wv.voidBelow(x, y, z, 40)) return true;
            if (!wv.passable(x, y, z)) return false;
        }
        return false;
    }

    private int dirX, dirZ;

    /** Aim at support (sx,sy,sz)'s face toward cell (cx,cy,cz). */
    private void setTarget(int sx, int sy, int sz, int cx, int cy, int cz) {
        Location p = ctx.pos();
        support = p.getWorld().getBlockAt(sx, sy, sz);
        face = cx > sx ? BlockFace.EAST : cx < sx ? BlockFace.WEST : cz > sz ? BlockFace.SOUTH : cz < sz ? BlockFace.NORTH : BlockFace.UP;
        tx = cx; ty = cy; tz = cz;
        double slop = (1 - style.clutch) * 0.2;
        aimPt = new Location(p.getWorld(), (sx + cx) / 2.0 + 0.5 + (dirX == 0 ? ctx.rng.nextGaussian() * slop : 0),
                sy + 0.5 + ctx.rng.nextGaussian() * slop, (sz + cz) / 2.0 + 0.5 + (dirZ == 0 ? ctx.rng.nextGaussian() * slop : 0));
        pickTick = ctx.tick;
        CombatStats.log(name() + " target " + cx + "," + cy + "," + cz + " " + face + " " + where());
    }

    /** Target the outward face of the edge block we stood on (one below it if there is one and we have time). */
    private boolean edgeTarget(Location p, WorldView wv) {
        int fy = floor(groundY - 0.01);
        int gx = floor(groundX), gz = floor(groundZ);
        if (!Shape.floor(wv.shape(gx, fy, gz))) {
            boolean found = false;
            for (double ox = -0.3; ox <= 0.31 && !found; ox += 0.3) for (double oz = -0.3; oz <= 0.31 && !found; oz += 0.3) {
                int x = floor(groundX + ox), z = floor(groundZ + oz);
                if (Shape.floor(wv.shape(x, fy, z))) { gx = x; gz = z; found = true; }
            }
            if (!found) return false;
        }
        double dx = p.getX() - (gx + 0.5), dz = p.getZ() - (gz + 0.5);
        Vector v = ctx.c.velocity();
        if (dx * dx + dz * dz < 0.04) { dx = v.getX(); dz = v.getZ(); }
        if (dx * dx + dz * dz < 1e-6) return false;
        int sx = 0, sz = 0;
        if (Math.abs(dx) > Math.abs(dz)) sx = dx > 0 ? 1 : -1; else sz = dz > 0 ? 1 : -1;
        // walk out to the actual edge
        for (int i = 0; i < 3 && Shape.floor(wv.shape(gx + sx, fy, gz + sz)); i++) { gx += sx; gz += sz; }
        if (!Shape.passable(wv.shape(gx + sx, fy, gz + sz))) return false;
        dirX = sx; dirZ = sz;
        boolean lower = Shape.floor(wv.shape(gx, fy - 1, gz)) && Shape.passable(wv.shape(gx + sx, fy - 1, gz + sz));
        int y = lower && p.getY() < groundY + 0.6 ? fy - 1 : fy;
        setTarget(gx, y, gz, gx + sx, y, gz + sz);
        return true;
    }

    /** The outward face of the block we stood on (or of the one under it), facing where we are now. */
    private Location edgeFace(Location p, WorldView wv) {
        int fy = floor(groundY - 0.01);
        // the block we actually stood on: at an edge the body's centre is often already over the air
        int gx = floor(groundX), gz = floor(groundZ);
        if (!Shape.floor(wv.shape(gx, fy, gz))) {
            boolean found = false;
            for (double ox = -0.3; ox <= 0.31 && !found; ox += 0.3) for (double oz = -0.3; oz <= 0.31 && !found; oz += 0.3) {
                int x = floor(groundX + ox), z = floor(groundZ + oz);
                if (Shape.floor(wv.shape(x, fy, z))) { gx = x; gz = z; found = true; }
            }
            if (!found) return null;
        }
        double dx = p.getX() - (gx + 0.5), dz = p.getZ() - (gz + 0.5);
        if (dx * dx + dz * dz < 0.04) { org.bukkit.util.Vector v = ctx.c.velocity(); dx = v.getX(); dz = v.getZ(); }
        if (dx * dx + dz * dz < 1e-6) return null;
        int sx = 0, sz = 0;
        if (Math.abs(dx) > Math.abs(dz)) sx = dx > 0 ? 1 : -1; else sz = dz > 0 ? 1 : -1;
        int y = Shape.floor(wv.shape(gx, fy - 1, gz)) && p.getY() > groundY - 0.2 ? fy - 1 : fy;
        if (!Shape.floor(wv.shape(gx, y, gz))) return null;
        return new Location(p.getWorld(), gx + 0.5 + sx * 0.5, y + 0.5, gz + 0.5 + sz * 0.5);
    }

    /** A block in (x,y,z) would end up under our feet (or right next to the line we fall along). */
    private boolean catches(Location p, WorldView wv, int x, int y, int z) {
        int floorY = floor(groundY - 0.01);
        if (y > floorY || y < floorY - 2) return false;
        double[] cr = crossing(p, ctx.c.velocity(), y + 1);
        if (cr == null) return false;
        double hx = Math.max(Math.abs(cr[0] - (x + 0.5)) - 0.5, 0), hz = Math.max(Math.abs(cr[1] - (z + 0.5)) - 0.5, 0);
        // within our half-width (0.3) plus a little slack: a chain block next to the line still helps
        return hx < 0.45 && hz < 0.45;
    }

    /** Something solid is under the spot where our feet will come down (within the levels we clutch at). */
    private boolean caught(Location p, WorldView wv) {
        int floorY = floor(groundY - 0.01);
        Vector v = ctx.c.velocity();
        for (int L = floorY; L >= floorY - 2; L--) {
            if (p.getY() < L + 1 - 0.05) continue;
            double[] cr = crossing(p, v, L + 1);
            if (cr == null) continue;
            for (double ox = -0.29; ox <= 0.3; ox += 0.58) for (double oz = -0.29; oz <= 0.3; oz += 0.58)
                if (Shape.floor(wv.shape(floor(cr[0] + ox), L, floor(cr[1] + oz)))) return true;
        }
        return false;
    }

    /** Where (x,z) our feet cross height y on the current trajectory (1.8 air physics), or null. */
    private static double[] crossing(Location p, Vector v, double y) {
        double x = p.getX(), z = p.getZ(), py = p.getY(), vx = v.getX(), vy = v.getY(), vz = v.getZ();
        for (int t = 0; t < 40; t++) {
            double ny = py + vy;
            if (ny <= y) {
                double f = (py - y) / Math.max(1e-6, py - ny);
                return new double[]{x + vx * f, z + vz * f};
            }
            x += vx; z += vz; py = ny;
            vx *= 0.91; vz *= 0.91;
            vy = (vy - 0.08) * 0.98;
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------- water bucket

    private boolean waterClutch(Location p, WorldView wv) {
        int fx = floor(p.getX()), fz = floor(p.getZ());
        int land = wv.landing(fx, floor(p.getY()), fz, 40);
        if (land == Integer.MIN_VALUE) { mode = 0; return false; }
        int ws = Inv.findHotbar(ctx.player, Inv.type(Material.WATER_BUCKET));
        if (ws < 0) { mode = 0; return false; }
        if (ctx.c.heldSlot() != ws) ctx.c.selectHotbar(ws);
        ctx.motor.aimRotation(Motor.P_FLICK + 5, ctx.c.yaw(), 89.5f, AimIntent.FLICK, 2.0);
        double h = p.getY() - land;
        // place when the ground is within bucket reach but before we hit it (the water needs a tick to appear)
        if (h < 3.2 && h > 0.5 && ctx.c.pitch() > 70 && ctx.c.heldSlot() == ws && clickWait == 0) {
            ctx.c.rightClick();
            clickWait = 3;
            waterPlacedAt = ctx.tick;
        }
        if (clickWait > 0) clickWait--;
        return true;
    }

    private boolean pickUpWater(Location p) {
        int bucket = Inv.findHotbar(ctx.player, Inv.type(Material.BUCKET));
        if (bucket < 0) { waterPending = false; return false; }
        if (ctx.c.heldSlot() != bucket) ctx.c.selectHotbar(bucket);
        ctx.motor.aimRotation(Motor.P_FLICK + 5, ctx.c.yaw(), 89.5f, AimIntent.FLICK, 2.0);
        if (clickWait > 0) { clickWait--; return true; }
        if (ctx.c.pitch() > 70 && ctx.c.heldSlot() == bucket) {
            Material m = p.getBlock().getType();
            if (m == Material.WATER || m == Material.STATIONARY_WATER || p.clone().add(0, -1, 0).getBlock().getType() == Material.STATIONARY_WATER) {
                ctx.c.rightClick();
                clickWait = 4;
            } else if (ctx.tick - waterPlacedAt > 8) {
                waterPending = false;
                restoreSlot();
            }
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------- pearl

    private boolean pearlSave(Location p, WorldView wv) {
        if (pearlThrown) {
            // keep steering toward land while the pearl flies
            double bx = groundX - p.getX(), bz = groundZ - p.getZ();
            ctx.motor.moveToward(bx, bz);
            if (ctx.tick - fallStart > 120) mode = 0;
            return ctx.tick - fallStart < 120;
        }
        int ps = Inv.findHotbar(ctx.player, Inv.PEARL);
        if (ps < 0 || p.getY() < 2) { mode = 0; return false; }
        if (ctx.c.heldSlot() != ps) { ctx.c.selectHotbar(ps); return true; }
        Location tgt = landingSpot(p, wv);
        if (tgt == null) { mode = 0; return false; }
        if (pearlAim == null || ctx.tick % 3 == 0) pearlAim = Ballistics.aim(ctx.eye(), tgt, new Vector(), Ballistics.PEARL, 1, style.projErrDeg * 0.8, shot());
        Location aim = pearlAim;
        if (aim == null) { CombatStats.log(name() + " pearl: no arc to " + tgt.toVector()); mode = 0; return false; }
        ctx.motor.aimPoint(Motor.P_FLICK + 5, aim, AimIntent.FLICK, 0.5);
        double err = aimErr(aim);
        if (err < 2.5 || (ctx.c.aimSettled() && err < 6)) {
            CombatStats.log(name() + " PEARL err=" + fmt(err) + " " + where());
            ctx.c.rightClick();
            pearlThrown = true;
            CombatStats.inc("pearl.save");
        }
        return true;
    }

    /** A standable spot near where we fell from (or the nearest land toward it). */
    private Location landingSpot(Location p, WorldView wv) {
        int gx = floor(groundX), gz = floor(groundZ), gy = floor(groundY + 0.01);
        double dx = groundX - p.getX(), dz = groundZ - p.getZ();
        double len = Math.max(1e-3, Math.sqrt(dx * dx + dz * dz));
        // a bit past the edge we fell from, onto the island
        for (int extra = 2; extra >= 0; extra--) {
            int x = floor(groundX + dx / len * extra), z = floor(groundZ + dz / len * extra);
            int land = wv.landing(x, gy + 2, z, 8);
            if (land != Integer.MIN_VALUE) return new Location(p.getWorld(), x + 0.5, land + 0.1, z + 0.5);
        }
        int land = wv.landing(gx, gy + 1, gz, 6);
        return land == Integer.MIN_VALUE ? null : new Location(p.getWorld(), gx + 0.5, land + 0.1, gz + 0.5);
    }

    /** Error sample that stays the same for ~1.2 s (one throw / one draw), so the crosshair has a fixed place to go. */
    private final long shotSalt = new java.util.Random().nextLong();
    private java.util.Random shot() { return new java.util.Random(shotSalt ^ (ctx.tick / 25) * 0x9E3779B97F4A7C15L); }

    private static int floor(double d) { int i = (int) d; return d < i ? i - 1 : i; }

    static double hd(double ax, double az, double bx, double bz) { return Math.sqrt(AIMath.hDistSq(ax, az, bx, bz)); }
}
