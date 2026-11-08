package net.h2020.api.core;

import net.h2020.api.game.GameMode;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which mode and map a player last played, so they can ask for the same game again after it ends. Written when a game
 * finishes (not when a player quits mid-game), read by /playagain.
 */
public final class LastGame {
    public static final class Entry {
        public final GameMode mode;
        public final String map;
        Entry(GameMode mode, String map) { this.mode = mode; this.map = map; }
    }

    private static final Map<UUID, Entry> LAST = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> COOLDOWN = new ConcurrentHashMap<>();
    public static final int COOLDOWN_SECONDS = 5;

    private LastGame() {}

    public static void record(Player p, GameMode mode, String map) {
        if (p != null && mode != null) LAST.put(p.getUniqueId(), new Entry(mode, map));
    }

    public static Entry of(Player p) { return p == null ? null : LAST.get(p.getUniqueId()); }

    /** Seconds still on cooldown, 0 if ready. */
    public static int cooldown(Player p) {
        Long at = COOLDOWN.get(p.getUniqueId());
        if (at == null) return 0;
        long left = COOLDOWN_SECONDS - (System.currentTimeMillis() - at) / 1000L;
        return left > 0 ? (int) left : 0;
    }

    public static void stamp(Player p) { COOLDOWN.put(p.getUniqueId(), System.currentTimeMillis()); }
}
