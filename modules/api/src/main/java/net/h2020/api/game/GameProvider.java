package net.h2020.api.game;

import net.h2020.api.map.MapTemplate;

/** Registered by the bedwars and skywars modules into GameManager during enable(). */
public interface GameProvider {
    GameFamily family();
    /** Create a new game instance (world copied from the template). Called on the main thread. */
    Game create(GameMode type, MapTemplate map, String gameId);
}
