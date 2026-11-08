package net.h2020.api.game;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;

/** One running match in its own world instance. Implemented by the bedwars/skywars modules. */
public interface Game {
    /** Hypixel-style server id shown on the scoreboard, e.g. "mini12A" / "m45C". */
    String id();
    GameMode type();
    GameState state();
    World world();
    String mapName();
    /** Everyone in the game including spectators. */
    List<Player> players();
    /** Still participating (not eliminated / spectating). */
    List<Player> alivePlayers();
    List<? extends GameTeam> teams();
    /** null if not on a team. */
    GameTeam teamOf(Player p);
    boolean isSpectator(Player p);
    /** Join during WAITING/STARTING; false if full or started. Teleports into the pre-game lobby/cage. */
    boolean addPlayer(Player p);
    /** Leave/quit. Handles elimination if playing. Does NOT send to lobby (GameManager does). */
    void removePlayer(Player p);
    /** -1 when no countdown is running. */
    int secondsUntilStart();
    Location spectatorSpawn();
    /** Forcefully end and unload the world. Must call GameManager.finished(this). */
    void shutdown();

    /** Non-null for a private game: join by code, never filled by the queue or by bots. */
    default String privateCode() { return null; }
    /** Turn this game private: a code to join with and an optional player cap (0 = the mode's own). */
    default void makePrivate(String code, int maxPlayers) { }
    /** Player cap for this game: the private override when there is one, otherwise the mode's. */
    default int playerCap() { return type().maxPlayers(); }
}
