package net.h2020.bots.ai.combat;

import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.motor.BlockPlacer;
import net.h2020.bots.ai.world.Shape;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Ladders as an answer to height.
 * <p>
 * The guide work says two things about ladders that the bots were not using: standing on one cancels most of the
 * knockback you would take, and climbing one is how you reach somebody who has towered above you or is camping on the
 * lip of your base. This does the second: when the enemy is above us and there is a wall to lean the ladder on, place
 * one and climb it instead of walking the long way round or tower-blocking into their sword.
 */
public final class LadderAssault {
    private final BotContext ctx;
    private final BlockPlacer placer;
    private long lastTry = -10000;
    private int climbTicks;
    private boolean climbing;
    private double targetY;

    public LadderAssault(BotContext ctx) {
        this.ctx = ctx;
        this.placer = new BlockPlacer(ctx);
    }

    public boolean busy() { return climbing || placer.active(); }

    public void cancel() {
        placer.cancel();
        climbing = false;
        climbTicks = 0;
    }

    /** @return true while it owns the movement. */
    public boolean tick(Player foe, double d) {
        if (foe == null) { cancel(); return false; }
        Location me = ctx.pos();
        double up = foe.getLocation().getY() - me.getY();
        if (climbing) return climb(me, foe);
        if (up < 2.2 || up > 9 || d > 5.5) { if (up >= 1.2) CombatStats.inc("ladder.norange"); return false; }
        if (ctx.tick - lastTry < 140) return false;
        if (Inv.findHotbar(ctx.player, Inv.type(Material.LADDER)) < 0
                && !Inv.has(ctx.player, Inv.type(Material.LADDER))) { CombatStats.inc("ladder.none"); return false; }

        int x = (int) Math.floor(me.getX()), y = (int) Math.floor(me.getY()), z = (int) Math.floor(me.getZ());
        double dx = foe.getLocation().getX() - me.getX(), dz = foe.getLocation().getZ() - me.getZ();
        int sx = Math.abs(dx) > Math.abs(dz) ? (int) Math.signum(dx) : 0;
        int sz = sx == 0 ? (int) Math.signum(dz) : 0;
        // the ladder goes in the air cell against a wall, at our feet level. Any of the four sides will do: the tower
        // is not always the side we happen to be facing, and the first guess failed on exactly that
        int lx = 0, lz = 0;
        boolean found = false;
        int[][] prefer = {{sx, sz}, {-sz, sx}, {sz, -sx}, {-sx, -sz}};
        for (int[] dir : prefer) {
            if (dir[0] == 0 && dir[1] == 0) continue;
            int cx = x + dir[0], cz = z + dir[1];
            if (!ctx.world().passable(cx, y, cz) || !ctx.world().passable(cx, y + 1, cz)) continue;
            if (Shape.passable(ctx.world().shape(cx + dir[0], y, cz + dir[1]))) continue;   // no wall behind it
            lx = cx; lz = cz; found = true;
            break;
        }
        if (!found) { CombatStats.inc("ladder.nowall"); return false; }
        if (!Inv.equip(ctx.player, ctx.c, Inv.type(Material.LADDER), 1)) return false;
        placer.setNeedSight(true);
        placer.setIgnoreEntities(true);   // a ladder is not a solid block: our own body does not block it
        placer.start(lx, y, lz, Inv.type(Material.LADDER));
        lastTry = ctx.tick;
        targetY = foe.getLocation().getY();
        CombatStats.inc("ladder.place");
        climbing = true;
        climbTicks = 0;
        return true;
    }

    private boolean climb(Location me, Player foe) {
        BlockPlacer.Result r = placer.tick();
        if (r == BlockPlacer.Result.RUNNING) return true;
        if (r == BlockPlacer.Result.FAILED) { climbing = false; CombatStats.inc("ladder.fail"); return false; }
        climbTicks++;
        if (me.getY() >= targetY - 0.8 || climbTicks > 80) {
            climbing = false;
            if (me.getY() >= targetY - 1.2) CombatStats.inc("ladder.reached");
            return false;
        }
        // walk into the ladder and hold jump: that is all a climb is
        double dx = foe.getLocation().getX() - me.getX(), dz = foe.getLocation().getZ() - me.getZ();
        ctx.motor.moveToward(dx, dz);
        ctx.motor.jump(true);
        ctx.motor.sprint(false);
        ctx.motor.aimPoint(net.h2020.bots.ai.motor.Motor.P_COMBAT, foe.getLocation().add(0, 1.2, 0),
                net.h2020.api.bot.AimIntent.COMBAT);
        CombatStats.inc("ladder.climb");
        return true;
    }
}
