package net.h2020.api.map;

import net.h2020.api.game.TeamColor;

/** Point keys used by MapTemplate. Team-scoped keys use the lowercase TeamColor name, e.g. "team.red.spawn". */
public final class MapKeys {
    private MapKeys() {}

    // shared
    public static final String WAITING_LOBBY = "waiting.lobby";     // pre-game lobby spawn (bedwars glass box / skywars hub above map)
    public static final String SPECTATOR = "spectator";
    public static final String MID = "mid";                          // center of the map

    // bedwars (per team)
    public static String teamSpawn(TeamColor c) { return "team." + c.name().toLowerCase() + ".spawn"; }
    /** two points: bed foot then bed head */
    public static String teamBed(TeamColor c) { return "team." + c.name().toLowerCase() + ".bed"; }
    public static String teamGenerator(TeamColor c) { return "team." + c.name().toLowerCase() + ".generator"; }
    public static String teamShop(TeamColor c) { return "team." + c.name().toLowerCase() + ".shop"; }
    public static String teamUpgrades(TeamColor c) { return "team." + c.name().toLowerCase() + ".upgrades"; }
    /** bounding box (2 points min,max) of a team's base island, used for heal pool / traps / "base" awareness. */
    public static String teamBase(TeamColor c) { return "team." + c.name().toLowerCase() + ".base"; }
    public static final String DIAMOND_GEN = "gen.diamond";
    public static final String EMERALD_GEN = "gen.emerald";

    // skywars (island index 0..n-1)
    public static String islandCage(int i) { return "island." + i + ".cage"; }
    public static String islandChests(int i) { return "island." + i + ".chests"; }
    /** bounding box (2 points min,max) of island i */
    public static String islandBounds(int i) { return "island." + i + ".bounds"; }
    public static final String MID_CHESTS = "mid.chests";
    public static final String ISLAND_COUNT = "island.count";        // not a point: use MapTemplate.points(...).size() of cages
}
