package net.h2020.bots.ai.perception;

import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

/**
 * The particle bursts a client actually receives (world particle packets), ring-buffered per tick. The only thing the
 * bots use it for: an invisible player still throws potion particles around his body, so a burst where we already
 * suspect somebody is the visual trace that gives his position away - exactly what a player uses to hunt an invis.
 * No omniscience: a burst only updates a suspect we already have a memory of, and only within the radius a client is
 * sent particles for.
 */
public final class ParticleBus {
    public static final class Burst {
        public final World world;
        public final double x, y, z;
        public final int count;
        public final long tick;

        Burst(World world, double x, double y, double z, int count, long tick) {
            this.world = world; this.x = x; this.y = y; this.z = z; this.count = count; this.tick = tick;
        }
    }

    private static final int CAP = 512;
    private static final Burst[] RING = new Burst[CAP];
    private static int head;
    private static long tick;

    private ParticleBus() {}

    public static void tick() { tick++; }

    public static long now() { return tick; }

    /** A world particle packet (already de-duplicated by the client's packet stream). */
    public static void emit(World w, double x, double y, double z, int count) {
        if (w == null || count <= 0) return;
        RING[head] = new Burst(w, x, y, z, count, tick);
        head = (head + 1) % CAP;
    }

    /** Bursts within `r` of a point (world must match), no older than `sinceTick`. */
    public static List<Burst> near(World w, double x, double y, double z, double r, long sinceTick) {
        List<Burst> out = new ArrayList<Burst>(4);
        double r2 = r * r;
        for (int i = 0; i < CAP; i++) {
            Burst b = RING[i];
            if (b == null || b.world != w || b.tick < sinceTick) continue;
            double dx = b.x - x, dy = b.y - y, dz = b.z - z;
            if (dx * dx + dy * dy + dz * dz <= r2) out.add(b);
        }
        return out;
    }

    public static void clear() {
        for (int i = 0; i < CAP; i++) RING[i] = null;
        head = 0;
    }
}
