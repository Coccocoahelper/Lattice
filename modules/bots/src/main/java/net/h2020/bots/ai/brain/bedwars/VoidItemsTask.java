package net.h2020.bots.ai.brain.bedwars;

import net.h2020.api.bot.AimIntent;
import net.h2020.bots.ai.brain.BedwarsBrain;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.motor.Motor;
import net.h2020.bots.ai.nav.NavOptions;
import org.bukkit.Location;
import org.bukkit.Material;

/**
 * Throwing the valuables into the void: the last resort when the bot knows the fight is lost, no teammate is close
 * enough to take them, and there is no ender chest to put them in. Walks to the nearest edge, looks down over it and
 * drops the stacks one by one, so the kill leaves nothing behind for the enemy to loot.
 * <p>
 * Only ever started for a bot that is carrying something worth it (see BedwarsBrain): throwing away a stack of iron
 * mid-fight would be worse than losing it.
 */
public final class VoidItemsTask extends BwTask {
    private static final Material[] VALUABLES = {Material.EMERALD, Material.DIAMOND, Material.GOLD_INGOT, Material.IRON_INGOT};

    private Location edge;
    private long looked;
    private int cooldown;

    public VoidItemsTask(BedwarsBrain brain) { super(brain); }

    @Override public String name() { return "voiditems"; }

    private Material next() {
        for (Material m : VALUABLES) if (Inv.count(ctx.player, m) > 0) return m;
        return null;
    }

    @Override
    public boolean tick() {
        if (age() > 20 * 12) return false;
        Material m = next();
        if (m == null) return false;
        if (edge == null || ctx.tick - looked > 40) { edge = VoidTask.findEdge(ctx, ctx.pos()); looked = ctx.tick; }
        if (edge == null) return false;
        double d = ctx.pos().distance(edge);
        if (d > 1.2) {
            NavOptions o = NavOptions.walk();
            o.parkourGap = 0;
            o.avoidEnemies = false;
            ctx.nav.goTo(edge, 0.9, o);
            ctx.nav.tick();
            if (ctx.nav.failed()) { edge = null; return true; }
            return true;
        }
        ctx.nav.stop();
        // look down over the drop and let the stack go into the void
        ctx.motor.aimPoint(Motor.P_TASK, edge.clone().add(0, -4.0, 0), AimIntent.PRECISE, 1.0);
        if (!Inv.equip(ctx.player, ctx.c, Inv.type(m), 8)) return true;
        if (cooldown > 0) { cooldown--; return true; }
        if (ctx.c.aimSettled()) {
            ctx.c.dropHeld(true);
            cooldown = 4;
        }
        return true;
    }
}
