package net.h2020.api.game;

/** Hypixel 2020 queue modes. id() matches Hypixel's /play ids. */
public enum GameType implements GameMode {
    /**
     * 1v1 on a solo map: eight islands exist (that is what the map was built for) but only RED and AQUA are used, so
     * the two players start on opposite corners of the ring with the rest of the map as the middle ground.
     */
    BEDWARS_DUEL(GameFamily.BEDWARS, "bedwars_duel", "Duel (1v1)", 1, 8),
    BEDWARS_SOLO(GameFamily.BEDWARS, "bedwars_eight_one", "Solo", 1, 8),
    BEDWARS_DOUBLES(GameFamily.BEDWARS, "bedwars_eight_two", "Doubles", 2, 8),
    BEDWARS_3V3V3V3(GameFamily.BEDWARS, "bedwars_four_three", "3v3v3v3", 3, 4),
    BEDWARS_4V4V4V4(GameFamily.BEDWARS, "bedwars_four_four", "4v4v4v4", 4, 4),
    SKYWARS_SOLO_NORMAL(GameFamily.SKYWARS, "solo_normal", "Solo Normal", 1, 12),
    SKYWARS_SOLO_INSANE(GameFamily.SKYWARS, "solo_insane", "Solo Insane", 1, 12),
    SKYWARS_TEAMS_NORMAL(GameFamily.SKYWARS, "teams_normal", "Teams Normal", 2, 8),
    SKYWARS_TEAMS_INSANE(GameFamily.SKYWARS, "teams_insane", "Teams Insane", 2, 8);

    public final GameFamily family;
    private final String id;
    public final String displayName;
    public final int teamSize;
    public final int teams;

    GameType(GameFamily family, String id, String displayName, int teamSize, int teams) {
        this.family = family; this.id = id; this.displayName = displayName; this.teamSize = teamSize; this.teams = teams;
    }

    // ---- GameMode presentation (menus are generated from these, not from a hard-coded list) ----
    @Override public GameFamily family() { return family; }
    @Override public String displayName() { return displayName; }
    @Override public int teamSize() { return teamSize; }
    @Override public int teams() { return teams; }

    @Override public org.bukkit.Material icon() {
        return family == GameFamily.BEDWARS ? org.bukkit.Material.BED : org.bukkit.Material.EYE_OF_ENDER;
    }

    @Override public short iconData() {
        switch (this) {
            case BEDWARS_DOUBLES: return 2;
            case BEDWARS_3V3V3V3: return 3;
            case BEDWARS_4V4V4V4: return 4;
            case BEDWARS_DUEL: return 0;
            case SKYWARS_SOLO_INSANE:
            case SKYWARS_TEAMS_INSANE: return 1;
            default: return 0;
        }
    }

    @Override public String menuSubtitle() {
        switch (this) {
            case BEDWARS_DUEL: return "Duel";
            case BEDWARS_SOLO: return "Solo";
            case BEDWARS_DOUBLES: return "Doubles";
            case BEDWARS_3V3V3V3: return "3v3v3v3";
            case BEDWARS_4V4V4V4: return "4v4v4v4";
            default: return displayName;
        }
    }

    @Override public String description() {
        switch (this) {
            case BEDWARS_DUEL: return "Two players, opposite islands, RED against AQUA. Played on the solo maps: the ring between you stays open for emeralds and diamonds.";
            case BEDWARS_SOLO: return "Play Bed Wars by yourself. 8 players, 8 teams, 1 winner.";
            case BEDWARS_DOUBLES: return "Team up with a friend or a random player. 16 players, 8 teams of 2.";
            case BEDWARS_3V3V3V3: return "Four teams of three fight for the last bed standing.";
            case BEDWARS_4V4V4V4: return "Four teams of four. Bigger maps, bigger fights.";
            case SKYWARS_SOLO_NORMAL: return "Normal mode: standard loot, 12 players, every player for themselves.";
            case SKYWARS_SOLO_INSANE: return "Insane mode: overpowered loot and enchantments, 12 players.";
            case SKYWARS_TEAMS_NORMAL: return "Team up with a partner on your island. 8 teams of 2, standard loot.";
            case SKYWARS_TEAMS_INSANE: return "Teams of 2 with overpowered loot. 8 teams, 1 winner.";
            default: return "";
        }
    }

    @Override public int menuOrder() {
        switch (this) {
            case BEDWARS_DUEL: return 1;
            case BEDWARS_SOLO: return 2;
            case BEDWARS_DOUBLES: return 3;
            case BEDWARS_3V3V3V3: return 4;
            case BEDWARS_4V4V4V4: return 5;
            case SKYWARS_SOLO_NORMAL: return 1;
            case SKYWARS_SOLO_INSANE: return 2;
            case SKYWARS_TEAMS_NORMAL: return 3;
            case SKYWARS_TEAMS_INSANE: return 4;
            default: return 50;
        }
    }

    public String id() { return id; }
    /** A duel fields exactly two players even though its map has eight islands. */
    public int maxPlayers() { return this == BEDWARS_DUEL ? 2 : teamSize * teams; }
    public boolean insane() { return this == SKYWARS_SOLO_INSANE || this == SKYWARS_TEAMS_INSANE; }

    public static GameType byId(String id) {
        for (GameType t : values()) if (t.id.equalsIgnoreCase(id) || t.name().equalsIgnoreCase(id)) return t;
        return null;
    }
}
