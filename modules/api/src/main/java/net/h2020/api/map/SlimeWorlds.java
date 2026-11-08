package net.h2020.api.map;

import org.bukkit.World;

/**
 * Slime-backed world spawning, so a game or a replay can get its own copy of a map instead of sharing one.
 *
 * <p>{@code net.h2020.maps.SlimeWorlds} in the core plugin installs itself here when Continued Slime World Manager is
 * present. Everything that wants a private world goes through this type, which keeps the replay and game modules free
 * of the world manager's own classes.
 */
public final class SlimeWorlds {

    /** Supplies worlds. Names are slime world names (the map's folder, lower case). */
    public interface Spawner {
        /** True when the world manager is loaded and this map has a slime file. */
        boolean has(String mapName);
        /** True when a copy of this map is already built, so {@link #spawn} will answer immediately. */
        boolean ready(String mapName);
        /**
         * A fresh, private, already generated world for this map, or null when one is not ready right now. The caller
         * owns it and must hand it back with {@link #release(World)}.
         */
        World spawn(String mapName, String purpose);
        /** Unload a world handed out by {@link #spawn(String, String)}; nothing is saved. */
        void release(World world);
    }

    private static volatile Spawner spawner;

    private SlimeWorlds() {}

    public static void spawner(Spawner s) { spawner = s; }

    public static boolean available() { return spawner != null; }

    public static boolean has(String mapName) {
        Spawner s = spawner;
        return s != null && mapName != null && s.has(mapName);
    }

    public static boolean ready(String mapName) {
        Spawner s = spawner;
        return s != null && mapName != null && s.ready(mapName);
    }

    public static World spawn(String mapName, String purpose) {
        Spawner s = spawner;
        return s == null ? null : s.spawn(mapName, purpose);
    }

    public static void release(World world) {
        Spawner s = spawner;
        if (s != null && world != null) s.release(world);
    }
}
