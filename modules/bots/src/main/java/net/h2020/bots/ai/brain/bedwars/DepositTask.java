package net.h2020.bots.ai.brain.bedwars;

import net.h2020.api.bot.AimIntent;
import net.h2020.bots.ai.brain.BedwarsBrain;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.motor.Motor;
import net.h2020.bots.ai.nav.NavOptions;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;

/**
 * Dropping the team's resources where the team can use them: the island chest next to spawn while the team still has
 * somebody to share with, the ender chest when the bot is on its own (solo, or the last one alive on its team).
 * <p>
 * The bot keeps enough to keep working (8 iron and its blocks) and puts the rest in - emeralds and diamonds first,
 * then gold - so a teammate can buy the upgrade or the pearl instead of the bot hoarding it into a final death.
 */
public final class DepositTask extends BwTask {
    private static final Material[] DEPOSIT = {Material.EMERALD, Material.DIAMOND, Material.GOLD_INGOT};

    private final Block chest;
    private int phase;             // 0 walk, 1 open, 2 click, 3 close
    private long wait;
    private int clicks;
    private boolean announced;

    public DepositTask(BedwarsBrain brain, Block chest) {
        super(brain);
        this.chest = chest;
    }

    @Override public String name() { return "deposit"; }

    /** Where this bot should drop its resources, or null when there is nothing suitable nearby. */
    public static Block spot(BedwarsBrain brain, boolean solo) {
        Location base = brain.color() == null ? null : brain.view().teamSpawn(brain.color());
        if (base == null) return null;
        Location me = brain.context().pos();
        if (me.getWorld() != base.getWorld()) return null;
        Block ender = null;
        Block teamChest = null;
        double bdE = 26, bdC = 26;
        for (int dx = -20; dx <= 20; dx++) for (int dy = -6; dy <= 6; dy++) for (int dz = -20; dz <= 20; dz++) {
            int x = base.getBlockX() + dx, y = base.getBlockY() + dy, z = base.getBlockZ() + dz;
            Material m = base.getWorld().getBlockAt(x, y, z).getType();
            if (m != Material.CHEST && m != Material.ENDER_CHEST) continue;
            double d = me.distanceSquared(new Location(me.getWorld(), x + 0.5, y + 0.5, z + 0.5));
            if (m == Material.ENDER_CHEST) { if (d < bdE) { bdE = d; ender = base.getWorld().getBlockAt(x, y, z); } }
            else if (d < bdC) { bdC = d; teamChest = base.getWorld().getBlockAt(x, y, z); }
        }
        // on our own, the ender chest is the safer one (nobody else is going to loot the team chest)
        if (solo && ender != null) return ender;
        if (teamChest != null) return teamChest;
        return ender;
    }

    /** Anything worth a trip home? */
    public static boolean worthDepositing(BedwarsBrain brain) {
        return ShopPlanner.emeralds(brain.context().player) > 0 || ShopPlanner.diamonds(brain.context().player) > 1;
    }

    @Override
    public boolean tick() {
        if (chest.getType() != Material.CHEST && chest.getType() != Material.ENDER_CHEST) return false;
        if (age() > 20 * 20) { if (ctx.c.containerOpen()) ctx.c.closeWindow(); return false; }
        switch (phase) {
            case 0: {
                if (!announced && brain.teamMode()) {
                    announced = true;
                    int ems = ShopPlanner.emeralds(ctx.player), dias = ShopPlanner.diamonds(ctx.player);
                    if (ems > 0) ctx.chat.callout("got " + ems + " em" + (ems == 1 ? "" : "s"), 0.4);
                    else if (dias > 0) ctx.chat.callout("got " + dias + " dias", 0.3);
                }
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
                if (ctx.nav.failed()) return false;
                return true;
            }
            case 1: {
                if (ctx.c.containerOpen()) { phase = 2; wait = ctx.tick + 4; return true; }
                if (ctx.tick > wait) { ctx.c.openContainer(chest); wait = ctx.tick + 12; }
                return true;
            }
            case 2: {
                if (ctx.tick < wait) return true;
                int slot = nextSlot();
                if (slot < 0 || clicks > 8) { phase = 3; wait = ctx.tick + 5; return true; }
                ctx.c.clickWindow(slot, 0, 1);                 // shift-click into the chest
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

    private int nextSlot() {
        for (Material m : DEPOSIT) {
            int idx = Inv.find(ctx.player, Inv.type(m));
            if (idx >= 0) return Inv.rawSlotInContainer(27, idx);
        }
        return -1;
    }
}
