package net.h2020.api.practice;

import org.bukkit.World;

/**
 * A way for anything outside the practice plugin to ask for a drill's arena again.
 *
 * <p>Practice arenas are generated, not maps: there is no template to look up, so the replay viewer could not find a
 * world for a practice recording and refused to play it back ("no maps available"). This hook lets the practice module
 * hand back a fresh arena for a mode id, which is enough to stand the recorded players in.
 */
public final class PracticeArenas {

    /** Builds an arena for a mode id ({@code practice_beddefence}); null when this server has no such drill. */
    public interface Factory { World create(String modeId); }

    private static Factory factory;

    private PracticeArenas() { }

    public static void install(Factory f) { factory = f; }

    public static boolean available() { return factory != null; }

    public static World spawn(String modeId) {
        return factory == null || modeId == null ? null : factory.create(modeId);
    }
}
