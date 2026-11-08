package net.h2020.api.core;

import org.bukkit.World;

/**
 * Names of the worlds that are not games: the lobbies and the practice arena. Registered by whatever owns them, so a
 * plugin can ask "is this the arena" without linking against the arena code.
 */
public final class Worlds {
    private static volatile String arena;

    private Worlds() {}

    public static void arena(String name) { arena = name; }
    public static String arena() { return arena; }

    public static boolean isArena(World w) { return w != null && arena != null && arena.equals(w.getName()); }
}
