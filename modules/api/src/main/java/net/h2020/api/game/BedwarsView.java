package net.h2020.api.game;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Bedwars-specific read/act surface. The bedwars Game implementation implements this; the bot AI consumes it.
 * All Locations are in game.world().
 */
public interface BedwarsView extends Game {
    boolean bedAlive(TeamColor team);
    /** Was this block placed by a player in this game (rather than part of the map)? Only those can be broken. */
    boolean isPlaced(org.bukkit.Location at);
    /** Foot block of the bed (where it was, even if destroyed). */
    Location bedLocation(TeamColor team);
    Location teamSpawn(TeamColor team);
    Location teamGenerator(TeamColor team);
    Location shopNpc(TeamColor team);
    Location upgradesNpc(TeamColor team);
    List<Location> diamondGenerators();
    List<Location> emeraldGenerators();
    Location mid();
    /** True while respawning (5s spectator countdown). */
    boolean isRespawning(Player p);
    /** Purchase as if clicked in the shop GUI (must be within reach of a shop NPC to look legit; not enforced). */
    boolean purchase(Player p, BedwarsShopItem item);
    boolean canAfford(Player p, BedwarsShopItem item);
    /** Team upgrades by id: "sharpness","protection","haste","forge","heal_pool","dragon_buff", traps "trap_it_is_a_trap",
     *  "trap_counter_offensive","trap_alarm","trap_miner_fatigue". */
    boolean purchaseUpgrade(Player p, String upgradeId);
    int upgradeLevel(TeamColor team, String upgradeId);
    /** Seconds until next diamond/emerald tier or bed destruction / sudden death, with its label (e.g. "Diamond II"). */
    String nextEventName();
    int nextEventSeconds();
}
