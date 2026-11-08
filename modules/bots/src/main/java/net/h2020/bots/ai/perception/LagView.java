package net.h2020.bots.ai.perception;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * What a client can actually know about somebody else's position: the version of them from half a round trip back.
 * The server sees every player at the current tick; a client sees the other player's last movement packet, which was
 * produced half of the other player's ping ago, and which reaches us half of our own ping later. So the position a bot
 * may use for a target is the one from (ourPing + theirPing) / 2 ms ago, interpolated between the two server ticks it
 * falls between (clients interpolate the same way - position packets are 20 Hz, the render loop is not).
 * <p>
 * One ring buffer per player, written once per server tick by {@link #tick()} (called from BotAI), read by
 * {@link Perception} and by every decision that uses a target position. {@link #enabled} off restores the old
 * "omniscient" behaviour (/bot settings).
 */
public final class LagView {
    /** 64 ticks = 3.2 s of history; enough for a 400 ms round trip plus jitter. */
    private static final int CAP = 64;

    /** Perceived latency (positions of others delayed by half a round trip). Toggled by /bot settings. */
    public static volatile boolean enabled = true;

    private static final Map<UUID, Track> TRACKS = new HashMap<UUID, Track>();
    private static long tick;

    private LagView() {}

    private static final class Track {
        final long[] ticks = new long[CAP];
        final double[] x = new double[CAP];
        final double[] y = new double[CAP];
        final double[] z = new double[CAP];
        final float[] yaw = new float[CAP];
        int size, head;
        long lastTick = Long.MIN_VALUE;

        void add(long t, Location l) {
            ticks[head] = t;
            x[head] = l.getX();
            y[head] = l.getY();
            z[head] = l.getZ();
            yaw[head] = l.getYaw();
            head = (head + 1) % CAP;
            if (size < CAP) size++;
            lastTick = t;
        }
    }

    /** One server tick: remember where every player is (cheap: 5 writes per player). */
    public static void tick() {
        if (!enabled) return;
        tick++;
        for (World w : Bukkit.getWorlds()) {
            for (Player p : w.getPlayers()) {
                Track t = TRACKS.get(p.getUniqueId());
                if (t == null) { t = new Track(); TRACKS.put(p.getUniqueId(), t); }
                t.add(tick, p.getLocation());
            }
        }
        if (TRACKS.size() > 256 && tick % 200 == 0) prune();
    }

    private static void prune() {
        for (Iterator<Map.Entry<UUID, Track>> it = TRACKS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Track> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !p.isOnline() || tick - e.getValue().lastTick > 200) it.remove();
        }
    }

    public static void forget(UUID id) { TRACKS.remove(id); }

    /** Ping shown in tab for a player (simulated for bots, real for humans). */
    public static int pingOf(Player p) {
        if (p == null) return 0;
        try {
            return Math.max(0, ((CraftPlayer) p).getHandle().ping);
        } catch (Throwable t) {
            return 50;
        }
    }

    /**
     * Where `target` is as far as a client with `viewerPingMs` is concerned right now. Falls back to the live position
     * when there is no history yet (first second of a game, or the feature is off).
     */
    public static Location at(Player target, int viewerPingMs) {
        if (target == null) return null;
        Location live = target.getLocation();
        if (!enabled) return live;
        Track t = TRACKS.get(target.getUniqueId());
        if (t == null || t.size == 0) return live;
        int delayMs = (Math.max(0, viewerPingMs) + pingOf(target)) / 2;
        int delayTicks = delayMs / 50;
        long want = tick - delayTicks;
        if (want >= t.lastTick) return live;                  // nothing newer has been queued yet
        long oldest = t.ticks[(t.head - t.size + CAP) % CAP];
        if (want < oldest) want = oldest;
        // walk back from the newest sample to the newest one at or before `want`, then interpolate toward the sample
        // after it (which is the first one newer than `want`)
        int i = (t.head - 1 + CAP) % CAP;
        for (int n = 0; n < t.size; n++) {
            if (t.ticks[i] <= want) {
                int newer = (i + 1) % CAP;
                double f = 0.0;
                if (n > 0 && t.ticks[newer] > t.ticks[i]) {
                    f = (want - t.ticks[i]) / (double) (t.ticks[newer] - t.ticks[i]);
                    if (f > 1.0) f = 1.0;
                }
                double nx = t.x[i] + (t.x[newer] - t.x[i]) * f;
                double ny = t.y[i] + (t.y[newer] - t.y[i]) * f;
                double nz = t.z[i] + (t.z[newer] - t.z[i]) * f;
                return new Location(target.getWorld(), nx, ny, nz, t.yaw[i], 0f);
            }
            i = (i - 1 + CAP) % CAP;
        }
        return live;
    }

    /** Ticks of history currently held (tests / debug). */
    public static int historyTicks(Player p) {
        Track t = TRACKS.get(p.getUniqueId());
        return t == null ? 0 : t.size;
    }
}
