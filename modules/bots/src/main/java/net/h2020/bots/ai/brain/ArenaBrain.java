package net.h2020.bots.ai.brain;

import org.bukkit.entity.Player;

/**
 * Flat PvP arena: no objectives, no bed, no chores. Find the nearest other fighter and fight it, with the same combat
 * stack (stances, aim model, zest tapping) that a real game uses - which is the point of the arena.
 */
public final class ArenaBrain extends BaseBrain {
    private long nextScan;
    private Player target;

    @Override
    protected void onActive() { }

    private long repathAt;
    private org.bukkit.Location roamTo;

    @Override
    protected void think() {
        if (ctx.tick >= nextScan || target == null || !target.isOnline() || target.isDead()) {
            nextScan = ctx.tick + 10;
            target = nearest();
        }
        boolean other = target != null && target.getWorld() == ctx.player.getWorld()
                && ctx.pos().distance(target.getLocation()) < 24;
        if (ctx.combat.target() != target) {
            if (other) ctx.combat.engage(target);
            else ctx.combat.disengage();
        }
        if (other) { ctx.nav.stop(); ctx.combat.tick(); return; }
        // No target in range: walk to it instead of standing still. The arena used to leave a bot rooted the moment
        // its opponent died or spawned across the pad, which is most of the "bots barely move" report.
        ctx.combat.tick();
        if (target == null) { idleRoam(); return; }
        double d2 = ctx.pos().distanceSquared(target.getLocation());
        if (ctx.tick >= repathAt || roamTo == null || roamTo.distanceSquared(target.getLocation()) > 9.0) {
            repathAt = ctx.tick + 20;
            roamTo = target.getLocation().clone();
            ctx.nav.goTo(roamTo, 2.0);
        }
        if (d2 < 6.0 * 6.0) ctx.nav.tick();
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
        for (Player p : ctx.player.getWorld().getPlayers()) {
            if (p == ctx.player || p.isDead()) continue;
            double d = ctx.pos().distance(p.getLocation());
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    @Override
    public String toString() { return "ArenaBrain"; }
}
