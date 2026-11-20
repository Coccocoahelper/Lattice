package net.h2020.bots.ai.brain;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Flat PvP arena: no objectives, no bed, no chores. Find the nearest other fighter and fight it, with the same combat
 * stack (stances, aim model, zest tapping) that a real game uses - which is the point of the arena.
 */
public final class ArenaBrain extends BaseBrain implements EmergencyBrain {
    private long nextScan;
    private Player target;

    /** A range drill sets this: the arena brain still finds and closes on the target, but always with the bow. */
    private boolean rangedOnly;
    /** The mob a ranged brain is currently drawing at. */
    private org.bukkit.entity.LivingEntity mobTarget;

    public ArenaBrain rangedOnly() { this.rangedOnly = true; return this; }

    @Override
    protected void onActive() {
        if (rangedOnly) ctx.combat.forceBow = true;
    }

    private long repathAt;
    private org.bukkit.Location roamTo;

    @Override
    protected void think() {
        if (ctx.tick >= nextScan || target == null || !target.isOnline() || target.isDead()) {
            nextScan = ctx.tick + 10;
            target = nearest();
        }
        boolean other = target != null && target.getLocation().getWorld() == ctx.player.getWorld()
                && ctx.pos().distance(target.getLocation()) < 24;
        if (ctx.combat.target() != target) {
            if (other) ctx.combat.engage(target);
            else ctx.combat.disengage();
        }
        if (other) { ctx.nav.stop(); ctx.combat.tick(); return; }
        // No target in range: walk to it instead of standing still. The arena used to leave a bot rooted the moment
        // its opponent died or spawned across the pad, which is most of the "bots barely move" report.
        if (rangedOnly) {
            if (mobTarget != null && !mobTarget.isDead()
                    && mobTarget.getWorld() == ctx.player.getWorld()) {
                ctx.motor.aimEntity(net.h2020.bots.ai.motor.Motor.P_TASK, mobTarget,
                        net.h2020.api.bot.AimIntent.COMBAT);
                ctx.motor.halt();
            } else {
                idleRoam();
            }
            ctx.combat.tick();
            return;
        }
        ctx.combat.tick();
        if (target == null) { idleRoam(); return; }
        boolean sameWorld = target.getLocation().getWorld() == ctx.player.getWorld();
        double d2 = sameWorld ? ctx.pos().distanceSquared(target.getLocation()) : Double.MAX_VALUE;
        if (!sameWorld) { idleRoam(); return; }
        if (ctx.tick >= repathAt || roamTo == null || roamTo.distanceSquared(target.getLocation()) > 9.0) {
            repathAt = ctx.tick + 20;
            roamTo = target.getLocation().clone();
            ctx.nav.goTo(roamTo, 2.0);
        }
        // Always tick: goTo() only sets the goal, and the path is walked by tick(). Guarding this on being close
        // already meant two fighters twenty-eight blocks apart each set a goal and then never moved - the arena
        // reported "bots barely move" and a duel was two statues facing each other for the whole round.
        ctx.nav.tick();
    }

    /** Nothing to chase at all: wander the pad so a duel is never two statues. */
    private void idleRoam() {
        if (ctx.tick < repathAt && ctx.nav.active()) { ctx.nav.tick(); return; }
        repathAt = ctx.tick + 60;
        double a = ctx.rng.nextDouble() * Math.PI * 2;
        double r = 4 + ctx.rng.nextDouble() * 10;
        org.bukkit.Location me = ctx.pos();
        ctx.nav.goTo(new org.bukkit.Location(me.getWorld(), me.getX() + Math.cos(a) * r, me.getY(), me.getZ() + Math.sin(a) * r), 2.0);
        ctx.nav.tick();
    }

    private Player nearest() {
        Player best = null;
        double bd = 1e9;
        World mine = ctx.player.getWorld();
        if (!rangedOnly) {
        for (Player p : mine.getPlayers()) {
            if (p == ctx.player || p.isDead()) continue;
            // getPlayers() on a world can hand back a body whose location still belongs to the world it left (a bot
            // teleported out this tick), and Location.distance throws across two worlds - which killed the ticker and
            // took the whole server with it. Compare in one world or not at all.
            Location l = p.getLocation();
            if (l.getWorld() != mine) continue;
            double d = ctx.pos().distance(l);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
        }
        // ranged: the nearest living thing at all, because a drill's targets are its own mobs
        org.bukkit.entity.LivingEntity lbe = null;
        for (org.bukkit.entity.Entity e : mine.getLivingEntities()) {
            if (!(e instanceof org.bukkit.entity.LivingEntity)) continue;
            if (e instanceof Player) { if (e == ctx.player) continue; }
            if (e.isDead()) continue;
            double d = ctx.pos().distance(e.getLocation());
            if (d < bd) { bd = d; lbe = (org.bukkit.entity.LivingEntity) e; }
        }
        mobTarget = lbe;
        return null;      // the player-wide target is none; mobAim() drives the bow
    }

    @Override
    public String toString() { return "ArenaBrain"; }
}
