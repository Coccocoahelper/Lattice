package net.h2020.bots.ai.perception;

import net.h2020.api.bot.AimIntent;
import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.motor.Motor;
import net.h2020.bots.ai.util.AIMath;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * Natural head movement when nothing else needs the mouse: glances toward noises, looking at nearby players/NPCs,
 * random look-arounds, checking behind (awareness-dependent), idle micro-drift. The brain passes the priority it
 * may use (P_IDLE when standing, P_LOOK to override walking aim on safe straights).
 */
public final class Gaze {
    private enum Mode { NONE, POINT, ENTITY, ROTATION }

    private final BotContext ctx;
    private Mode mode = Mode.NONE;
    private Location point;
    private Entity entity;
    private float yaw, pitch;
    private int holdTicks;
    private long nextLook;
    private float homeYaw, homePitch;
    private boolean returning;

    public Gaze(BotContext ctx) {
        this.ctx = ctx;
        this.nextLook = 20 + ctx.rng.nextInt(60);
    }

    public boolean busy() { return mode != Mode.NONE; }

    public void reset() { mode = Mode.NONE; holdTicks = 0; }

    /** @param priority aim priority to use; @param walking true when moving (keep glances short, no entity stares) */
    public void tick(int priority, boolean walking) {
        long now = ctx.tick;
        Location glance = ctx.perception.takeGlance();
        if (glance != null) {
            begin(Mode.POINT);
            point = glance;
            holdTicks = AIMath.range(ctx.rng, 8, 22);
        }
        if (mode == Mode.NONE && now >= nextLook) chooseLook(walking);
        if (mode == Mode.NONE) return;
        if (--holdTicks <= 0) {
            mode = Mode.NONE;
            nextLook = now + AIMath.msToTicks(ctx.rng, ctx.persona.lookAroundMs * (walking ? 1.4 : 1.0));
            if (!walking && returning) {
                // look back roughly to where we were looking before
                mode = Mode.ROTATION;
                yaw = homeYaw + (float) ctx.rng.nextGaussian() * 10f;
                pitch = homePitch + (float) ctx.rng.nextGaussian() * 4f;
                holdTicks = AIMath.range(ctx.rng, 6, 12);
                returning = false;
            }
            return;
        }
        switch (mode) {
            case POINT: ctx.motor.aimPoint(priority, point, AimIntent.GLANCE, 0.8); break;
            case ENTITY:
                if (entity == null || entity.isDead() || entity.getWorld() != ctx.player.getWorld()) { mode = Mode.NONE; break; }
                ctx.motor.aimEntity(priority, entity, AimIntent.GLANCE);
                break;
            case ROTATION: ctx.motor.aimRotation(priority, yaw, pitch, AimIntent.GLANCE, 6.0); break;
            default: break;
        }
    }

    private void begin(Mode m) {
        if (mode == Mode.NONE) { homeYaw = ctx.c.yaw(); homePitch = ctx.c.pitch(); }
        mode = m;
    }

    private void chooseLook(boolean walking) {
        double aw = ctx.persona.skill.awareness;
        double r = ctx.rng.nextDouble();
        if (!walking && r < 0.4) {
            Entity e = interesting(12);
            if (e != null) {
                begin(Mode.ENTITY);
                entity = e;
                holdTicks = AIMath.range(ctx.rng, 20, 70);
                returning = false;
                return;
            }
        }
        if (r < 0.4 + 0.25 * aw) {
            // check behind
            begin(Mode.ROTATION);
            yaw = AIMath.wrap(ctx.c.yaw() + 180f + (float) ctx.rng.nextGaussian() * 35f);
            pitch = (float) AIMath.gauss(ctx.rng, 4, 8, -20, 30);
            holdTicks = AIMath.range(ctx.rng, walking ? 6 : 10, walking ? 12 : 26);
            returning = true;
            return;
        }
        begin(Mode.ROTATION);
        float spread = walking ? 45f : 75f;
        yaw = AIMath.wrap(ctx.c.yaw() + (float) ctx.rng.nextGaussian() * spread);
        pitch = (float) AIMath.gauss(ctx.rng, 6, 12, -35, 45);
        holdTicks = AIMath.range(ctx.rng, walking ? 6 : 12, walking ? 14 : 45);
        returning = !walking && ctx.rng.nextDouble() < 0.5;
    }

    private Entity interesting(double r) {
        Location me = ctx.pos();
        Entity best = null;
        double bd = Double.MAX_VALUE;
        for (Entity e : ctx.player.getNearbyEntities(r, 4, r)) {
            if (!(e instanceof LivingEntity)) continue;
            if (e instanceof Player && ((Player) e).isSneaking() && ctx.rng.nextDouble() < 0.5) continue;
            double d = e.getLocation().distanceSquared(me) * (0.6 + ctx.rng.nextDouble());
            if (d < bd) { bd = d; best = e; }
        }
        return best;
    }
}
