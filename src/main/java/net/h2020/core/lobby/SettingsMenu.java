package net.h2020.core.lobby;

import net.h2020.api.gui.ItemBuilder;
import net.h2020.api.gui.Menu;
import net.h2020.core.Core;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.UUID;

/** The lobby settings GUI: pick a simulated latency and variability for the real client connection. */
public final class SettingsMenu {
    private static final int MAX_PING = 1000;
    private static final int MAX_JITTER = 500;
    private static final int STEP = 5;

    private final Core core;

    public SettingsMenu(Core core) { this.core = core; }

    public void open(Player p) {
        Menu m = new Menu("Settings", 3);
        for (int slot = 0; slot < 27; slot++) {
            m.set(slot, new ItemBuilder(Material.STAINED_GLASS_PANE, 1, 7).name(" ").build());
        }

        UUID id = p.getUniqueId();
        int ping = core.ping.delay(id);
        int jitter = core.ping.jitter(id);

        m.set(4, new ItemBuilder(Material.WATCH).name(ChatColor.GREEN + "Connection")
                .lore(ChatColor.GRAY + "Base ping: " + ChatColor.WHITE + label(ping),
                        ChatColor.GRAY + "Variability: " + ChatColor.WHITE + jitter + " ms",
                        ChatColor.GRAY + "Applies to your real client connection.").build());

        ItemBuilder pingItem = new ItemBuilder(Material.WATCH)
                .name(ChatColor.GREEN + "Ping: " + ChatColor.WHITE + label(ping))
                .lore(ChatColor.GRAY + "Current: " + ChatColor.WHITE + label(ping), "",
                        ChatColor.YELLOW + "Left-click: +5 ms",
                        ChatColor.YELLOW + "Right-click: -5 ms",
                        ChatColor.YELLOW + "Shift-left: +5 ms variability",
                        ChatColor.YELLOW + "Shift-right: -5 ms variability");
        if (ping > 0) pingItem.glow();
        m.set(11, pingItem.hideAll().build(), new Menu.Click() {
            @Override public void click(Player pl, ClickType type) {
                int currentPing = core.ping.delay(pl.getUniqueId());
                int currentJitter = core.ping.jitter(pl.getUniqueId());
                if (type == ClickType.LEFT) currentPing = Math.min(MAX_PING, currentPing + STEP);
                else if (type == ClickType.RIGHT) currentPing = Math.max(0, currentPing - STEP);
                else if (type == ClickType.SHIFT_LEFT) currentJitter = Math.min(MAX_JITTER, currentJitter + STEP);
                else if (type == ClickType.SHIFT_RIGHT) currentJitter = Math.max(0, currentJitter - STEP);
                core.ping.set(pl, currentPing, currentJitter);
                pl.playSound(pl.getLocation(), Sound.CLICK, 1f, 1f);
                open(pl);
            }
        });

        m.set(15, new ItemBuilder(Material.REDSTONE_COMPARATOR)
                .name(ChatColor.GREEN + "Variability: " + ChatColor.WHITE + jitter + " ms")
                .lore(ChatColor.GRAY + "Each packet is held for the base ping",
                        ChatColor.GRAY + "plus or minus this much, chosen at",
                        ChatColor.GRAY + "random for every packet.",
                        "",
                        ChatColor.GRAY + "Shift-click the ping item to change it.",
                        "",
                        ChatColor.GRAY + "In practice: " + ChatColor.WHITE + practice(ping, jitter))
                .hideAll().build(), new Menu.Click() {
            @Override public void click(Player pl, ClickType type) {
                core.ping.set(pl, core.ping.delay(pl.getUniqueId()), core.ping.jitter(pl.getUniqueId()));
                pl.playSound(pl.getLocation(), Sound.CLICK, 1f, 1f);
                open(pl);
            }
        });

        m.set(22, new ItemBuilder(Material.BARRIER).name(ChatColor.RED + "Close").build(), new Menu.Click() {
            @Override public void click(Player pl, ClickType type) { pl.closeInventory(); }
        });
        m.open(p);
    }

    private static String label(int ms) {
        return ms <= 0 ? "Off" : ms + " ms";
    }

    private static String practice(int ping, int jitter) {
        return jitter <= 0 ? "Off" : ping + " +/- " + jitter + " ms";
    }
}
