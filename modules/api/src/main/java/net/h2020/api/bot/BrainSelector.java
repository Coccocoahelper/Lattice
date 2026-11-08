package net.h2020.api.bot;

import org.bukkit.World;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lets a plugin own the brain for its own kind of world. The bots plugin asks these first, then falls back to its
 * built-in choices (a game's brain, the arena brain, the lobby brain), so a practice server, an event world or a
 * different minigame can ship its own behaviour without patching the bot runtime.
 */
public final class BrainSelector {
    public interface Factory {
        /** Null when this factory does not want the world. */
        BotBrain create(World world);
    }

    private static final List<Factory> FACTORIES = new CopyOnWriteArrayList<>();

    private BrainSelector() {}

    public static void register(Factory f) { if (f != null) FACTORIES.add(f); }

    /** First factory that claims the world, or null. */
    public static BotBrain select(World world) {
        if (world == null) return null;
        for (Factory f : FACTORIES) {
            try {
                BotBrain b = f.create(world);
                if (b != null) return b;
            } catch (Throwable ignored) { }
        }
        return null;
    }
}
