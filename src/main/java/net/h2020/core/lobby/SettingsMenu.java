package net.h2020.core.lobby;

import net.h2020.api.gui.ItemBuilder;
import net.h2020.api.gui.Menu;
import net.h2020.core.Core;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

/** The lobby settings GUI: pick a simulated latency for the real client connection. */
public final class SettingsMenu {
    private static final int[] PINGS = {0, 50, 100, 150, 200, 300, 500};

    private final Core core;

    public SettingsMenu(Core core) { this.core = core; }

    public void open(Player p) {
        Menu m = new Menu("Settings", 3);
        for (int slot = 0; slot < 27; slot++) {
            m.set(slot, new ItemBuilder(Material.STAINED_GLASS_PANE, 1, 7).name(" ").build());
        }

        int current = core.ping.delay(p.getUniqueId());
        m.set(4, new ItemBuilder(Material.WATCH).name(ChatColor.GREEN + "Connection")
                .lore(ChatColor.GRAY + "Simulated ping: " + ChatColor.WHITE + label(current),
                        ChatColor.GRAY + "Applies to your real client connection.").build());

        for (int i = 0; i < PINGS.length; i++) {
            final int ms = PINGS[i];
            boolean selected = ms == current;
            ItemBuilder b = new ItemBuilder(Material.REDSTONE_COMPARATOR)
                    .name(ChatColor.GREEN + "Latency: " + ChatColor.WHITE + label(ms))
                    .lore(ChatColor.GRAY + "Simulated ping: " + ChatColor.WHITE + label(ms), "",
                            selected ? ChatColor.GREEN + "Currently selected" : ChatColor.YELLOW + "Click to apply");
            if (selected) b.glow();
            m.set(10 + i, b.hideAll().build(), new Menu.Click() {
                @Override public void click(Player pl, ClickType type) {
                    core.ping.set(pl, ms);
                    pl.playSound(pl.getLocation(), Sound.CLICK, 1f, 1f);
                    open(pl);
                }
            });
        }

        m.set(22, new ItemBuilder(Material.BARRIER).name(ChatColor.RED + "Close").build(), new Menu.Click() {
            @Override public void click(Player pl, ClickType type) { pl.closeInventory(); }
        });
        m.open(p);
    }

    private static String label(int ms) {
        return ms <= 0 ? "Off" : ms + " ms";
    }
}
