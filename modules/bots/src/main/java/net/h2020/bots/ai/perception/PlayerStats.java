package net.h2020.bots.ai.perception;

import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What players would notice about each other: swing rate (CPS) and hit distances, plus recent Watchdog flags.
 * Used for "hacker"/"wdr" chat reactions and threat estimates.
 */
public final class PlayerStats {
    private static final class Rec {
        final ArrayDeque<Long> swings = new ArrayDeque<>();
        final ArrayDeque<Long> taken = new ArrayDeque<>();
        final ArrayDeque<Long> placed = new ArrayDeque<>();
        org.bukkit.Location lastPlace;
        long lastPlaceAt = -1;
        long lastPickup, lastEat;
        int pickups;
        String lastPickupType, lastEatType;
        final ArrayDeque<Long> hitDealt = new ArrayDeque<>();
        double maxReachRecent;
        long reachTick;
        long lastFlagTick = -100000;
        int flags;
    }

    private static final Map<UUID, Rec> RECS = new ConcurrentHashMap<>();

    private PlayerStats() {}

    private static Rec rec(Player p) {
        Rec r = RECS.get(p.getUniqueId());
        if (r == null) { r = new Rec(); RECS.put(p.getUniqueId(), r); }
        return r;
    }

    public static void swing(Player p) {
        Rec r = rec(p);
        long now = SoundBus.now();
        r.swings.addLast(now);
        while (!r.swings.isEmpty() && now - r.swings.peekFirst() > 40) r.swings.pollFirst();
    }

    /** Swings per second over the last 2 seconds. */
    public static double cps(Player p) {
        Rec r = RECS.get(p.getUniqueId());
        if (r == null) return 0;
        long now = SoundBus.now();
        int n = 0;
        for (Long t : r.swings) if (now - t <= 40) n++;
        return n / 2.0;
    }

    /** victim -> {attacker uuid, tick}: who last hit them, for knock-off attribution. */
    private static final java.util.Map<java.util.UUID, Object[]> LAST_HIT_BY = new java.util.HashMap<java.util.UUID, Object[]>();

    /** Remember the last player who hit this victim (used to credit void knock-offs). */
    public static void hitBy(Player victim, Player attacker) {
        if (victim == null) return;
        LAST_HIT_BY.put(victim.getUniqueId(), new Object[]{attacker == null ? null : attacker.getUniqueId(), SoundBus.now()});
    }

    /** The player who hit this victim within `ticks`, or null. */
    public static Player attackerWithin(Player victim, long ticks) {
        if (victim == null) return null;
        Object[] o = LAST_HIT_BY.get(victim.getUniqueId());
        if (o == null) return null;
        long at = (Long) o[1];
        if (SoundBus.now() - at > ticks) return null;
        return o[0] == null ? null : org.bukkit.Bukkit.getPlayer((java.util.UUID) o[0]);
    }

    public static void hit(Player attacker, double distance) {
        Rec r = rec(attacker);
        long now = SoundBus.now();
        if (now - r.reachTick > 200) r.maxReachRecent = 0;
        r.maxReachRecent = Math.max(r.maxReachRecent, distance);
        r.reachTick = now;
    }

    /** A block was placed by this player (behaviour model: bridging vs placing). */
    public static void placed(Player p) { placed(p, null); }

    /** A block was placed at a known position (used to spot bed-defence building). */
    public static void placed(Player p, org.bukkit.Location at) {
        if (p == null) return;
        Rec r = rec(p);
        if (at != null) { r.lastPlace = at.clone(); r.lastPlaceAt = SoundBus.now(); }
        placedOnly(p);
    }

    /** Where and when the last block went down (null / -1 when never). */
    public static org.bukkit.Location lastPlace(Player p) {
        Rec r = RECS.get(p.getUniqueId());
        return r == null ? null : r.lastPlace;
    }

    public static long lastPlaceTick(Player p) {
        Rec r = RECS.get(p.getUniqueId());
        return r == null ? -1 : r.lastPlaceAt;
    }

    private static void placedOnly(Player p) {
        if (p == null) return;
        Rec r = rec(p);
        r.placed.addLast(SoundBus.now());
        while (!r.placed.isEmpty() && SoundBus.now() - r.placed.peekFirst() > 60) r.placed.pollFirst();
    }

    /** Blocks placed in the last ~3 seconds. */
    public static int recentPlacements(Player p) {
        Rec r = RECS.get(p.getUniqueId());
        if (r == null) return 0;
        long now = SoundBus.now();
        int n = 0;
        for (Long t : r.placed) if (now - t <= 60) n++;
        return n;
    }

    /** The player ate or drank something (food, gapple, potion). */
    public static void ate(Player p, String what) {
        if (p == null) return;
        Rec r = rec(p);
        r.lastEat = SoundBus.now();
        r.lastEatType = what;
    }

    /** Ate in the last ~5 seconds (classical sign of healing). */
    public static boolean recentEat(Player p) {
        Rec r = RECS.get(p.getUniqueId());
        return r != null && SoundBus.now() - r.lastEat <= 100;
    }

    /** An emerald or diamond was picked up (behaviour model: gathering). */
    public static void picked(Player p) {
        if (p == null) return;
        Rec r = rec(p);
        r.lastPickup = SoundBus.now();
        r.pickups++;
        org.bukkit.inventory.ItemStack hand = p.getItemInHand();
        r.lastPickupType = hand == null ? null : hand.getType().name();
    }

    /** What was picked up last (for the classical gathering check). */
    public static String lastPickupType(Player p) {
        Rec r = RECS.get(p.getUniqueId());
        return r == null ? null : r.lastPickupType;
    }

    /** Picked a gem up in the last ~5 seconds. */
    public static boolean recentPickup(Player p) {
        Rec r = RECS.get(p.getUniqueId());
        return r != null && SoundBus.now() - r.lastPickup <= 100;
    }

    /** Damage dealt (behaviour model: fighting). */
    public static void dealtHit(Player p) {
        if (p == null) return;
        Rec r = rec(p);
        r.hitDealt.addLast(SoundBus.now());
        while (!r.hitDealt.isEmpty() && SoundBus.now() - r.hitDealt.peekFirst() > 60) r.hitDealt.pollFirst();
    }

    /** Damage dealt or taken in the last ~3 seconds. */
    public static int recentHits(Player p) {
        Rec r = RECS.get(p.getUniqueId());
        if (r == null) return 0;
        long now = SoundBus.now();
        int n = 0;
        for (Long t : r.hitDealt) if (now - t <= 60) n++;
        for (Long t : r.taken) if (now - t <= 60) n++;
        return n;
    }

    /** Count a hit this player took (for the fight model's "hits taken" variable on human samples). */
    public static void tookHit(Player victim) {
        if (victim == null) return;
        Rec r = rec(victim);
        long now = SoundBus.now();
        r.taken.addLast(now);
        while (!r.taken.isEmpty() && now - r.taken.peekFirst() > 200) r.taken.pollFirst();
    }

    /** Hits taken in the last ~10 seconds. */
    public static int hitsTaken(Player p) {
        Rec r = RECS.get(p.getUniqueId());
        if (r == null) return 0;
        long now = SoundBus.now();
        int n = 0;
        for (Long t : r.taken) if (now - t <= 200) n++;
        return n;
    }

    public static double recentReach(Player p) {
        Rec r = RECS.get(p.getUniqueId());
        if (r == null || SoundBus.now() - r.reachTick > 200) return 0;
        return r.maxReachRecent;
    }

    /*public static void flagged(Player p) {
        Rec r = rec(p);
        r.lastFlagTick = SoundBus.now();
        r.flags++;
    }*/

    /** Looked suspicious lately (Watchdog flags in the last 30s). */
    /*public static boolean suspicious(Player p) {
        Rec r = RECS.get(p.getUniqueId());
        return r != null && SoundBus.now() - r.lastFlagTick < 600 && r.flags >= 3;
    }*/

    public static void forget(Player p) { RECS.remove(p.getUniqueId()); }
}
