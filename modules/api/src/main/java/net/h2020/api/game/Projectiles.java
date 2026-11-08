package net.h2020.api.game;

/**
 * Flight speeds of the custom projectiles, in blocks per tick (20 ticks per second). Values are what the real server
 * does, so the bots' leads and the item behaviour match the footage:
 * <ul>
 * <li>Bed Wars fireballs fly in a straight line at a constant ~26.9 blocks/s (community motion-tracking measurement of
 *   Hypixel: 26.872 blocks/s). Vanilla 1.8 fireballs accelerate 0.1/tick on top of their launch speed, which is what
 *   makes the stock ones run away from the player; the game keeps this one constant instead.</li>
 * </ul>
 */
public final class Projectiles {
    /** 26.872 blocks/s / 20 = 1.3436 blocks per tick. */
    public static final double FIREBALL = 26.872 / 20.0;

    private Projectiles() {}
}
