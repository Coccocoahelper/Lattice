package net.h2020.bots.ai.motor;

/**
 * Bridging switches, read live every tick so a change takes effect on the next block rather than at the next restart.
 * <p>
 * {@code diagonalStance} selects {@link DiagonalBridge} (back-down-the-bridge stance, S-key travel, two cells per
 * stance) for true diagonals. Off by default: that stance is still being brought up, and the straight bridge's
 * proven diagonal handling is what the bots use until this is turned on.
 */
public final class BridgeTuning {
    private BridgeTuning() {}

    public static volatile boolean diagonalStance = false;

    /**
     * SHIFT ("sneak") and SPEED bridging stay in the code but stop being selected: they are the weakest of the techs
     * (a held shift is slow, and the straight-line speed bridge has no answer to being knocked off). Flip this to true
     * to bring them back for testing; nothing else changes.
     */
    public static volatile boolean shiftSpeedEnabled = false;

    /** While a diagonal stance keeps failing, diagonals go back to the straight bridge instead of retrying forever. */
    private static volatile long diagonalBlockedUntil = 0;
    private static volatile int diagonalFails = 0;
    private static volatile int parkStep = 0;
    private static final long[] PARK_MS = { 60_000L, 300_000L, 900_000L, Long.MAX_VALUE };

    public static boolean diagonalAllowed() {
        return diagonalStance && System.currentTimeMillis() >= diagonalBlockedUntil;
    }

    /** Two failures in a row parks the stance for a minute: a bad stance must not cost a whole game of bridging. */
    public static void noteDiagonalFailure() {
        if (++diagonalFails >= 2) {
            long park = PARK_MS[Math.min(parkStep, PARK_MS.length - 1)];
            diagonalBlockedUntil = park == Long.MAX_VALUE ? Long.MAX_VALUE : System.currentTimeMillis() + park;
            if (parkStep < PARK_MS.length - 1) parkStep++;
            diagonalFails = 0;
        }
    }

    public static void noteDiagonalSuccess() { diagonalFails = 0; parkStep = 0; diagonalBlockedUntil = 0; }

    /** Operator action (the /bot diag switch): clears any parked stance so it gets a fresh run. */
    public static void resetParked() { diagonalFails = 0; parkStep = 0; diagonalBlockedUntil = 0; }
}
