package net.h2020.skywars.loot;

import net.h2020.skywars.loot.LootTable.Category;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Fills SkyWars chests the way Hypixel did: each island's (usually 3) chests together guarantee a playable start
 * (armour pieces in one, a sword and blocks in another, projectiles/utility in the third), then random extras;
 * items land in random slots. Mid chests are purely random rolls from the mid table.
 */
public final class ChestFiller {
    private ChestFiller() {}

    /** Guaranteed categories per island chest index (chests beyond the third only get random rolls). */
    private static final Category[][] ISLAND_GUARANTEES = {
            {Category.ARMOR, Category.ARMOR, Category.BLOCKS},
            {Category.SWORD, Category.BLOCKS, Category.FOOD},
            {Category.ARMOR, Category.PROJECTILE, Category.UTILITY},
    };

    public static void fillIsland(List<Inventory> chests, LootTable table, Random r) {
        if (chests.isEmpty()) return;
        List<List<ItemStack>> contents = new ArrayList<>();
        for (int i = 0; i < chests.size(); i++) contents.add(new ArrayList<ItemStack>());
        Set<String> armorSlots = new HashSet<>();
        Set<String> picked = new HashSet<>();

        // guarantees, merged round-robin into the chests the island actually has
        for (int g = 0; g < ISLAND_GUARANTEES.length; g++) {
            List<ItemStack> target = contents.get(g % chests.size());
            for (Category c : ISLAND_GUARANTEES[g]) {
                ItemStack s = rollDistinct(table, c, r, armorSlots, picked);
                if (s != null) target.add(s);
            }
        }
        // random extras: 2-4 per chest
        for (List<ItemStack> list : contents) {
            int extras = 2 + r.nextInt(3);
            for (int i = 0; i < extras; i++) {
                ItemStack s = rollDistinct(table, null, r, armorSlots, picked);
                if (s != null) list.add(s);
            }
        }
        for (int i = 0; i < chests.size(); i++) place(chests.get(i), contents.get(i), r);
    }

    public static void fillMid(Inventory chest, LootTable table, Random r) {
        List<ItemStack> items = new ArrayList<>();
        Set<String> armorSlots = new HashSet<>();
        Set<String> picked = new HashSet<>();
        int n = 5 + r.nextInt(3);
        for (int i = 0; i < n; i++) {
            ItemStack s = rollDistinct(table, null, r, armorSlots, picked);
            if (s != null) items.add(s);
        }
        place(chest, items, r);
    }

    /** Roll, avoiding a second armour piece of the same slot and exact duplicates where possible. */
    private static ItemStack rollDistinct(LootTable t, Category c, Random r, Set<String> armorSlots, Set<String> picked) {
        ItemStack last = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            LootTable.Entry e = c == null ? t.pick(r) : t.pick(r, c);
            if (e == null) return null;
            ItemStack s = e.roll();
            last = s;
            String slot = armorSlot(s);
            String key = s.getType().name() + ":" + s.getDurability();
            if (slot != null && armorSlots.contains(slot)) continue;
            if (slot == null && picked.contains(key) && attempt < 3) continue;
            if (slot != null) armorSlots.add(slot);
            picked.add(key);
            return s;
        }
        return last;
    }

    private static String armorSlot(ItemStack s) {
        String n = s.getType().name();
        if (n.endsWith("_HELMET")) return "HEAD";
        if (n.endsWith("_CHESTPLATE")) return "CHEST";
        if (n.endsWith("_LEGGINGS")) return "LEGS";
        if (n.endsWith("_BOOTS")) return "FEET";
        return null;
    }

    /** Clear and put items into distinct random slots. */
    private static void place(Inventory inv, List<ItemStack> items, Random r) {
        inv.clear();
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) slots.add(i);
        Collections.shuffle(slots, r);
        int i = 0;
        for (ItemStack s : items) {
            if (i >= slots.size()) break;
            inv.setItem(slots.get(i++), s);
        }
    }
}
