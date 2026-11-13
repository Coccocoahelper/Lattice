package net.h2020.api.bot;

/** A do-nothing brain, used as the default when a server does not provide the one that was asked for. */
public final class BrainFactory {
    private BrainFactory() { }

    public static BotBrain none() {
        return new BotBrain() {
            @Override public void start(Bot bot) { }
            @Override public void tick(Bot bot) { }
            @Override public void stop(Bot bot) { }
        };
    }
}
