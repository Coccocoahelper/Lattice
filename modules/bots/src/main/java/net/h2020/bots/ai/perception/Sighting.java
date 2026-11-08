package net.h2020.bots.ai.perception;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/** Memory of one other player: detection meter, last known state, threat. */
public final class Sighting {
    public final Player player;
    /** Detection meter 0..1+; crossing 1 (after reaction time) makes the player "known". */
    public double meter;
    public boolean known;
    public long noticeAt = -1;
    /** In the vision cone with clear line of sight on the latest scan. */
    public boolean visible;
    /** Drinking or splashing invisibility right now: only particles / armour give the position away. */
    public boolean invisible;
    public Location lastPos;
    public Vector lastVel = new Vector();
    public long lastSeenTick = -10000;
    public long lastHeardTick = -10000;
    public long lastHurtMeTick = -10000;
    public long lastHitByMeTick = -10000;
    public int hitsOnMe;
    public boolean ally;
    public double threat;
    public double opportunity;
    /** Gear as last seen (armour is visible on the body, the weapon in the hand): the best of what we saw. */
    public double armorSeen, weaponSeen = 1, healthSeen = 20;
    public boolean bowSeen;
    public long gearTick = -10000;
    /** Last time they stood (not bridging) inside their own spawn area / were seen over the void. */
    public long overVoidTick = -10000;
    /** Raw position on the previous scan (seen or not) for footstep loudness. */
    Location scanPos;
    long scanTick;
    double scanSpeed;

    Sighting(Player p) { this.player = p; }

    /**
     * Rough fighting strength from what we saw: armour points, weapon, health (0..~40). Compare with
     * {@link Perception#selfPower()}.
     */
    public double power() {
        return armorSeen * 1.1 + (weaponSeen - 1) * 1.6 + healthSeen * 0.6;
    }

    /** Seconds since last direct sight. */
    public double unseenSeconds(long now) { return (now - lastSeenTick) / 20.0; }

    /** 1 while visible, decaying to 0 over ~15s of not seeing/hearing them. */
    public double confidence(long now) {
        long last = Math.max(lastSeenTick, Math.max(lastHeardTick, lastHurtMeTick));
        double dt = (now - last) / 20.0;
        if (dt <= 0) return 1;
        return Math.exp(-dt / 7.0);
    }

    /**
     * Where we think they are now. For somebody who is invisible the memory is trusted further (game sense: he keeps
     * walking where he was going), for a visible player only a brief extrapolation is honest.
     */
    public Location estimate(long now) {
        if (lastPos == null) return null;
        double dt = Math.min(invisible ? 70 : 30, now - lastSeenTick);
        return lastPos.clone().add(lastVel.clone().multiply(dt * (invisible ? 0.85 : 0.6)));
    }
}
