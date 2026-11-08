package net.h2020.api.map;

import net.h2020.api.game.GameFamily;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Map;

/**
 * A playable map. Positions are block-relative world coordinates stored as Vectors (x,y,z; may be fractional for
 * spawns), keyed by MapKeys constants. Yaw for spawn-like keys is stored in yaw(key, index).
 */
public interface MapTemplate {
    String name();
    GameFamily family();
    /** Team counts this map supports, e.g. [8] for 8-team bedwars maps, [4] for 4-team, [12] solo skywars. */
    List<Integer> supportedTeamCounts();
    /** All points for a key (empty list if none). */
    List<Vector> points(String key);
    Vector point(String key);
    float yaw(String key, int index);
    Map<String, List<Vector>> allPoints();
    /** Free-form tags from map.yml (e.g. dreams, seasonal, christmas); a disabled tag hides the map. */
    default List<String> tags() { return java.util.Collections.emptyList(); }
    /** Build/void height limits and play bounds. */
    int buildLimitY();
    Vector boundsMin();
    Vector boundsMax();
    /** Copy the template world folder to a fresh instance folder and load it (void generator, no mobs, no weather). */
    World createInstance(String instanceWorldName);
    /** Unload and delete an instance world created by createInstance. */
    void destroyInstance(World world);
}
