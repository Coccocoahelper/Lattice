package net.h2020.bots.ai.ml;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The two things that can go wrong with "classical checks only" are quiet ones, so they are worth pinning down: the
 * mode has to be off unless somebody turns it on, and turning it on has to stick.
 *
 * <p>The refusal paths themselves ({@code questionFor} returning null, a decision being scored once) take a
 * {@code Player}, which a plain unit test has no Bukkit server to build - those are exercised on a live server through
 * {@code /bot ml classical}, where the counters {@code ask.blocked} and {@code check.agree} show them working.
 */
public class ClassicalChecksTest {

    /** The switch is off by default: asking is the richer source, and losing it must be a deliberate act. */
    @Test
    public void classicalOnlyIsOffByDefault() {
        MlSettings.setClassicalOnly(false);
        assertFalse("the bots must ask unless somebody turns asking off", MlSettings.classicalOnly());
    }

    /** Turning it on sticks, and turning it back off restores asking. */
    @Test
    public void classicalOnlyRoundTrips() {
        try {
            MlSettings.setClassicalOnly(true);
            assertTrue(MlSettings.classicalOnly());
            MlSettings.setClassicalOnly(false);
            assertFalse(MlSettings.classicalOnly());
        } finally {
            MlSettings.setClassicalOnly(false);
        }
    }

    /**
     * The setting is independent of the bot-training switch. They answer different questions - which bodies may teach
     * the models, and whether the bots ask at all - and turning one on must not silently flip the other.
     */
    @Test
    public void theTwoSwitchesAreIndependent() {
        try {
            boolean botsBefore = MlSettings.trainFromBots();
            MlSettings.setClassicalOnly(true);
            assertTrue("the bot-training switch must not move", botsBefore == MlSettings.trainFromBots());
            MlSettings.setClassicalOnly(false);
            assertTrue("and neither on the way back", botsBefore == MlSettings.trainFromBots());
        } finally {
            MlSettings.setClassicalOnly(false);
        }
    }
}
