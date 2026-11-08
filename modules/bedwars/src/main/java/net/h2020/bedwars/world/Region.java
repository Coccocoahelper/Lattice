package net.h2020.bedwars.world;

import org.bukkit.Location;

/**
 * A box in the arena with rules attached, the way BedWars2023 models its protection: one object that answers "is this
 * inside?" and "may I build / fight here?" instead of a dozen radius checks scattered through the listeners.
 */
public final class Region {
    public final String name;
    private final double minX, minY, minZ, maxX, maxY, maxZ;
    /** May blocks be placed or broken inside? False for spawns, forges, shops and generators. */
    public final boolean build;
    /** Is PvP allowed inside? True for the whole arena today; the flag exists so a spawn region can protect later. */
    public final boolean pvp;

    private Region(String name, double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                   boolean build, boolean pvp) {
        this.name = name;
        this.minX = minX; this.minY = minY; this.minZ = minZ;
        this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        this.build = build; this.pvp = pvp;
    }

    /** Axis-aligned box around a point: r blocks out horizontally, dyMin..dyMax vertically. */
    public static Region around(String name, Location c, double r, double dyMin, double dyMax, boolean build,
                                boolean pvp) {
        return new Region(name, c.getX() - r, c.getY() + dyMin, c.getZ() - r,
                c.getX() + r, c.getY() + dyMax, c.getZ() + r, build, pvp);
    }

    public boolean contains(Location l) {
        if (l == null) return false;
        return l.getX() >= minX && l.getX() <= maxX && l.getY() >= minY && l.getY() <= maxY
                && l.getZ() >= minZ && l.getZ() <= maxZ;
    }

    @Override public String toString() { return name + "(" + (build ? "build" : "protected") + ")"; }
}
