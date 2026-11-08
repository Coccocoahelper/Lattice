package net.h2020.bots.ai.brain;

import net.h2020.api.bot.AimIntent;
import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.motor.Motor;
import net.h2020.bots.ai.nav.NavOptions;
import net.h2020.bots.ai.util.AIMath;
import net.h2020.bots.ai.world.WorldView;
import org.bukkit.Location;

/**
 * Lobby / pre-game idling: strolling between random walkable spots with natural pauses, AFK stretches, jumping
 * around, the occasional parkour hop, opening the game menu (slot 0 compass right-click) and closing it again,
 * hotbar scrolling. Reused by the Bed Wars / SkyWars waiting lobbies with a smaller roaming radius.
 */
public final class IdleBehavior {
    private enum Mode { PAUSE, WANDER, AFK, JUMPY, MENU, SCROLL, CROUCH }

    private final BotContext ctx;
    private Mode mode = Mode.PAUSE;
    private long until;
    private double roam;
    private Location home;
    private int jumpTimer;
    private boolean menuOpened;
    private int scrollLeft;
    private final boolean useMenu;

    public IdleBehavior(BotContext ctx, double roamRadius, boolean useMenu) {
        this.ctx = ctx;
        this.roam = roamRadius;
        this.useMenu = useMenu;
        this.until = ctx.tick + AIMath.range(ctx.rng, 20, 80);
    }

    public void setHome(Location l) { home = l == null ? null : l.clone(); }
    public void setRoam(double r) { roam = r; }

    public void tick() {
        if (home == null || home.getWorld() != ctx.player.getWorld()) home = ctx.pos();
        switch (mode) {
            case WANDER:
                ctx.nav.tick();
                if (!ctx.nav.active() || ctx.tick > until) { ctx.nav.stop(); next(); }
                break;
            case AFK:
                // nearly motionless: tiny look drift now and then
                if (ctx.tick % 60 == 0 && ctx.rng.nextDouble() < 0.4) {
                    ctx.motor.aimRotation(Motor.P_TASK, ctx.c.yaw() + (float) ctx.rng.nextGaussian() * 6f,
                            ctx.c.pitch() + (float) ctx.rng.nextGaussian() * 2f, AimIntent.GLANCE, 1.0);
                }
                if (ctx.tick > until) next();
                break;
            case JUMPY:
                if (--jumpTimer <= 0) { ctx.motor.jump(true); jumpTimer = AIMath.range(ctx.rng, 1, 14); }
                if (ctx.rng.nextDouble() < 0.05) ctx.motor.move(0, ctx.rng.nextBoolean() ? 1 : -1);
                if (ctx.tick > until) next();
                break;
            case MENU:
                if (!menuOpened) {
                    if (ctx.c.heldSlot() != 0) ctx.c.selectHotbar(0);
                    else if (ctx.tick > until - 60) { ctx.c.rightClick(); menuOpened = true; }
                } else if (ctx.tick > until) {
                    if (ctx.c.containerOpen()) ctx.c.closeWindow();
                    next();
                }
                break;
            case CROUCH:
                // crouch spam (at someone, usually): a few quick toggles, a short break, again
                if (ctx.tick % 9 < 6) ctx.motor.sneak(ctx.tick % 3 != 0);
                if (ctx.tick > until) next();
                break;
            case SCROLL:
                if (ctx.tick % 3 == 0 && scrollLeft-- > 0) ctx.c.selectHotbar((ctx.c.heldSlot() + (ctx.rng.nextBoolean() ? 1 : 8)) % 9);
                if (scrollLeft <= 0) next();
                break;
            case PAUSE:
            default:
                if (ctx.tick > until) next();
        }
    }

    private void next() {
        double r = ctx.rng.nextDouble();
        menuOpened = false;
        if (ctx.c.containerOpen()) ctx.c.closeWindow();
        if (ctx.persona.afkProne && r < 0.12) {
            mode = Mode.AFK;
            until = ctx.tick + AIMath.range(ctx.rng, 20 * 40, 20 * 240);
        } else if (r < 0.55) {
            Location to = randomSpot();
            if (to != null) {
                NavOptions o = NavOptions.strollOpts();
                if (ctx.persona.sprintJumper && ctx.rng.nextDouble() < 0.35) { o.sprint = true; o.stroll = false; o.parkourGap = 2; }
                ctx.nav.goTo(to, 1.2, o);
                mode = Mode.WANDER;
                until = ctx.tick + 20 * 30;
            } else { mode = Mode.PAUSE; until = ctx.tick + AIMath.range(ctx.rng, 20, 60); }
        } else if (r < 0.62) {
            mode = Mode.JUMPY;
            until = ctx.tick + AIMath.range(ctx.rng, 20, 90);
        } else if (r < 0.65) {
            mode = Mode.CROUCH;
            until = ctx.tick + AIMath.range(ctx.rng, 12, 45);
        } else if (useMenu && r < 0.69) {
            mode = Mode.MENU;
            until = ctx.tick + AIMath.range(ctx.rng, 60, 140);
        } else if (r < 0.73) {
            mode = Mode.SCROLL;
            scrollLeft = AIMath.range(ctx.rng, 2, 9);
        } else {
            mode = Mode.PAUSE;
            until = ctx.tick + AIMath.range(ctx.rng, 30, 220);
            if (ctx.rng.nextDouble() < 0.15) ctx.chat.lobbyIdle();
        }
    }

    private Location randomSpot() {
        WorldView wv = ctx.world();
        Location base = home != null && ctx.pos().distance(home) > roam ? home : ctx.pos();
        for (int attempt = 0; attempt < 12; attempt++) {
            double ang = ctx.rng.nextDouble() * Math.PI * 2;
            double dist = AIMath.range(ctx.rng, 4, roam);
            int x = (int) Math.floor(base.getX() + Math.cos(ang) * dist);
            int z = (int) Math.floor(base.getZ() + Math.sin(ang) * dist);
            if (home != null && AIMath.hDistSq(x + 0.5, z + 0.5, home.getX(), home.getZ()) > roam * roam * 1.5) continue;
            int y0 = (int) Math.floor(base.getY());
            for (int y = y0 + 4; y >= y0 - 6; y--) {
                if (wv.standable(x, y, z) && wv.voidSides(x, y, z) == 0 && !wv.lavaNear(x, y, z)) {
                    return new Location(base.getWorld(), x + 0.5, y, z + 0.5);
                }
            }
        }
        return null;
    }
}
