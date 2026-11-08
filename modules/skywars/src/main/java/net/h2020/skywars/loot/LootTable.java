package net.h2020.skywars.loot;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/** Weighted loot table. Each entry has a category so island chests can guarantee a base kit spread. */
public final class LootTable {

    public enum Category { ARMOR, SWORD, BLOCKS, PROJECTILE, BOW, FOOD, UTILITY, TOOL, POTION }

    public static final class Entry {
        public final Category category;
        public final int weight;
        private final Supplier<ItemStack> generator;

        Entry(Category category, int weight, Supplier<ItemStack> generator) {
            this.category = category; this.weight = weight; this.generator = generator;
        }

        public ItemStack roll() { return generator.get(); }
    }

    private final String name;
    private final List<Entry> entries = new ArrayList<>();
    private int totalWeight;

    public LootTable(String name) { this.name = name; }

    public String name() { return name; }

    public LootTable add(Category c, int weight, Supplier<ItemStack> gen) {
        entries.add(new Entry(c, weight, gen));
        totalWeight += weight;
        return this;
    }

    public List<Entry> entries() { return Collections.unmodifiableList(entries); }

    /** Any entry, by weight. */
    public Entry pick(Random r) {
        int x = r.nextInt(Math.max(1, totalWeight));
        for (Entry e : entries) {
            x -= e.weight;
            if (x < 0) return e;
        }
        return entries.get(entries.size() - 1);
    }

    /** An entry of the given category, by weight (null if the table has none). */
    public Entry pick(Random r, Category c) {
        int total = 0;
        for (Entry e : entries) if (e.category == c) total += e.weight;
        if (total <= 0) return null;
        int x = r.nextInt(total);
        for (Entry e : entries) {
            if (e.category != c) continue;
            x -= e.weight;
            if (x < 0) return e;
        }
        return null;
    }
}
