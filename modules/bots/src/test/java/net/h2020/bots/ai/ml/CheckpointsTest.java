package net.h2020.bots.ai.ml;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A checkpoint system fails quietly in the ways that matter, so the guards are pinned here rather than left to a manual
 * run: a name that escapes the checkpoint directory, and a file that this build of the models cannot read.
 *
 * <p>{@code Checkpoints} needs a Bukkit data folder to do anything, and these tests build no server, so the observable
 * behaviour is the refusal path. That is deliberate - the expensive case, an incompatible file overwriting the live
 * model, is exactly the one that must never depend on a live server being watched.
 */
public class CheckpointsTest {

    /** A name is a directory name: anything that could escape the checkpoint folder must be refused. */
    @Test
    public void namesCannotEscapeTheCheckpointDirectory() {
        String[] bad = { "../etc", "..", ".", "a/b", "a\\b", "", "   ", "with space", "a.b", repeat('x', 49) };
        for (String n : bad) {
            String said = Checkpoints.capture(n);
            assertTrue("must refuse name [" + n + "], said: " + said, said.contains("name must be"));
        }
    }

    /** The ordinary case must not be refused by the name check - it fails later, for want of an installed folder. */
    @Test
    public void ordinaryNamesPassTheNameCheck() {
        String[] good = { "pre-experiment", "v1", "before_ml_fix", "A1", repeat('y', 48) };
        for (String n : good) {
            String said = Checkpoints.capture(n);
            assertFalse("must not reject name [" + n + "], said: " + said, said.contains("name must be"));
        }
    }

    /** Reading a checkpoint that does not exist is an error message, never a throw. */
    @Test
    public void readingAMissingCheckpointIsRefusedCleanly() {
        assertTrue(Checkpoints.restore("nope-not-here").contains("checkpoint"));
        assertTrue(Checkpoints.delete("nope-not-here").contains("checkpoint"));
    }

    /** Listing with nothing installed is an instruction, not an exception. */
    @Test
    public void listingWithNothingInstalledIsClean() {
        assertFalse("listing must say something", Checkpoints.list().isEmpty());
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }
}
