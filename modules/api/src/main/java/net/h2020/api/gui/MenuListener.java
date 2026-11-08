package net.h2020.api.gui;

import net.h2020.api.H2020;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

/** Routes clicks in core menus and keeps their items in place. */
public final class MenuListener implements Listener {
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        InventoryHolder h = e.getInventory().getHolder();
        if (!(h instanceof Menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getInventory()) return;
        final Menu menu = (Menu) h;
        final Player p = (Player) e.getWhoClicked();
        final int slot = e.getRawSlot();
        final ClickType type = e.getClick();
        // handled next tick: opening/closing windows or teleporting to a game from inside the click event is unsafe
        // in 1.8 (ghost items, the new window closing straight away)
        Bukkit.getScheduler().runTask(H2020.plugin(), new Runnable() {
            @Override public void run() {
                if (!p.isOnline() || p.getOpenInventory().getTopInventory().getHolder() != menu) return;
                menu.handle(p, slot, type);
                p.updateInventory();
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }
}
