package net.h2020.bots.ai.brain.bedwars;

import net.h2020.api.bot.AimIntent;
import net.h2020.bots.ai.brain.BedwarsBrain;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.motor.Motor;
import net.h2020.bots.ai.nav.NavOptions;
import net.h2020.bots.ai.util.AIMath;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Handing the valuables to a teammate: emeralds, diamonds, gold and iron, dropped at their feet stack by stack - what
 * a player does when he is one hit from dying and a teammate is standing next to him, so the resources stay on the
 * team instead of going to whoever lands the kill.
 */
public final class GiftTask extends BwTask {
    private static final Material[] VALUABLES = {Material.EMERALD, Material.DIAMOND, Material.GOLD_INGOT, Material.IRON_INGOT};

    private final Player mate;
    /** Also pass on the odds and ends (blocks, gapples, pearls) - used when the bot is voiding anyway. */
    private final boolean everything;
    private int cooldown;

    public GiftTask(BedwarsBrain brain, Player mate, boolean everything) {
        super(brain);
        this.mate = mate;
        this.everything = everything;
    }

    @Override public String name() { return "gift"; }

    /** First valuable stack still in the inventory (null when there is nothing left to give). */
    private Material nextValuable() {
        for (Material m : VALUABLES) if (Inv.count(ctx.player, m) > 0) return m;
        if (everything) {
            if (Inv.count(ctx.player, Inv.GAPPLE) > 0) return Material.GOLDEN_APPLE;
            if (Inv.count(ctx.player, Inv.PEARL) > 0) return Material.ENDER_PEARL;
            if (Inv.count(ctx.player, Inv.FIREBALL) > 0) return Material.FIREBALL;
        }
        return null;
    }

    /** Is there anything worth handing over? */
    public static boolean carriesValuables(Player p) {
        for (Material m : VALUABLES) if (Inv.count(p, m) > 0) return true;
        return false;
    }

    @Override
    public boolean tick() {
        if (mate == null || !mate.isOnline() || mate.isDead() || mate.getWorld() != ctx.player.getWorld()) return false;
        if (age() > 20 * 20) return false;
        Material m = nextValuable();
        if (m == null) {
            Inv.equip(ctx.player, ctx.c, Inv.SWORD, 0);
            return false;
        }
        double d = ctx.pos().distance(mate.getLocation());
        if (d > 3.0) {
            ctx.nav.follow(mate.getLocation(), 2.2, NavOptions.walk());
            ctx.nav.tick();
            if (ctx.nav.failed()) return false;
            return true;
        }
        ctx.nav.stop();
        ctx.motor.aimPoint(Motor.P_TASK, mate.getLocation().add(0, 0.25, 0), AimIntent.PRECISE, 0.5);
        if (!Inv.equip(ctx.player, ctx.c, Inv.type(m), 8)) return true;
        if (cooldown > 0) { cooldown--; return true; }
        if (ctx.c.aimSettled()) {
            ctx.c.dropHeld(true);                   // whole stack, right at their feet
            cooldown = AIMath.range(ctx.rng, 3, 7);
        }
        return true;
    }
}
