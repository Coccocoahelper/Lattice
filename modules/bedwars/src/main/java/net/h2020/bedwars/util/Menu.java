package net.h2020.bedwars.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Chest GUI with per-slot click handlers. Every click inside a Menu is cancelled by the module listener, which then
 * routes it here. Contents can be rebuilt in place (keeps the window open, no cursor reset) via {@link #clear()}.
 */
public class Menu implements InventoryHolder {
    public interface Click { void click(Player p, ClickType type); }

    private final Inventory inventory;
    private final Map<Integer, Click> handlers = new HashMap<>();

    public Menu(String title, int rows) {
        String t = title.length() > 32 ? title.substring(0, 32) : title;
        inventory = Bukkit.createInventory(this, rows * 9, t);
    }

    @Override public Inventory getInventory() { return inventory; }

    public void set(int slot, ItemStack item, Click click) {
        inventory.setItem(slot, item);
        if (click == null) handlers.remove(slot); else handlers.put(slot, click);
    }

    public void set(int slot, ItemStack item) { set(slot, item, null); }

    public void clear() { inventory.clear(); handlers.clear(); }

    public void handle(Player p, int rawSlot, ClickType type) {
        Click c = handlers.get(rawSlot);
        if (c != null) c.click(p, type);
    }

    public void open(Player p) { p.openInventory(inventory); }
}
