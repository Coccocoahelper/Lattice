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
        ctx.combat.tick();
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
