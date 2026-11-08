package net.h2020.skywars.game;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;

/** One spawn island of a SkyWars instance (locations in the instance world). */
final class Island {
    final int index;
    /** Feet position at the cage (block-centred, with the template yaw). */
    final Location spawn;
    final List<Location> chests = new ArrayList<>();
    final Location center;
    Cage cage;

    Island(int index, Location spawn, Location center) {
        this.index = index; this.spawn = spawn; this.center = center;
    }

    /** Spawn position of the n-th member inside the (2x2 for teams) cage. */
    Location memberSpawn(int slot) {
        Location l = spawn.clone();
        if (slot == 1) l.add(1, 0, 1);
        else if (slot == 2) l.add(1, 0, 0);
        else if (slot == 3) l.add(0, 0, 1);
        return l;
    }
}
