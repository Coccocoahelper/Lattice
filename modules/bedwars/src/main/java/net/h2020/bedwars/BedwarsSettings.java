package net.h2020.bedwars;

import net.h2020.api.game.GameType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Module defaults (Hypixel 2020 values) with optional overrides from the "bedwars:" config section, e.g.
 * <pre>
 * bedwars:
 *   min-players: 2        # 0 = Hypixel default (half of max)
 *   countdown: 20
 *   full-countdown: 10
 *   respawn-seconds: 5
 *   end-seconds: 10
 *   timeline-scale: 1.0   # multiply event times (0.1 = 10x faster for testing)
 * </pre>
 */
public final class BedwarsSettings {
    public int minPlayers = 0;
    public int countdown = 20;
    public int fullCountdown = 10;
    public int respawnSeconds = 5;
    public int endSeconds = 10;
    public double timelineScale = 1.0;
    /** How far from its bed a team's island counts as "in base" for heal pool, traps and protection. */
    public int islandRadius = 12;
    /** Training profile: a game that starts almost immediately and runs its timeline compressed. */
    public boolean sprint;

    public void applySprint(boolean on) { sprint = on; }
    public int efCountdown()      { return sprint ? 2 : countdown; }
    public int efFullCountdown()  { return sprint ? 1 : fullCountdown; }
    public int efRespawnSeconds() { return sprint ? 1 : respawnSeconds; }
    public int efEndSeconds()     { return sprint ? 2 : endSeconds; }
    public double efTimelineScale(){ return sprint ? 0.08 : timelineScale; }

    public static BedwarsSettings load(JavaPlugin plugin) {
        BedwarsSettings s = new BedwarsSettings();
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("bedwars");
        if (c != null) {
            s.minPlayers = c.getInt("min-players", s.minPlayers);
            s.countdown = Math.max(1, c.getInt("countdown", s.countdown));
            s.fullCountdown = Math.max(1, c.getInt("full-countdown", s.fullCountdown));
            s.respawnSeconds = Math.max(1, c.getInt("respawn-seconds", s.respawnSeconds));
            s.endSeconds = Math.max(3, c.getInt("end-seconds", s.endSeconds));
            s.timelineScale = Math.max(0.01, c.getDouble("timeline-scale", s.timelineScale));
            s.islandRadius = Math.max(4, c.getInt("island-radius", s.islandRadius));
        }
        return s;
    }

    /** Players needed to start the countdown. Hypixel starts once half the lobby is filled. */
    public int minPlayers(net.h2020.api.game.GameMode type) {
        if (minPlayers > 0) return Math.min(minPlayers, type.maxPlayers());
        return Math.max(2, type.maxPlayers() / 2);
    }
}
