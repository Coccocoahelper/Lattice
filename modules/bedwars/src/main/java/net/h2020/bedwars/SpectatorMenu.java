package net.h2020.bedwars;

import net.h2020.bedwars.util.Menu;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

/** Spectator compass "Teleporter": heads of every player still alive, click to teleport. */
final class SpectatorMenu {
    private SpectatorMenu() {}

    static void open(final BedwarsGame g, Player viewer) {
        List<BwPlayer> alive = g.playingPlayers();
        int rows = Math.max(1, Math.min(6, (alive.size() + 8) / 9));
        Menu m = new Menu("Teleporter", rows);
        int slot = 0;
        for (final BwPlayer bp : alive) {
            if (slot >= rows * 9) break;
            ItemStack head = new ItemStack(Material.SKULL_ITEM, 1, (short) 3);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwner(bp.player.getName());
            meta.setDisplayName(bp.team.color.chat + bp.player.getName());
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Health: " + ChatColor.WHITE + (int) Math.ceil(bp.player.getHealth() * 5) + "%");
            lore.add(ChatColor.GRAY + "Team: " + bp.team.coloredName());
            lore.add("");
            lore.add(ChatColor.YELLOW + "Click to spectate!");
            meta.setLore(lore);
            head.setItemMeta(meta);
            m.set(slot++, head, new Menu.Click() {
                @Override public void click(Player p, ClickType type) {
                    p.closeInventory();
                    if (!bp.playing() || !bp.player.isOnline()) {
                        p.sendMessage(ChatColor.RED + "That player is no longer alive!");
                        return;
                    }
                    p.teleport(bp.player.getLocation());
                }
            });
        }
        m.open(viewer);
    }
}
