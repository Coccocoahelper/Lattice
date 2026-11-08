package net.h2020.bots.ai.nav;

/** A* search node: feet cell (x,y,z) plus the exact standing height. */
final class PathNode {
    final int x, y, z;
    final long key;
    double stand;
    double g, h, f;
    PathNode parent;
    MoveType move = MoveType.START;
    /** Blocks placed along the best route to here (bridge/pillar). */
    int blocksUsed;
    /** Direction of the bridge edge that created this node (for turn penalties), 0..3 or -1. */
    int bridgeDir = -1;
    /** Floor is virtual (a block we'd place). */
    boolean virtualFloor;
    int heapIndex = -1;
    boolean closed;

    PathNode(int x, int y, int z, long key) { this.x = x; this.y = y; this.z = z; this.key = key; }
}
