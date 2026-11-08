package net.h2020.api.game;

import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Bed Wars-specific admin work that core must be able to trigger without depending on the Bed Wars jar. Implemented by
 * the bedwars plugin and looked up as a service; if that plugin is not installed the commands simply report it.
 */
public interface BedwarsTools {
    void teamChest(CommandSender sender, Player asPlayerOrNull);
    void placeCheck(CommandSender sender, Block block);
    void testDeposit(CommandSender sender, Player player, boolean enderChest);
    void generators(CommandSender sender, Player asPlayerOrNull);
    /** Build a pop-up tower at a location (debug). */
    void tower(CommandSender sender, org.bukkit.Location at);
    /** Re-read the Bed Wars shop catalogue after a config edit. */
    void reload();
}
