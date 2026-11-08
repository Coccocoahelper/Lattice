package net.h2020.bots.ai.brain.bedwars;

import net.h2020.api.bot.AimIntent;
import net.h2020.bots.ai.brain.BedwarsBrain;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.motor.Motor;
import net.h2020.bots.ai.nav.NavOptions;
import net.h2020.bots.ai.util.AIMath;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Team play: walk to a teammate and drop them some iron/gold (Q taps while looking at their feet). */
public final class ShareTask extends BwTask {
    private final Player mate;
    private final Material what;
    private int drops;
    private int cooldown;

    public ShareTask(BedwarsBrain brain, Player mate, Material what, int drops) {
        super(brain);
        this.mate = mate;
        this.what = what;
        this.drops = drops;
    }

    @Override public String name() { return "share"; }

    @Override
    public boolean tick() {
        if (mate == null || !mate.isOnline() || mate.isDead() || mate.getWorld() != ctx.player.getWorld()) return false;
        if (age() > 20 * 25 || drops <= 0 || Inv.count(ctx.player, what) == 0) {
            Inv.equip(ctx.player, ctx.c, Inv.SWORD, 0);
            return false;
        }
        double d = ctx.pos().distance(mate.getLocation());
        if (d > 3.0) {
            ctx.nav.follow(mate.getLocation(), 2.2, NavOptions.walk());
            ctx.nav.tick();
            return true;
        }
        ctx.nav.stop();
        ctx.motor.aimPoint(Motor.P_TASK, mate.getLocation().add(0, 0.2, 0), AimIntent.PRECISE, 0.4);
        if (!Inv.equip(ctx.player, ctx.c, Inv.type(what), 8)) return true;
        if (cooldown > 0) { cooldown--; return true; }
        if (ctx.c.aimSettled()) {
            ctx.c.dropHeld(drops > 16 && ctx.rng.nextBoolean());
            drops--;
            cooldown = AIMath.range(ctx.rng, 2, 5);
        }
        return true;
    }
}
