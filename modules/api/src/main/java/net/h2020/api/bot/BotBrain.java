package net.h2020.api.bot;

/** Decision layer. tick() is called every server tick (main thread) BEFORE the body simulates movement. */
public interface BotBrain {
    void start(Bot bot);
    void tick(Bot bot);
    void stop(Bot bot);
}
