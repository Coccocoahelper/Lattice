package net.h2020.bots.ai.motor;

import net.h2020.api.bot.AimIntent;
import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.util.AIMath;
import net.h2020.bots.ai.world.Shape;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

/**
 * Pillaring up ("towering"): look straight down, jump, right-click the top face of the block below once the feet have
 * cleared it (around the jump apex). Centres on the column first so the new block never clips the body.
 * Habits differ (Persona.pillarStyle): most hold space and place right at the apex; beginners hop one block at a
 * time (land, pause, jump again) and place a little late; some hold shift the whole time.
 */
public final class Pillarer {
    public enum Result { RUNNING, DONE, FAILED }

    private final BotContext ctx;
    private int x, z, floorY, startFloor, count;
    private boolean active;
    private int ticks, stuck, cooldown;
    private float pitch;
    private int style, hopWait, placeDelay, swapWait;
    private boolean wasGround = true;

    public Pillarer(BotContext ctx) { this.ctx = ctx; }

    public boolean active() { return active; }

    /** Pillar `count` blocks up from the feet cell (fx,fy,fz). */
    public void start(int fx, int fy, int fz, int count) {
        x = fx; z = fz; floorY = fy - 1; startFloor = floorY; this.count = count;
        active = true; ticks = 0; stuck = 0; cooldown = 0;
        style = ctx.persona.pillarStyle;
        pitch = (float) AIMath.range(ctx.rng, style == 1 ? 84.5 : 86.5, 90);
        hopWait = 0; placeDelay = -1; wasGround = true; swapWait = 0;
    }

    public void cancel() { active = false; }

    public Result tick() {
        if (!active) return Result.FAILED;
        ticks++;
        if (ticks > 60 + count * 30) { active = false; return Result.FAILED; }
        Location p = ctx.pos();
        if (ctx.c.containerOpen() && !Inv.ownInventoryOpen(ctx.player, ctx.c)) ctx.c.closeWindow(); // stray chest window
        // support grew?
        while (Shape.floor(ctx.world().shape(x, floorY + 1, z)) && floorY + 1 < 255) floorY++;
        if (floorY - startFloor >= count) {
            if (ctx.c.onGround()) { active = false; return Result.DONE; }
            return Result.RUNNING;
        }
        if (p.getY() < floorY + 1 - 0.8) { active = false; return Result.FAILED; }
        if (!ctx.world().passable(x, floorY + 3, z)) { active = false; return Result.FAILED; }
        if (!Inv.holding(ctx.player, Inv.BLOCK)) {
            if (!Inv.has(ctx.player, Inv.BLOCK)) { active = false; return Result.FAILED; }
            // a hotbar swap lands one ping later: don't click it back and forth
            if (swapWait > 0 && Inv.findHotbar(ctx.player, Inv.BLOCK) < 0) { swapWait--; return Result.RUNNING; }
            if (!Inv.equip(ctx.player, ctx.c, Inv.BLOCK, 1)) { swapWait = 8; return Result.RUNNING; }
        }
        ctx.motor.aimRotation(Motor.P_TASK, ctx.c.yaw(), pitch, AimIntent.PRECISE, 1.0);
        // stay centred on the column
        double ex = x + 0.5 - p.getX(), ez = z + 0.5 - p.getZ();
        if (ex * ex + ez * ez > 0.04) ctx.motor.moveToward(ex, ez);
        boolean centred = ex * ex + ez * ez < 0.09;
        if (style == 2) ctx.motor.sneak(true);
        boolean ground = ctx.c.onGround();
        if (ground && !wasGround && style == 1) hopWait = AIMath.range(ctx.rng, 2, 9); // landed: catch breath, re-aim
        wasGround = ground;
        if (hopWait > 0) hopWait--;
        else if (centred && ctx.c.aimSettled()) ctx.motor.jump(true);
        if (cooldown > 0) cooldown--;
        Block tb = ctx.c.targetedBlock();
        BlockFace tf = ctx.c.targetedFace();
        // feet must stay clear of the new block until the click lands (next tick; the server's copy of us lags a bit):
        // only on the way up or right at the apex
        double vy = ctx.c.velocity().getY();
        if (cooldown == 0 && tb != null && tf == BlockFace.UP && tb.getX() == x && tb.getZ() == z && tb.getY() == floorY
                && p.getY() + Math.min(0, vy) * 2.5 > floorY + 2.05) {
            // feet clear the block for ~5 ticks around the apex: beginners click somewhere in there, not on the first one
            if (placeDelay < 0) placeDelay = style == 1 ? AIMath.range(ctx.rng, 0, 2) : (ctx.rng.nextDouble() < 0.2 ? 1 : 0);
            if (placeDelay > 0) placeDelay--;
            else {
                ctx.c.rightClick();
                cooldown = 3;
                placeDelay = -1;
            }
        } else if (ground) placeDelay = -1;
        return Result.RUNNING;
    }
}
