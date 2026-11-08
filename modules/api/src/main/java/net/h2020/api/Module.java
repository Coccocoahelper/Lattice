package net.h2020.api;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * A feature module. Enabled in this order by H2020Plugin:
 * core, maps, bots, bedwars, skywars. Disabled in reverse.
 * Register services in enable(); look up other modules' services lazily (at game time), not in enable().
 */
public interface Module {
    String name();
    void enable(JavaPlugin plugin);
    void disable();
}
