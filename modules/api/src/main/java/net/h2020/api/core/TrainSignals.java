package net.h2020.api.core;

/**
 * Hooks a training plugin can attach to: "a bot died", "a bot got a kill". The bot runtime reports them without
 * linking against whatever is listening.
 */
public final class TrainSignals {
    public static volatile Runnable onDeath, onKill;
    private TrainSignals() {}
    public static void death() { Runnable r = onDeath; if (r != null) r.run(); }
    public static void kill() { Runnable r = onKill; if (r != null) r.run(); }
}
