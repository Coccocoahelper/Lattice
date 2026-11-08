package net.h2020.bots.ai.nav;

import java.util.Collections;
import java.util.List;

/** Result of a search. partial = goal not reached (best effort towards it, e.g. node budget exhausted). */
public final class Path {
    public final List<PathPoint> points;
    public final boolean partial;
    public final int blocksNeeded;
    public final double cost;

    public Path(List<PathPoint> points, boolean partial, int blocksNeeded, double cost) {
        this.points = Collections.unmodifiableList(points);
        this.partial = partial; this.blocksNeeded = blocksNeeded; this.cost = cost;
    }

    public PathPoint last() { return points.isEmpty() ? null : points.get(points.size() - 1); }
    public int size() { return points.size(); }
    public boolean isEmpty() { return points.isEmpty(); }
}
