package net.h2020.core.lobby;

import net.h2020.api.game.GameFamily;
import net.h2020.api.gui.ItemBuilder;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

/** 2020 lobby hotbar. */
public final class LobbyItems {
    public enum Kind { GAME_MENU, PROFILE, SHOP, COLLECTIBLES, VISIBILITY, LOBBY_SELECTOR }

    private static final String RC = ChatColor.GRAY + " (Right Click)";
    static final String GAME_MENU = ChatColor.GREEN + "Game Menu" + RC;
    static final String PROFILE = ChatColor.GREEN + "My Profile" + RC;
    static final String SHOP = ChatColor.GREEN + "Shop" + RC;
    static final String COLLECTIBLES = ChatColor.GREEN + "Collectibles" + RC;
    static final String VISIBLE = ChatColor.WHITE + "Players: " + ChatColor.GREEN + "Visible" + RC;
    static final String HIDDEN = ChatColor.WHITE + "Players: " + ChatColor.RED + "Hidden" + RC;
    static final String LOBBY_SELECTOR = ChatColor.GREEN + "Lobby Selector" + RC;

    private LobbyItems() {}

    public static void give(Player p, GameFamily family, boolean playersHidden) {
        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setItem(0, new ItemBuilder(Material.COMPASS).name(GAME_MENU)
                .lore(family == null ? ChatColor.GRAY + "Right Click to bring up the Game Menu!"
                        : ChatColor.GRAY + "Right Click to choose a " + (family == GameFamily.BEDWARS ? "Bed Wars" : "SkyWars") + " mode!").build());
        inv.setItem(1, new ItemBuilder(Material.SKULL_ITEM, 1, 3).skullOwner(p.getName()).name(PROFILE)
                .lore(ChatColor.GRAY + "Right-click to browse quests, view", ChatColor.GRAY + "achievements, activate Network",
                        ChatColor.GRAY + "Boosters and more!").build());
        if (family != null) {
            inv.setItem(2, new ItemBuilder(Material.EMERALD).name(SHOP)
                    .lore(ChatColor.GRAY + "Right-click to open the " + (family == GameFamily.BEDWARS ? "Bed Wars" : "SkyWars") + " Shop!").build());
        }
        inv.setItem(4, new ItemBuilder(Material.CHEST).name(COLLECTIBLES)
                .lore(ChatColor.GRAY + "Right-click to view and use your", ChatColor.GRAY + "Collectibles!").build());
        inv.setItem(7, visibility(playersHidden));
        inv.setItem(8, new ItemBuilder(Material.NETHER_STAR).name(LOBBY_SELECTOR)
                .lore(ChatColor.GRAY + "Right-click to switch between", ChatColor.GRAY + "different lobbies!", ChatColor.GRAY + "Use this to stay with your friends.").build());
        inv.setHeldItemSlot(0);
        p.updateInventory();
    }

    public static ItemStack visibility(boolean hidden) {
        return new ItemBuilder(Material.INK_SACK, 1, hidden ? 8 : 10).name(hidden ? HIDDEN : VISIBLE)
                .lore(ChatColor.GRAY + "Right-click to toggle player visibility!").build();
    }

    public static Kind kind(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta m = item.getItemMeta();
        if (!m.hasDisplayName()) return null;
        String n = m.getDisplayName();
        if (n.equals(GAME_MENU)) return Kind.GAME_MENU;
        if (n.equals(PROFILE)) return Kind.PROFILE;
        if (n.equals(SHOP)) return Kind.SHOP;
        if (n.equals(COLLECTIBLES)) return Kind.COLLECTIBLES;
        if (n.equals(VISIBLE) || n.equals(HIDDEN)) return Kind.VISIBILITY;
        if (n.equals(LOBBY_SELECTOR)) return Kind.LOBBY_SELECTOR;
        return null;
    }
}
