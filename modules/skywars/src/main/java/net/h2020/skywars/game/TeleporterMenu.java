package net.h2020.skywars.game;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Spectator "Teleporter" compass GUI: one head per alive player. */
final class TeleporterMenu implements InventoryHolder {
    static final String TITLE = "Teleporter";

    private final Inventory inv;
    private final Map<Integer, UUID> targets = new HashMap<>();

    TeleporterMenu(SkywarsGame game, Player viewer) {
        List<Player> alive = game.alivePlayers();
        int size = Math.max(9, Math.min(54, ((alive.size() + 8) / 9) * 9));
        inv = Bukkit.createInventory(this, size, TITLE);
        int slot = 0;
        for (Player p : alive) {
            if (slot >= size) break;
            ItemStack head = new ItemStack(Material.SKULL_ITEM, 1, (short) 3);
            SkullMeta m = (SkullMeta) head.getItemMeta();
            m.setOwner(p.getName());
            m.setDisplayName(game.coloredName(p));
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Health: " + ChatColor.WHITE + (int) Math.ceil(p.getHealth()) + ChatColor.RED + "❤");
            lore.add(ChatColor.GRAY + "Kills: " + ChatColor.WHITE + game.kills(p));
            lore.add("");
            lore.add(ChatColor.YELLOW + "Click to spectate!");
            m.setLore(lore);
            head.setItemMeta(m);
            inv.setItem(slot, head);
            targets.put(slot, p.getUniqueId());
            slot++;
        }
    }

    @Override public Inventory getInventory() { return inv; }

    UUID targetAt(int slot) { return targets.get(slot); }
}
