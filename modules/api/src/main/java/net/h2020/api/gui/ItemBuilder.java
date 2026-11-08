package net.h2020.api.gui;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Fluent ItemStack builder for menus and lobby items. */
public final class ItemBuilder {
    private final ItemStack item;
    private final ItemMeta meta;
    private final List<String> lore = new ArrayList<>();

    public ItemBuilder(Material m) { this(m, 1, (short) 0); }

    public ItemBuilder(Material m, int amount, int data) {
        item = new ItemStack(m, Math.max(1, Math.min(64, amount)), (short) data);
        meta = item.getItemMeta();
    }

    public ItemBuilder name(String name) { meta.setDisplayName(name); return this; }

    public ItemBuilder lore(String... lines) { lore.addAll(Arrays.asList(lines)); return this; }

    public ItemBuilder lore(List<String> lines) { lore.addAll(lines); return this; }

    /** Word-wrap gray description text at ~40 visible chars like Hypixel menus. */
    public ItemBuilder wrap(String color, String text) {
        lore.addAll(wrapText(color, text, 40));
        return this;
    }

    public ItemBuilder glow() {
        meta.addEnchant(Enchantment.DURABILITY, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        return this;
    }

    public ItemBuilder hideAll() {
        meta.addItemFlags(ItemFlag.values());
        return this;
    }

    public ItemBuilder skullOwner(String owner) {
        if (meta instanceof SkullMeta) ((SkullMeta) meta).setOwner(owner);
        return this;
    }

    public ItemStack build() {
        if (!lore.isEmpty()) meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_POTION_EFFECTS);
        item.setItemMeta(meta);
        return item;
    }

    public static List<String> wrapText(String color, String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && ChatColor.stripColor(line.toString()).length() + 1 + ChatColor.stripColor(word).length() > width) {
                out.add(color + line);
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) out.add(color + line);
        return out;
    }
}
