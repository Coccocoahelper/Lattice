package net.h2020.bedwars.shop;

import net.h2020.bedwars.BwPlayer;
import net.h2020.bedwars.BwTeam;
import net.h2020.bedwars.BedwarsGame;

/**
 * One trap's effect, as an object. The BedWars2023 shape: a trap is data (name, icon, price) plus a small action that
 * knows what it does when it fires, so adding a trap is a new class rather than another branch inside the game loop.
 */
public interface TrapAction {
    void trigger(BedwarsGame game, BwTeam team, BwPlayer intruder);
}
