package net.h2020.api.map;

import net.h2020.api.game.GameFamily;
import net.h2020.api.game.GameType;
import org.bukkit.World;

import java.util.List;

/** Owned by maps. Registered in MapsModule.enable() after templates are generated/loaded. */
public interface MapService {
    List<MapTemplate> templates(GameFamily family);
    /** Templates usable for this type (team count matches). */
    List<MapTemplate> templatesFor(net.h2020.api.game.GameMode type);
    MapTemplate byName(String name);
    /** Re-read every map folder (used after editing a map.yml in place). */
    void reload();
    /** Folder of a map, or null if there is no such map. */
    java.io.File folderOf(String name);
    /** The main lobby world and the per-family lobby worlds (generated Hypixel-style hubs). */
    World mainLobby();
    World familyLobby(GameFamily family);
    /** Template an instance world was created from (null if not an instance). */
    MapTemplate templateOf(World w);
    /** Convert a map's world folder into the slime data source ("all" for every map). -1 when there is no manager. */
    int convertToSlime(String mapName);
    /** Map names that have a slime copy, so games and replays can be given their own world. */
    List<String> slimeMaps();
}
