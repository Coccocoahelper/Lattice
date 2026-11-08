package net.h2020.bots.ai.nav;

/** Action edge of the navigation graph: how the bot gets INTO the node that carries this type. */
public enum MoveType {
    START,
    /** Walk/sprint, including stepping up slabs/stairs (<= 0.6). */
    WALK,
    /** Jump up one block (<= 1.25). */
    JUMP_UP,
    /** Walk off an edge and fall (fall-damage aware). */
    DROP,
    /** Sprint-jump across a 1-3 block gap. */
    PARKOUR,
    /** Climb a ladder/vine up or down. */
    LADDER,
    /** Swim through water. */
    SWIM,
    /** Place a block under the next cell (bridging over void). */
    BRIDGE,
    /** Jump and place a block under yourself (one up). */
    PILLAR,
    /** Break the blocks occupying the next cell first. */
    BREAK
}
