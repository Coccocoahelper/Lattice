package net.h2020.api.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

/** A chest GUI whose clicks are always cancelled and routed to per-slot handlers. */
public class Menu implements InventoryHolder {
    public interface Click { void click(Player p, ClickType type); }

    private final Inventory inventory;
    private final Map<Integer, Click> handlers = new HashMap<>();

    public Menu(String title, int rows) {
        if (title.length() > 32) title = title.substring(0, 32);
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    public Menu set(int slot, ItemStack item, Click handler) {
        inventory.setItem(slot, item);
        if (handler != null) handlers.put(slot, handler);
        else handlers.remove(slot);
        return this;
    }

    public Menu set(int slot, ItemStack item) { return set(slot, item, null); }

    public void open(Player p) { p.openInventory(inventory); }

    void handle(Player p, int rawSlot, ClickType type) {
        if (rawSlot < 0 || rawSlot >= inventory.getSize()) return;
        Click c = handlers.get(rawSlot);
        if (c != null) c.click(p, type);
    }

    @Override public Inventory getInventory() { return inventory; }

    public int size() { return inventory.getSize(); }
}
