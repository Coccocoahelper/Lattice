package net.h2020.bots.ai.brain.bedwars;

import net.h2020.api.bot.AimIntent;
import net.h2020.bots.ai.brain.BedwarsBrain;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.motor.Motor;
import net.h2020.bots.ai.nav.NavOptions;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.inventory.ItemStack;

/**
 * Taking the team's diamonds out of the team chest so they can be spent on the team's upgrade instead of sitting in a
 * box. Only the diamonds, only from a chest that actually has some, and only when the bot is short of its own.
 */
public final class WithdrawTask extends BwTask {
    private final Block chest;
    private int phase;
    private long wait;
    private int clicks;

    public WithdrawTask(BedwarsBrain brain, Block chest) {
        super(brain);
        this.chest = chest;
    }

    public WithdrawTask(BedwarsBrain brain) {
        this(brain, chestWithDiamonds(brain));
    }

    @Override public String name() { return "withdraw"; }

    /** The team chest holding diamonds (nearest one to spawn), or null. */
    public static Block chestWithDiamonds(BedwarsBrain brain) {
        Location base = brain.color() == null ? null : brain.view().teamSpawn(brain.color());
        if (base == null) return null;
        Location me = brain.context().pos();
        Block best = null;
        double bd = 30;
        for (int dx = -20; dx <= 20; dx++) for (int dy = -6; dy <= 6; dy++) for (int dz = -20; dz <= 20; dz++) {
            int x = base.getBlockX() + dx, y = base.getBlockY() + dy, z = base.getBlockZ() + dz;
            Block b = base.getWorld().getBlockAt(x, y, z);
            if (b.getType() != Material.CHEST) continue;
            if (!hasDiamonds(b)) continue;
            double d = me.distance(new Location(me.getWorld(), x + 0.5, y + 0.5, z + 0.5));
            if (d < bd) { bd = d; best = b; }
        }
        return best;
    }

    private static boolean hasDiamonds(Block b) {
        if (!(b.getState() instanceof Chest)) return false;
        for (ItemStack i : ((Chest) b.getState()).getInventory().getContents()) {
            if (i != null && i.getType() == Material.DIAMOND) return true;
        }
        return false;
    }

    @Override
    public boolean tick() {
        if (chest == null || chest.getType() != Material.CHEST) return false;
        if (age() > 20 * 20) { if (ctx.c.containerOpen()) ctx.c.closeWindow(); return false; }
        switch (phase) {
            case 0: {
                if (ctx.pos().distance(chest.getLocation().add(0.5, 0.5, 0.5)) < 3.4) {
                    ctx.nav.stop();
                    ctx.motor.aimPoint(Motor.P_TASK, chest.getLocation().add(0.5, 0.8, 0.5), AimIntent.PRECISE, 0.5);
                    ctx.c.openContainer(chest);
                    phase = 1;
                    wait = ctx.tick + 12;
                    return true;
                }
                ctx.nav.goTo(chest.getLocation(), 2.0, NavOptions.walk());
                ctx.nav.tick();
                return !ctx.nav.failed();
            }
            case 1: {
                if (ctx.c.containerOpen()) { phase = 2; wait = ctx.tick + 4; return true; }
                if (ctx.tick > wait) { ctx.c.openContainer(chest); wait = ctx.tick + 12; }
                return true;
            }
            case 2: {
                if (ctx.tick < wait) return true;
                int slot = diamondSlot();
                if (slot < 0 || clicks > 3) { phase = 3; wait = ctx.tick + 5; return true; }
                ctx.c.clickWindow(slot, 0, 1);              // shift-click the diamonds into the inventory
                clicks++;
                wait = ctx.tick + 4;
                return true;
            }
            default:
                if (ctx.c.containerOpen()) ctx.c.closeWindow();
                else if (ctx.tick > wait) { Inv.equip(ctx.player, ctx.c, Inv.SWORD, 0); return false; }
                return true;
        }
    }

    /** Raw slot of a diamond stack in the chest (top inventory of a chest is 27 slots). */
    private int diamondSlot() {
        if (!(chest.getState() instanceof Chest)) return -1;
        ItemStack[] top = ((Chest) chest.getState()).getInventory().getContents();
        for (int i = 0; i < Math.min(27, top.length); i++) {
            if (top[i] != null && top[i].getType() == Material.DIAMOND) return i;
        }
        return -1;
    }
}
