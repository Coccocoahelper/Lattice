package net.h2020.api.game;

import org.bukkit.entity.Player;

import java.util.List;

public interface GameTeam {
    TeamColor color();
    /** Members still in the game (including dead/respawning). */
    List<Player> members();
    /** Still in contention (bedwars: bed alive or a member alive). */
    boolean alive();
}
