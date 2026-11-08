package net.h2020.bedwars.util;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Tiny fluent item builder. */
public final class Items {
    private final ItemStack stack;
    private final ItemMeta meta;

    private Items(Material m, int amount, int data) {
        stack = new ItemStack(m, amount, (short) data);
        meta = stack.getItemMeta();
    }

    public static Items of(Material m) { return new Items(m, 1, 0); }
    public static Items of(Material m, int amount) { return new Items(m, amount, 0); }
    public static Items of(Material m, int amount, int data) { return new Items(m, amount, data); }

    public Items name(String name) { if (meta != null) meta.setDisplayName(name); return this; }

    public Items lore(List<String> lore) { if (meta != null) meta.setLore(new ArrayList<>(lore)); return this; }

    public Items lore(String... lore) { return lore(Arrays.asList(lore)); }

    public Items enchant(Enchantment e, int level) { if (meta != null) meta.addEnchant(e, level, true); return this; }

    public Items unbreakable() {
        if (meta != null) {
            meta.spigot().setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        }
        return this;
    }

    public Items flags(ItemFlag... f) { if (meta != null) meta.addItemFlags(f); return this; }

    public Items hideAll() { return flags(ItemFlag.values()); }

    public Items color(Color c) {
        if (meta instanceof LeatherArmorMeta) ((LeatherArmorMeta) meta).setColor(c);
        return this;
    }

    public ItemStack build() {
        if (meta != null) stack.setItemMeta(meta);
        return stack;
    }

    /** Word-wraps a gray description into lore lines of ~30 visible chars like Hypixel's shop lore. */
    public static List<String> wrap(String text, String color, int width) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                out.add(color + line);
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) out.add(color + line);
        return out;
    }

    public static boolean isSword(Material m) {
        return m == Material.WOOD_SWORD || m == Material.STONE_SWORD || m == Material.IRON_SWORD
                || m == Material.GOLD_SWORD || m == Material.DIAMOND_SWORD;
    }

    public static boolean isAxe(Material m) {
        return m == Material.WOOD_AXE || m == Material.STONE_AXE || m == Material.IRON_AXE
                || m == Material.GOLD_AXE || m == Material.DIAMOND_AXE;
    }

    public static boolean isPickaxe(Material m) {
        return m == Material.WOOD_PICKAXE || m == Material.STONE_PICKAXE || m == Material.IRON_PICKAXE
                || m == Material.GOLD_PICKAXE || m == Material.DIAMOND_PICKAXE;
    }

    public static boolean isArmor(Material m) {
        String n = m.name();
        return n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS");
    }
}
