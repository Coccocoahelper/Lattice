package net.h2020.maps.gen;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spawn and NPC positions of the three hub worlds, taken from Swofty's Hypixel recreation.
 *
 * <p>The numbers are the verbatim {@code new Pos(x, y, z, yaw, pitch)} values from that project's lobby loaders
 * ({@code TypeMainLobbyLoader}, {@code TypeBedWarsLobbyLoader}, {@code TypeSkyWarsLobbyLoader}) and its NPC classes.
 * They are written to {@code lobby-npcs.yml}, which the lobby reads for its spawn and NPC pads.
 *
 * <p>Positions are feet positions: the y is where the NPC's feet are, so the block below is solid. A few of them were
 * checked against the map files themselves - the skywars NPCs stand on a carpet at y=92 with their feet at 93, the
 * bedwars spawn has stone at 71 and air at 72 - which is how these were confirmed to belong to the maps we ship.
 */
public final class SwoftyLobbies {
    public static final String MAIN = "swofty_lobby_main";
    public static final String BEDWARS = "swofty_lobby_bedwars";
    public static final String SKYWARS = "swofty_lobby_skywars";

    private SwoftyLobbies() { }

    /** {x, y, z, yaw} of the hub spawn, or null for a world this class does not describe. */
    public static double[] spawn(String world) {
        if (MAIN.equals(world)) return new double[]{-52.5, 97, 0.5, -90};
        if (BEDWARS.equals(world)) return new double[]{-39.5, 72, 0, -90};
        if (SKYWARS.equals(world)) return new double[]{-3.5, 66, 0.5, -90};
        return null;
    }

    /**
     * NPC pads keyed by the id the lobby asks for, or null for an unknown world. Keys that match one of our game NPCs
     * use Swofty's coordinates for the equivalent NPC; the rest are that hub's other NPCs, recorded under {@code extra.}
     * so a hologram or a later NPC can use them without another visit to the source.
     */
    public static Map<String, double[]> pads(String world) {
        Map<String, double[]> m = new LinkedHashMap<String, double[]>();
        if (MAIN.equals(world)) {
            m.put("bedwars", p(-25.5, 93, 2.5, 96));                 // NPCBedWars
            m.put("skywars", p(-26.5, 93, 10.5, 118));               // NPCSkyWars
            m.put("extra.all_games", p(-29.5, 93, 18.5, 139));       // NPCAllGames
            m.put("extra.skyblock", p(-25.5, 93, -1.5, 83));         // NPCSkyBlock
            m.put("extra.murder_mystery", p(-29.5, 93, -17.5, 39));  // NPCMurderMystery
            m.put("extra.hypixel_store", p(-44.5, 92, 25.5, -180));  // NPCHypixelStore
            m.put("extra.lobby_explorer", p(-52.5, 92, 28.5, -150)); // NPCLobbyExplorerOliver
            m.put("extra.achievement_guide", p(-39.5, 87, 42.5, 129)); // NPCAchievementGuide
            m.put("extra.delivery_man", p(-24.5, 67, -5.5, 85));     // NPCDeliveryMan
        } else if (BEDWARS.equals(world)) {
            m.put("bedwars_eight_one", p(-6.5, 68, -14.5, 90));      // NPCSolo
            m.put("bedwars_eight_two", p(-3.5, 68, -11.5, 90));      // NPCDoubles
            m.put("bedwars_four_three", p(-1.5, 68, -7.5, 90));      // NPCTrios
            m.put("bedwars_four_four", p(-0.5, 68, -3.5, 90));       // NPCSquads
            m.put("extra.four_four_dreams", p(-0.5, 68, 5.5, 90));   // NPCFourFour
            m.put("extra.dreams", p(-3.5, 68, 13.5, 90));            // NPCDreams
            m.put("extra.shopkeeper", p(4.5, 67, 0.5, 90));          // NPCShopkeeper
            m.put("extra.stats", p(2, 67, -17, 65));                 // NPCStats
            m.put("extra.hypixel_store", p(11.5, 69, 20.5, 90));     // NPCHypixelStore
            m.put("extra.doorman_dave", p(21.5, 69, 1.5, 90));       // NPCDoormanDave
            m.put("extra.slumber_tour_guide", p(-28.5, 68, -8.5, 0)); // NPCSlumberTourGuide
            m.put("extra.quest_master", p(-23.5, 67, 6.15, 110));    // QuestMasterNPC
            m.put("extra.holiday_man", p(-24.5, 67, -5.5, 85));      // NPCHolidayMan
        } else if (SKYWARS.equals(world)) {
            m.put("solo_normal", p(21.5, 64, -10.5, 90));            // NPCNormal
            m.put("solo_insane", p(22.5, 64, -7.5, 90));             // NPCSoloInsane
            // Swofty's skywars hub carries no teams NPC; the row continues at its spacing (x + 1, z + 3) so our two
            // teams modes stay reachable instead of stacking on a pad that already has an NPC.
            m.put("teams_normal", p(23.5, 64, -4.5, 90));
            m.put("teams_insane", p(24.5, 64, -1.5, 90));
            m.put("extra.solo_lucky_block", p(23.5, 64, -4.5, 90));  // NPCSoloLuckyBlock
            m.put("extra.quest_master", p(11.5, 63, 6.5, 110));      // NPCQuestMaster
            m.put("extra.stats", p(37.5, 64, -23.5, 90));            // NPCSkywarsStats
            m.put("extra.holiday_man", p(11.5, 63, -5.5, 65));       // NPCHolidayMan
        }
        return m;
    }

    private static double[] p(double x, double y, double z, double yaw) { return new double[]{x, y, z, yaw}; }
}
