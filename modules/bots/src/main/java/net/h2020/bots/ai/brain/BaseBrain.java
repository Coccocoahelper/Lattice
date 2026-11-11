package net.h2020.bots.ai.brain;

import net.h2020.api.bot.AimIntent;
import net.h2020.api.bot.Bot;
import net.h2020.api.bot.BotBrain;
import net.h2020.bots.ai.BotAI;
import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.motor.Motor;
import net.h2020.bots.ai.perception.Sighting;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.logging.Level;

/**
 * Common brain skeleton: builds the bot's AI components, runs perception/chat/gaze around the subclass's think(),
 * flushes inputs once per tick, and stops all inputs while dead, spectating or otherwise unable to act.
 */
public abstract class BaseBrain implements BotBrain {
    protected BotContext ctx;
    protected Bot bot;
    private boolean halted = true;
    private Player flickTarget;
    private int flickTicks;
    private int errors;

    @Override
    public final void start(Bot bot) {
        this.bot = bot;
        this.ctx = new BotContext(bot);
        BotAI.register(bot.player(), this);
        ctx.motor.halt();
        try { onStart(); } catch (Throwable t) { report(t); }
    }

    @Override
    public final void tick(Bot bot) {
        if (ctx == null || bot.isRemoved()) return;
        ctx.tick++;
        ctx.motor.beginTick();
        try {
            if (!canAct()) {
                if (!halted) {
                    ctx.motor.halt();
                    ctx.nav.stop();
                    ctx.combat.disengage();
                    halted = true;
                    onInactive();
                }
                ctx.chat.tick();
                idleTick();
                return;
            }
            if (halted) { halted = false; onActive(); }
            ctx.perception.tick();
            ctx.chat.tick();
            Player f = ctx.perception.takeFlick();
            if (f != null) { flickTarget = f; flickTicks = 6 + ctx.rng.nextInt(5); }
            if (flickTicks > 0 && flickTarget != null) {
                flickTicks--;
                ctx.motor.aimEntity(Motor.P_FLICK, flickTarget, AimIntent.FLICK);
            }
            // (combat package) clutches when knocked off / the calm heal after a fight pre-empt the brain this tick
            ctx.c.setTickPurpose(ctx.combat.engaged() || ctx.nav.active());
            if (!ctx.combat.emergencyTick()) think();
            ctx.nav.settle();
            // a player in water holds space: swims to the surface and bobs there, high enough that pushing into a
            // bank triggers vanilla's 0.3 "jump out of water" boost. Without it the body sinks (and on the bottom of
            // shallow water a jump press is only +0.04, so it can never hop out).
            if (ctx.inLiquid()) ctx.motor.jump(true);
            // natural looking around when nothing important holds the mouse
            int claimed = ctx.motor.claimedPriority();
            if (claimed < Motor.P_NAV) ctx.gaze.tick(Motor.P_IDLE, false);
            else if (claimed == Motor.P_NAV && ctx.nav.safeToGlance() && !ctx.combat.engaged()) ctx.gaze.tick(Motor.P_LOOK, true);
            ctx.motor.flush();
        } catch (Throwable t) {
            report(t);
            try { ctx.motor.halt(); } catch (Throwable ignored) {}
        }
    }

    @Override
    public final void stop(Bot bot) {
        BotAI.unregister(bot.player(), this);
        if (ctx == null) return;
        try { onStop(); } catch (Throwable t) { report(t); }
        try {
            ctx.nav.stop();
            if (ctx.c.containerOpen()) ctx.c.closeWindow();
            ctx.motor.halt();
        } catch (Throwable ignored) {}
    }

    private void report(Throwable t) {
        if (errors++ < 3) BotAI.log().log(Level.WARNING, "[bots-ai] " + getClass().getSimpleName() + " error for " + bot.player().getName(), t);
    }

    /** Player can move/act (alive, not a spectator). Subclasses add game-specific conditions. */
    protected boolean canAct() {
        Player p = ctx.player;
        return p.isOnline() && !p.isDead() && p.getHealth() > 0 && p.getGameMode() != GameMode.SPECTATOR;
    }

    /** Still runs while inactive (e.g. watch the respawn timer). */
    protected void idleTick() {}

    protected abstract void think();

    protected void onStart() {}
    protected void onStop() {}
    protected void onInactive() {}
    protected void onActive() {}

    // ---- events dispatched by BotAI's listener ----
    public void onHurt(Player attacker, double damage) {
        if (attacker != null) {
            ctx.perception.onHurt(attacker);
            ctx.combat.onHurt(attacker);
        }
    }

    public void onHitLanded(Player victim) {
        ctx.perception.onHitLanded(victim);
        ctx.combat.onHitLanded(victim);
    }

    public void onKill(Player victim, boolean finalKill) {
        ctx.combat.onKill(victim);
        if (ctx.combat.target() == victim) ctx.combat.disengage();
        ctx.chat.kill(finalKill);
    }

    public void onDeath(Player killer, boolean voidDeath) {
        boolean sus = killer != null && (net.h2020.bots.ai.perception.PlayerStats.cps(killer) > 14.5
                || net.h2020.bots.ai.perception.PlayerStats.recentReach(killer) > 3.6
                /*|| net.h2020.bots.ai.perception.PlayerStats.suspicious(killer)*/);
        ctx.chat.died(killer != null, sus, voidDeath);
        // some players really /wdr the guy who killed them (more so the chatty and the salty ones)
        //if (sus && ctx.rng.nextDouble() < 0.2 + 0.4 * ctx.persona.skill.chattiness) ctx.chat.report(killer.getName());
        ctx.combat.disengage();
        ctx.nav.stop();
    }

    /** Server moved us far (respawn, game teleport): any path is stale. */
    public void onTeleported() {
        ctx.nav.stop();
        ctx.combat.disengage();
    }

    // ---- shared helpers ----

    /** Pick a fight if a known enemy is close/threatening. Returns true when combat should drive this tick. */
    protected boolean combatPolicy(double engageRange) {
        if (ctx.combat.engaged()) {
            Sighting cur = ctx.perception.get(ctx.combat.target());
            Sighting best = ctx.perception.bestThreat();
            if (best != null && cur != null && best != cur && best.visible
                    && best.threat > cur.threat * 1.8 && best.estimate(ctx.tick).distance(ctx.pos()) < 4) {
                ctx.combat.engage(best.player);
            }
            return true;
        }
        Sighting best = ctx.perception.bestThreat();
        if (best == null) return false;
        Location me = ctx.pos();
        double d = best.estimate(ctx.tick).distance(me);
        double range = engageRange * (0.7 + 0.6 * ctx.persona.skill.aggression);
        boolean hurtMe = ctx.tick - best.lastHurtMeTick < 60;
        if (!hurtMe && ctx.combat.ignoring(best.player)) return false; // just gave up on reaching them
        // mid-bridge / mid-pillar: finish the block line unless they are on top of us (dropping the keys there is
        // how bridgers walk off the end)
        if (!hurtMe && ctx.nav.doingAction() && d > 4.5) return false;
        if ((best.visible && d < range) || (hurtMe && d < 12)) {
            ctx.nav.stop();
            ctx.combat.engage(best.player);
            return true;
        }
        return false;
    }

    public BotContext context() { return ctx; }

    /** The player this brain drives (null before start). */
    public Player player() { return bot == null ? null : bot.player(); }

    /** Name of the running task, for telemetry; subclasses override with their task list. */
    public String taskName() { return "none"; }
}
