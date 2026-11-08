package net.h2020.skywars.util;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.Potion;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Small fluent ItemStack builder. */
public final class Items {
    private final ItemStack stack;

    private Items(ItemStack stack) { this.stack = stack; }

    public static Items of(Material m) { return new Items(new ItemStack(m, 1)); }
    public static Items of(Material m, int amount) { return new Items(new ItemStack(m, amount)); }
    public static Items of(Material m, int amount, int data) { return new Items(new ItemStack(m, amount, (short) data)); }
    public static Items of(ItemStack s) { return new Items(s); }

    /** Drinkable or splash 1.8 potion. */
    public static Items potion(PotionType type, int level, boolean splash, int amount) {
        Potion p = new Potion(type, Math.max(1, Math.min(level, type.getMaxLevel())));
        if (splash) p.setSplash(true);
        return new Items(p.toItemStack(amount));
    }

    public Items amount(int a) { stack.setAmount(Math.max(1, a)); return this; }

    public Items name(String name) {
        ItemMeta m = stack.getItemMeta();
        if (m != null) { m.setDisplayName(name); stack.setItemMeta(m); }
        return this;
    }

    public Items lore(String... lines) { return lore(Arrays.asList(lines)); }

    public Items lore(List<String> lines) {
        ItemMeta m = stack.getItemMeta();
        if (m != null) { m.setLore(new ArrayList<>(lines)); stack.setItemMeta(m); }
        return this;
    }

    public Items ench(Enchantment e, int level) {
        if (level > 0) stack.addUnsafeEnchantment(e, level);
        return this;
    }

    public ItemStack build() { return stack; }

    /** True if the stack is of this type and has exactly this display name. */
    public static boolean is(ItemStack s, Material type, String name) {
        if (s == null || s.getType() != type || !s.hasItemMeta()) return false;
        ItemMeta m = s.getItemMeta();
        return m.hasDisplayName() && m.getDisplayName().equals(name);
    }
}
