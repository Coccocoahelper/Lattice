package net.h2020.bots.ai.perception;

import net.h2020.bots.ai.BotContext;
import net.h2020.api.stats.CombatStats;
import net.h2020.api.bot.AimIntent;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * What is going on around the bot, in one place.
 * <p>
 * Perception answers "where is the player I already know about". This answers the questions a player asks without
 * thinking: is somebody behind me, how many are there, is that noise at my bed, is a third party walking into this
 * fight. It is cheap (recomputed five times a second) and it only uses what a client can know: sightings, sounds and
 * the tab list.
 */
public final class Awareness {
    private final BotContext ctx;

    private int visible, behind, mates, aliveEnemies;
    private double nearestDist = 99;
    private Player nearest, nearestBehind;
    private long nearestBehindSeen = -1000;
    private long lastSoundTick = -1000;
    private SoundBus.Sound lastSound;
    private boolean bedNoise;
    private long lastLookBack = -1000;
    private long nextScan;

    public Awareness(BotContext ctx) { this.ctx = ctx; }

    public int visible() { return visible; }
    public int aliveEnemies() { return aliveEnemies; }
    public int mates() { return mates; }
    public Player nearest() { return nearest; }
    public double nearestDist() { return nearestDist; }
    public boolean bedNoise() { return bedNoise; }
    /** Two or more enemies inside eight blocks while we are engaged: the fight is about to become somebody's highlight. */
    public boolean thirdParty() { return visible >= 2 && nearestDist < 8; }

    public void tick() {
        if (ctx.tick < nextScan) return;
        nextScan = ctx.tick + 4;
        Location me = ctx.pos();
        float yaw = ctx.c.yaw();
        double lookX = -Math.sin(Math.toRadians(yaw)), lookZ = Math.cos(Math.toRadians(yaw));
        visible = 0; behind = 0;
        nearest = null; nearestBehind = null; nearestDist = 99;
        for (Sighting s : ctx.perception.all()) {
            Player p = s.player;
            if (p == null || !p.isOnline() || p.isDead()) continue;
            Location at = s.estimate(ctx.tick);
            if (at == null || at.getWorld() != me.getWorld()) continue;
            double d = me.distance(at);
            if (d > 30) continue;
            visible++;
            if (d < nearestDist) { nearestDist = d; nearest = p; }
            double dx = at.getX() - me.getX(), dz = at.getZ() - me.getZ();
            double l = Math.max(1e-3, Math.hypot(dx, dz));
            if ((dx / l) * lookX + (dz / l) * lookZ < -0.2) {
                behind++;
                if (d < 18) { nearestBehind = p; nearestBehindSeen = ctx.tick; }
            }
        }
        // sounds: only the ones a client could hear from here, and only the ones that matter
        List<SoundBus.Sound> heard = SoundBus.heard(me, ctx.tick - 20, ctx.player);
        bedNoise = false;
        lastSound = null;
        for (SoundBus.Sound s : heard) {
            if (s.kind != SoundBus.Kind.BREAK && s.kind != SoundBus.Kind.PLACE
                    && s.kind != SoundBus.Kind.EXPLOSION && s.kind != SoundBus.Kind.PEARL) continue;
            lastSound = s;
            lastSoundTick = s.tick;
            if (brainBed() != null && brainBed().getWorld() == s.world
                    && Math.hypot(brainBed().getX() - s.x, brainBed().getZ() - s.z) < 6) {
                bedNoise = true;
            }
        }
        if (bedNoise) CombatStats.inc("aware.bednoise");
        if (visible > 0) CombatStats.inc("aware.seen");
        if (behind > 0) CombatStats.inc("aware.behind");
        lookAround();
    }

    /**
     * A player who hears blocks going down behind them turns their head. This is the whole of "aware of surroundings"
     * in one behaviour: no aim change during a fight, a short glance otherwise, and a counter so it can be measured.
     */
    private void lookAround() {
        if (ctx.combat.engaged() || ctx.tick - lastLookBack < 30) return;
        if (lastSound == null || ctx.tick - lastSoundTick > 20) return;
        if (lastSound.source == ctx.player) return;
        Location me = ctx.pos();
        double dx = lastSound.x - me.getX(), dz = lastSound.z - me.getZ();
        if (Math.hypot(dx, dz) > 16) return;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = ctx.c.pitch();
        ctx.c.aimRotation(yaw, Math.max(-30, Math.min(30, pitch)), AimIntent.GLANCE);
        lastLookBack = ctx.tick;
        CombatStats.inc("aware.lookback");
    }

    /** Our bed, when there is one (null in the arena or a lobby). */
    private Location brainBed() {
        try {
            net.h2020.bots.ai.brain.BedwarsBrain b = (net.h2020.bots.ai.brain.BedwarsBrain)
                    net.h2020.bots.ai.BotAI.brainOf(ctx.player);
            if (b == null || b.color() == null) return null;
            return b.view().bedLocation(b.color());
        } catch (Throwable t) {
            return null;
        }
    }
}
