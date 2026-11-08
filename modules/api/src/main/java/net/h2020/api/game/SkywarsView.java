package net.h2020.api.game;

import org.bukkit.Location;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;

import java.util.List;

/** Skywars-specific read surface. The skywars Game implementation implements this; the bot AI consumes it. */
public interface SkywarsView extends Game {
    /** Island index the player spawned on, -1 if none. */
    int islandOf(Player p);
    int islandCount();
    Location cage(int island);
    List<Location> islandChests(int island);
    List<Location> midChests();
    Location mid();
    /** Island bounds center (for "go to the next island" decisions). */
    Location islandCenter(int island);
    boolean cagesOpen();
    /** Seconds until next chest refill (-1 if none left). */
    int refillSeconds();
    /** Has this chest been opened by anyone since the last refill (for bot looting memory). */
    boolean chestLooted(Chest chest);
}
