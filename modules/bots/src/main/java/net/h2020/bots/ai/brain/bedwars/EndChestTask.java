package net.h2020.bots.ai.brain.bedwars;

import net.h2020.api.bot.AimIntent;
import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.brain.BedwarsBrain;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.motor.Motor;
import net.h2020.bots.ai.nav.NavOptions;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;

/**
 * "Chesting" the valuables: walk to the island's ender chest and shift-click the emeralds / diamonds / gold / iron in.
 * The third choice when a bot knows it is about to die - after handing them to a teammate, before throwing them into
 * the void. Uses the real window: open the chest, click stacks in like a player, close.
 */
public final class EndChestTask extends BwTask {
    /**
     * What is worth stowing: the resources, the consumables, and the tools (shears / pickaxes / axes). The tier-one
     * tool is never deposited - the server keeps it in the inventory, and so does the bot.
     */
    private static final Material[] VALUABLES = {Material.EMERALD, Material.DIAMOND, Material.GOLD_INGOT, Material.IRON_INGOT,
            Material.ENDER_PEARL, Material.GOLDEN_APPLE, Material.SHEARS,
            Material.IRON_PICKAXE, Material.DIAMOND_PICKAXE, Material.GOLD_PICKAXE,
            Material.STONE_AXE, Material.IRON_AXE, Material.DIAMOND_AXE};

    private final Location chest;
    private final Block block;
    private int phase;                 // 0 walk, 1 open, 2 click stacks in, 3 close
    private long wait;
    private int clicks;

    public EndChestTask(BedwarsBrain brain, Location chest) {
        super(brain);
        this.chest = chest;
        this.block = chest.getWorld().getBlockAt(chest);
    }

    @Override public String name() { return "endchest"; }

    /** Nearest ender chest within `r` blocks of the bot, or null. */
    public static Location nearest(BotContext ctx, double r) {
        Location me = ctx.pos();
        Location best = null;
        double bd = r;
        int ir = (int) Math.ceil(r);
        for (int dx = -ir; dx <= ir; dx++) for (int dy = -3; dy <= 3; dy++) for (int dz = -ir; dz <= ir; dz++) {
            int x = me.getBlockX() + dx, y = me.getBlockY() + dy, z = me.getBlockZ() + dz;
            if (me.getWorld().getBlockAt(x, y, z).getType() != Material.ENDER_CHEST) continue;
            double d = me.distance(new Location(me.getWorld(), x + 0.5, y + 0.5, z + 0.5));
            if (d < bd) { bd = d; best = new Location(me.getWorld(), x, y, z); }
        }
        return best;
    }

    @Override
    public boolean tick() {
        if (block.getType() != Material.ENDER_CHEST) return false;
        if (age() > 20 * 15) { if (ctx.c.containerOpen()) ctx.c.closeWindow(); return false; }
        switch (phase) {
            case 0: {
                if (ctx.pos().distance(chest.clone().add(0.5, 0.5, 0.5)) < 3.4) {
                    ctx.nav.stop();
                    ctx.motor.aimPoint(Motor.P_TASK, chest.clone().add(0.5, 0.8, 0.5), AimIntent.PRECISE, 0.5);
                    ctx.c.openContainer(block);
                    phase = 1;
                    wait = ctx.tick + 12;
                    return true;
                }
                ctx.nav.goTo(chest, 2.0, NavOptions.walk());
                ctx.nav.tick();
                if (ctx.nav.failed()) return false;
                return true;
            }
            case 1: {
                if (ctx.c.containerOpen()) { phase = 2; wait = ctx.tick + 4; return true; }
                if (ctx.tick > wait) { ctx.c.openContainer(block); wait = ctx.tick + 12; }
                return true;
            }
            case 2: {
                if (ctx.tick < wait) return true;
                int slot = nextStackSlot();
                if (slot < 0 || clicks > 12) { phase = 3; wait = ctx.tick + gap(3, 7); return true; }
                ctx.c.clickWindow(slot, 0, 1);      // shift-click: stack goes into the chest
                clicks++;
                wait = ctx.tick + gap(3, 7);
                return true;
            }
            default:
                if (ctx.c.containerOpen()) ctx.c.closeWindow();
                else if (ctx.tick > wait) { Inv.equip(ctx.player, ctx.c, Inv.SWORD, 0); return false; }
                return true;
        }
    }

    private int gap(int lo, int hi) { return net.h2020.bots.ai.util.AIMath.range(ctx.rng, lo, hi); }

    /** Raw slot (in the 27-slot chest window) of the next valuable stack, or -1. */
    private int nextStackSlot() {
        for (Material m : VALUABLES) {
            int idx = Inv.find(ctx.player, Inv.type(m));
            if (idx >= 0) return Inv.rawSlotInContainer(27, idx);
        }
        return -1;
    }
}
