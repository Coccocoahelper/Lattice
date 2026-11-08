package net.h2020.api.game;

import org.bukkit.entity.Player;

/** Owned by the bots module: lets the lobby menus offer the replay browser without depending on it. */
public interface ReplayService {
    /** Open the list of recorded games for this player. */
    void openBrowser(Player p);
}
