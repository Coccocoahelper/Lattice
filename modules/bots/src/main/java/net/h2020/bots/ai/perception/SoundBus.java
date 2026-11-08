package net.h2020.bots.ai.perception;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Recent audible events (block placing, bow shots, hits, eating, explosions), fed by the AI listener. Bots poll
 * it during their perception scan; footsteps are synthesized there from players' movement instead.
 */
public final class SoundBus {
    public enum Kind {
        PLACE(16), BREAK(16), BOW(26), HIT(18), EAT(8), EXPLOSION(40), PEARL(20), CHEST(10);
        public final double radius;
        Kind(double r) { this.radius = r; }
    }

    public static final class Sound {
        public final Kind kind;
        public final World world;
        public final double x, y, z;
        public final Player source;
        public final long tick;

        Sound(Kind kind, Location l, Player source, long tick) {
            this.kind = kind; this.world = l.getWorld(); this.x = l.getX(); this.y = l.getY(); this.z = l.getZ();
            this.source = source; this.tick = tick;
        }
    }

    private static final int CAP = 128;
    private static final Sound[] RING = new Sound[CAP];
    private static int head;
    private static long tick;

    private SoundBus() {}

    public static void tick() { tick++; }
    public static long now() { return tick; }

    public static void emit(Kind kind, Location at, Player source) {
        if (at == null || at.getWorld() == null) return;
        RING[head] = new Sound(kind, at, source, tick);
        head = (head + 1) % CAP;
    }

    /** Sounds since `sinceTick` audible at the given position. */
    public static List<Sound> heard(Location ear, long sinceTick, Player self) {
        List<Sound> out = new ArrayList<>(4);
        World w = ear.getWorld();
        for (int i = 0; i < CAP; i++) {
            Sound s = RING[i];
            if (s == null || s.tick <= sinceTick || s.world != w || s.source == self) continue;
            double dx = s.x - ear.getX(), dy = s.y - ear.getY(), dz = s.z - ear.getZ();
            double r = s.kind.radius;
            if (dx * dx + dy * dy * 2 + dz * dz <= r * r) out.add(s);
        }
        return out;
    }

    public static void clear() {
        for (int i = 0; i < CAP; i++) RING[i] = null;
    }
}
