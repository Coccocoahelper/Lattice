package net.h2020.api.game;

import org.bukkit.entity.Player;

/** Owned by the bots module: lets the lobby menus offer the replay browser without depending on it. */
public interface ReplayService {
    /** Open the list of recorded games for this player. */
    void openBrowser(Player p);

    /**
     * A line the game itself put in front of its players - a kill, a bed, an announcement. Recorded with the replay so
     * watching a match back shows what was said in it, not only where everybody stood.
     *
     * <p>Defaulted to a no-op so a server without the replay plugin is unaffected: games must not fail to boot because
     * nothing is recording them.
     */
    default void serverLine(org.bukkit.World w, String text) { }

    /** Chat or a shout a player sent during a game, with the speaker's name so the replay can attribute it. */
    default void chatLine(org.bukkit.World w, String speaker, String text, String kind) { }
}
