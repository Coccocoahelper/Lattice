package net.h2020.bots.ai.nav;

/**
 * Movement costs in "walk blocks", taken from Baritone's {@code ActionCosts} and converted from its tick units.
 * <p>
 * Baritone prices every movement by the ticks it takes at 20 TPS and lets A* minimise ticks, which is why its paths
 * look like a player's: a sprint-jump across a gap really is cheaper than walking round, and a fall is priced by the
 * exact number of ticks the fall takes (its table comes from the 1.8 fall physics, not from a guess). The numbers below
 * are those ticks divided by {@code WALK_ONE_BLOCK_COST} (4.633), so one flat walking block is 1.0 and everything else
 * is comparable to it.
 * <p>
 * Source values: walk 20/4.317 = 4.633, sprint 20/5.612 = 3.564, sneak 20/1.3 = 15.385, water 20/2.2 = 9.091,
 * ladder up 20/2.35 = 8.511, ladder down 20/3 = 6.667, jump one block = fall(1.25) - fall(0.25) = 3.163 ticks.
 */
public final class NavCosts {
    private NavCosts() {}

    private static final double WALK_TICKS = 20 / 4.317;      // 4.633
    public static final double SPRINT = (20 / 5.612) / WALK_TICKS;      // 0.769 per block
    public static final double SNEAK = (20 / 1.3) / WALK_TICKS;         // 3.320 per block
    public static final double WATER = (20 / 2.2) / WALK_TICKS;         // 1.962 per block
    public static final double LADDER_UP = (20 / 2.35) / WALK_TICKS;    // 1.837 per block
    public static final double LADDER_DOWN = (20 / 3.0) / WALK_TICKS;   // 1.439 per block
    public static final double JUMP_ONE = (distanceToTicks(1.25) - distanceToTicks(0.25)) / WALK_TICKS; // 0.683
    /** Walking off a block costs a little less than walking one: Baritone's WALK_OFF_BLOCK_COST. */
    public static final double WALK_OFF = (WALK_TICKS * 0.8) / WALK_TICKS;

    /** Ticks to fall n blocks, straight from the 1.8 fall physics (0.98 drag, 3.92 gravity). */
    private static final double[] FALL_TICKS = new double[65];

    static {
        for (int i = 0; i < FALL_TICKS.length; i++) FALL_TICKS[i] = distanceToTicks(i);
    }

    /** Cost of the fall itself, in walk blocks. */
    public static double fall(int blocks) {
        if (blocks <= 0) return 0;
        return FALL_TICKS[Math.min(blocks, FALL_TICKS.length - 1)] / WALK_TICKS;
    }

    /** One half-heart of fall damage is worth this many walk blocks: damage is the thing the path is avoiding. */
    public static final double DAMAGE_PER_HP = 3.0;

    /** Cost of taking fall damage for a drop of n blocks (0 for the three safe ones). */
    public static double fallDamage(int blocks) {
        int hp = (int) Math.max(0, blocks - 3);
        return hp * DAMAGE_PER_HP;
    }

    /** A sprint-jump over `gap` blocks: i sprint blocks plus the jump, as Baritone prices parkour. */
    public static double parkour(int gap) {
        return gap * SPRINT + JUMP_ONE;
    }

    /** Placing a block costs about as much as the swing plus the pause: one third of a block on foot. */
    public static final double PLACE = 0.35;

    /** Breaking one block of the given hardness (ticks to mine) on top of the walk it interrupts. */
    public static double breaking(double mineTicks) {
        return mineTicks / WALK_TICKS;
    }

    private static double velocity(int ticks) {
        return (Math.pow(0.98, ticks) - 1) * -3.92;
    }

    private static double distanceToTicks(double distance) {
        if (distance == 0) return 0;
        double left = distance;
        int ticks = 0;
        while (true) {
            double step = velocity(ticks);
            if (left <= step) return ticks + left / step;
            left -= step;
            ticks++;
        }
    }
}
