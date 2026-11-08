package net.h2020.bots.ai.combat;

import net.h2020.api.stats.CombatStats;
import net.h2020.api.bot.AimIntent;
import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.motor.Motor;
import net.h2020.bots.ai.util.AIMath;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.ItemStack;

/**
 * Two item tricks from the movement guides, both of which the bots could not do at all:
 * <ul>
 * <li><b>Ladder plonk</b> - ladders cancel knockback. Placed at the moment a combo is about to send you off an edge,
 * the same hit barely moves you; placed while holding a block, the block drops instantly behind you. Cost: 4 iron
 * (the shop already sells them).</li>
 * <li><b>Fireball jump</b> - jump before throwing for height (the explosion reads both your vertical and horizontal
 * movement); throw behind yourself for horizontal distance instead; and a fireball thrown mid-air redirects incoming
 * knockback.</li>
 * </ul>
 * Both are real item use through the client, so a watcher sees a player doing a ladder save or a fireball jump, not a
 * teleport.
 */
public final class MovementTricks {
    private final BotContext ctx;
    private long lastLadder = -1000, lastFireball = -1000;
    private long fireballTicks = -1;
    private boolean fireballUp;

    public MovementTricks(BotContext ctx) { this.ctx = ctx; }

    /** Ladder save: called when we are being knocked with the void at our back. Returns true when a ladder went down. */
    public boolean ladderPlonk() {
        if (ctx.tick - lastLadder < 40) return false;
        if (Inv.findHotbar(ctx.player, Inv.type(Material.LADDER)) < 0 && !Inv.has(ctx.player, Inv.type(Material.LADDER))) return false;
        Location me = ctx.pos();
        // aim at the block we are standing over (or the one behind us) and place: a ladder there catches the knockback
        Block under = me.getWorld().getBlockAt((int) Math.floor(me.getX()), (int) Math.floor(me.getY()) - 1,
                (int) Math.floor(me.getZ()));
        if (under.getType() == Material.AIR) return false;
        Inv.equip(ctx.player, ctx.c, Inv.type(Material.LADDER), 1);
        ctx.motor.aimPoint(Motor.P_FLICK + 6, under.getLocation().add(0.5, 0.5, 0.5), AimIntent.FLICK, 0.4);
        Block tb = ctx.c.targetedBlock();
        BlockFace tf = ctx.c.targetedFace();
        if (tb != null && tb.equals(under) && tf == BlockFace.UP) {
            ctx.c.rightClick();
            lastLadder = ctx.tick;
            CombatStats.inc("trick.ladder");
            return true;
        }
        return false;
    }

    /** Fireball jump toward a point (height first, then horizontal). Returns true while the trick is running. */
    public boolean fireballJump(Location toward, boolean wantHeight) {
        if (!Inv.holding(ctx.player, Inv.type(Material.FIREBALL)) && Inv.findHotbar(ctx.player, Inv.type(Material.FIREBALL)) < 0)
            return false;
        if (ctx.tick - lastFireball < 60) return false;
        if (fireballTicks < 0) {
            // jump first: the explosion reads the vertical movement, which is what turns it into height
            ctx.motor.jump(true);
            fireballTicks = ctx.tick + 2;
            fireballUp = wantHeight;
            return true;
        }
        if (ctx.tick < fireballTicks) return true;
        Inv.equip(ctx.player, ctx.c, Inv.type(Material.FIREBALL), 1);
        Location me = ctx.pos();
        float yaw;
        float pitch;
        if (fireballUp) {
            yaw = ctx.c.yaw();
            pitch = 85f;                         // straight down: pure height
        } else {
            double dx = me.getX() - toward.getX(), dz = me.getZ() - toward.getZ();
            yaw = AIMath.yawOf(dx, dz);           // behind us: pure horizontal
            pitch = 60f;
        }
        ctx.motor.aimRotation(Motor.P_FLICK + 6, yaw, pitch, AimIntent.FLICK, 1.0);
        ctx.c.rightClick();
        lastFireball = ctx.tick;
        fireballTicks = -1;
        CombatStats.inc(fireballUp ? "trick.fireball.up" : "trick.fireball.horiz");
        return false;
    }

    public boolean busy() { return fireballTicks >= 0; }
}
