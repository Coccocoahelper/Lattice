package net.h2020.api.game;

import org.bukkit.World;

import java.util.HashMap;
import java.util.Map;

/**
 * Which blocks of a world are player-placed, for whatever asks (the pathfinder, the block tactics). Registered by the
 * Bed Wars game of that world; anything else (SkyWars, lobbies) has no registry and everything stays breakable, which
 * is what those worlds actually are.
 */
public final class PlacedRegistry {
    private static final Map<World, BedwarsView> VIEWS = new HashMap<World, BedwarsView>();

    private PlacedRegistry() {}

    public static void register(World w, BedwarsView v) { if (w != null && v != null) VIEWS.put(w, v); }

    public static void unregister(World w) { if (w != null) VIEWS.remove(w); }

    /** True when the block may be broken: player-placed in a Bed Wars world, anything elsewhere. */
    public static boolean isPlaced(World w, int x, int y, int z) {
        BedwarsView v = VIEWS.get(w);
        if (v == null) return true;
        return v.isPlaced(new org.bukkit.Location(w, x, y, z));
    }

    public static boolean managed(World w) { return VIEWS.containsKey(w); }
}
