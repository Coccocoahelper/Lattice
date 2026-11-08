package net.h2020.bots.ai.brain.bedwars;

import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.brain.BedwarsBrain;
import net.h2020.bots.ai.nav.NavOptions;
import net.h2020.bots.ai.util.AIMath;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Stand on a generator (team forge, diamond or emerald) until enough has been picked up. Items are collected by
 * walking over them; the bot shuffles around on the generator like players do while waiting.
 */
public final class CollectTask extends BwTask {
    public enum Kind { IRON, DIAMOND, EMERALD }

    private final Location gen;
    private final Kind kind;
    private final int target;
    private final long maxTicks;
    private long nextShuffle;
    private Location spot;
    private long arrivedTick = -1;
    /** Standing outside the forge while a teammate takes it (the split technique): the multiplier feeds both. */
    private boolean sharing, shareGaveUp;
    /** One bridger per generator: when a teammate leads the way there, walk behind his bridge. */
    private Player followLeader;
    private long lastAssign = -100;
    private long shareStart = -1;
    private int shareHave;

    public CollectTask(BedwarsBrain brain, Location gen, Kind kind, int target, long maxTicks) {
        super(brain);
        this.gen = gen;
        this.kind = kind;
        this.target = target;
        this.maxTicks = maxTicks;
        this.spot = gen;
    }

    @Override public String name() { return "collect-" + kind.name().toLowerCase(); }

    @Override public org.bukkit.Location goal() { return gen; }

    /** Did the trip actually get the resources it came for? (the macro policy learns from this) */
    public boolean reachedTarget() { return have() >= target; }

    private int have() {
        switch (kind) {
            case DIAMOND: return ShopPlanner.diamonds(ctx.player);
            case EMERALD: return ShopPlanner.emeralds(ctx.player);
            default: return ShopPlanner.iron(ctx.player);
        }
    }

    /** A teammate standing on the forge right now. */
    private org.bukkit.entity.Player mateOnForge() {
        for (org.bukkit.entity.Player p : brain.teammates()) {
            if (p == ctx.player || p.isDead() || p.getWorld() != gen.getWorld()) continue;
            // only somebody actually standing on the forge counts as the picker - a teammate at the split spot
            // (1.9 blocks out) must not count, or both bots stand outside and nobody picks anything up
            if (p.getLocation().distance(gen) < 1.2) return p;
        }
        return null;
    }

    /** 1.9 blocks out from the generator, on our side of it: inside the split radius (2.2), outside item pickup. */
    private Location splitSpot() {
        Location me = ctx.pos();
        double dx = me.getX() - gen.getX(), dz = me.getZ() - gen.getZ();
        double l = Math.sqrt(dx * dx + dz * dz);
        if (l < 1e-3) { dx = 1; dz = 0; l = 1; }
        return gen.clone().add(dx / l * 2.05, 0, dz / l * 2.05);
    }

    @Override
    public boolean tick() {
        if (gen == null) return false;
        // the wait budget starts on the generator: the walk/bridge there has its own (generous) cap
        boolean timeUp = arrivedTick < 0 ? age() > 20 * 75 : ctx.tick - arrivedTick > maxTicks;
        // a human does not stand at the generator until the full stack arrives: once there is enough to build the
        // next thing, leaving is the better play - the rest can be picked up on the way back
        boolean enough = kind == Kind.IRON ? have() >= Math.max(12, target * 3 / 5) : have() >= target;
        if (have() >= target || timeUp || (enough && age() > 20 * 3)) {
            if (have() >= target) net.h2020.api.stats.CombatStats.inc("collect.full");
            else if (enough) net.h2020.api.stats.CombatStats.inc("collect.early");
            ctx.nav.stop();
            return false;
        }
        double d = ctx.pos().distance(spot);
        boolean far = ctx.pos().distance(gen) > 4;
        if (far) {
            // same target = same bridge: the teammate closest to the generator builds it, the rest walk behind him
            if (ctx.tick - lastAssign > 20) {
                lastAssign = ctx.tick;
                TeamPlay.Assignment as = TeamPlay.bridging(brain, gen);
                followLeader = as.role == TeamPlay.Role.FOLLOW ? as.leader : null;
            }
            if (followLeader != null && followLeader.isOnline() && !followLeader.isDead()
                    && followLeader.getWorld() == gen.getWorld()) {
                NavOptions w = NavOptions.walk();
                w.bridge = false;
                w.avoidEnemies = false;
                ctx.nav.follow(followLeader.getLocation(), 2.4, w);
                ctx.nav.tick();
                if (ctx.nav.failed()) { followLeader = null; }
                else return true;
            }
            NavOptions o = kind == Kind.IRON ? NavOptions.walk() : NavOptions.bridging(brain.blocks());
            ctx.nav.goTo(gen, 1.0, o);
            ctx.nav.tick();
            if (ctx.nav.failed()) return false;
            return true;
        }
        if (arrivedTick < 0) arrivedTick = ctx.tick;
        // split: a teammate is on the forge, so take the spot just outside it instead of pushing in - both players are
        // then fed by the generator (that is the whole point of the technique)
        if (kind == Kind.IRON && brain.teamMode() && !shareGaveUp && mateOnForge() != null) {
            if (!sharing) {
                sharing = true;
                shareStart = ctx.tick;
                shareHave = have();
                ctx.chat.callout(ctx.rng.nextBoolean() ? "split" : "ill split", 0.2);
            }
            // the picker's copies are what we are here for: if nothing arrived in 8 s, take the forge ourselves
            if (have() > shareHave || ctx.tick - shareStart > 160) {
                if (have() <= shareHave) shareGaveUp = true;
                sharing = false;
            } else {
                Location out = splitSpot();
                ctx.nav.stop();
                double dd = ctx.pos().distance(out);
                if (dd > 0.6) ctx.motor.moveToward(out.getX() - ctx.pos().getX(), out.getZ() - ctx.pos().getZ());
                else if (ctx.tick % 10 == 0) {
                    // a one-block sidestep every half second: still "at the spot", never a statue
                    ctx.motor.moveToward(Math.cos(ctx.tick * 0.6), Math.sin(ctx.tick * 0.6));
                    net.h2020.api.stats.CombatStats.inc("collect.shuffle");
                }
                return true;
            }
        }
        sharing = false;
        if (ctx.tick >= nextShuffle) {
            spot = gen.clone().add((ctx.rng.nextDouble() - 0.5) * 1.4, 0, (ctx.rng.nextDouble() - 0.5) * 1.4);
            nextShuffle = ctx.tick + AIMath.range(ctx.rng, 12, 32);   // keep shuffling: standing on a generator is how a bot looks switched off
        }
        ctx.nav.stop();
        if (d > 0.45) {
            // small shuffle on the generator: just tap the keys toward the spot
            ctx.motor.moveToward(spot.getX() - ctx.pos().getX(), spot.getZ() - ctx.pos().getZ());
        }
        return true;
    }
}
