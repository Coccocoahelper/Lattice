package net.h2020.core.lobby;

import net.h2020.api.game.GameFamily;
import net.h2020.api.game.GameType;
import net.h2020.core.CoreConfig;
import net.h2020.core.game.GameManagerImpl;
import org.bukkit.Bukkit;

/**
 * Player counts for NPC holograms, menus and scoreboards. Real counts from GameManager by default; with
 * fake-player-counts the values look like a summer-2020 evening on Hypixel (slowly drifting, plus real players).
 */
public final class PlayerCounts {
    private final CoreConfig config;
    private final GameManagerImpl games;

    public PlayerCounts(CoreConfig config, GameManagerImpl games) { this.config = config; this.games = games; }

    private static double drift(long salt) {
        double t = System.currentTimeMillis() / 1000.0;
        return 1.0 + 0.04 * Math.sin(t / 97.0 + salt) + 0.015 * Math.sin(t / 13.0 + salt * 3);
    }

    private static int fakeBase(GameType t) {
        switch (t) {
            case BEDWARS_SOLO: return 9800;
            case BEDWARS_DOUBLES: return 13400;
            case BEDWARS_3V3V3V3: return 6900;
            case BEDWARS_4V4V4V4: return 8700;
            case SKYWARS_SOLO_NORMAL: return 4100;
            case SKYWARS_SOLO_INSANE: return 6300;
            case SKYWARS_TEAMS_NORMAL: return 1200;
            case SKYWARS_TEAMS_INSANE: return 2400;
            default: return 1000;
        }
    }

    public int count(GameType type) {
        int real = games.playing(type);
        if (!config.fakePlayerCounts) return real;
        return (int) (fakeBase(type) * drift(type.ordinal())) + real;
    }

    /** Count for any registered mode, built-in or added at runtime. */
    public int count(net.h2020.api.game.GameMode mode) {
        if (mode instanceof GameType) return count((GameType) mode);
        int real = games.playing(mode.family());
        return config.fakePlayerCounts ? (int) (40 * drift(mode.id().hashCode())) + real : real;
    }

    public int count(GameFamily family) {
        int real = games.playing(family);
        if (!config.fakePlayerCounts) return real;
        int sum = 0;
        for (net.h2020.api.game.GameMode m : net.h2020.api.game.GameModes.of(family)) {
            sum += m instanceof GameType ? fakeBase((GameType) m) : 40;
        }
        // lobbies hold ~8% on top of the in-game players
        return (int) (sum * 1.08 * drift(family.ordinal() + 11)) + real;
    }

    /** "Players" line on the main lobby scoreboard: the whole network. */
    public int network() {
        int real = Bukkit.getOnlinePlayers().size();
        if (!config.fakePlayerCounts) return real;
        return (int) (98500 * drift(42)) + real;
    }

    /** Count shown for games this server does not run (Game Menu decoration). */
    public int decorative(int base, int salt) {
        if (!config.fakePlayerCounts) return 0;
        return (int) (base * drift(salt));
    }
}
