package net.h2020.bots.ai.nav;

import org.bukkit.util.Vector;

/** A waypoint of a finished path: feet cell + standing position + the action to get here. */
public final class PathPoint {
    public final int bx, by, bz;
    public final double x, y, z;
    public final MoveType move;
    /** Edge danger at this point (void sides / lava), used to slow down and shrink look-ahead. */
    public final int danger;

    public PathPoint(int bx, int by, int bz, double stand, MoveType move, int danger) {
        this.bx = bx; this.by = by; this.bz = bz;
        this.x = bx + 0.5; this.y = stand; this.z = bz + 0.5;
        this.move = move; this.danger = danger;
    }

    public Vector vec() { return new Vector(x, y, z); }

    public double hDistSq(double px, double pz) {
        double dx = x - px, dz = z - pz;
        return dx * dx + dz * dz;
    }

    public boolean special() { return move != MoveType.WALK && move != MoveType.START; }

    @Override public String toString() { return move + "(" + bx + "," + by + "," + bz + ")"; }
}
