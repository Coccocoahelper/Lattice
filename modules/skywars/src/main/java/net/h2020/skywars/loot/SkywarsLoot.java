package net.h2020.skywars.loot;

import net.h2020.skywars.loot.LootTable.Category;
import net.h2020.skywars.util.Items;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionType;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Hypixel SkyWars (summer 2020) chest loot, reconstructed from 2020 gameplay footage and the wiki loot tables.
 * Normal: island chests are iron/chain/gold armour, stone/iron swords, stacks of stone and planks, snowballs/eggs,
 * occasional bows, pearls and golden apples; mid has diamond gear with light enchants. Insane: island chests already
 * carry enchanted diamond/iron gear and pearls; mid carries OP gear (Sharpness III-IV, Protection III-IV, Power V).
 */
public final class SkywarsLoot {
    private SkywarsLoot() {}

    public static final LootTable NORMAL_ISLAND = new LootTable("normal_island");
    public static final LootTable NORMAL_MID = new LootTable("normal_mid");
    public static final LootTable INSANE_ISLAND = new LootTable("insane_island");
    public static final LootTable INSANE_MID = new LootTable("insane_mid");

    public static LootTable island(boolean insane) { return insane ? INSANE_ISLAND : NORMAL_ISLAND; }
    public static LootTable mid(boolean insane) { return insane ? INSANE_MID : NORMAL_MID; }

    // --- helpers ---------------------------------------------------------------------------------------------
    private static int rnd(int min, int max) { return ThreadLocalRandom.current().nextInt(min, max + 1); }
    private static boolean chance(double p) { return ThreadLocalRandom.current().nextDouble() < p; }

    private static ItemStack item(Material m, int min, int max) { return new ItemStack(m, rnd(min, max)); }

    /** Armor with a chance of protection levels in [minLvl, maxLvl]. */
    private static ItemStack armor(Material m, double enchChance, int minLvl, int maxLvl) {
        Items b = Items.of(m);
        if (chance(enchChance)) b.ench(Enchantment.PROTECTION_ENVIRONMENTAL, rnd(minLvl, maxLvl));
        return b.build();
    }

    private static ItemStack sword(Material m, double sharpChance, int minLvl, int maxLvl, double fireChance) {
        Items b = Items.of(m);
        if (chance(sharpChance)) b.ench(Enchantment.DAMAGE_ALL, rnd(minLvl, maxLvl));
        if (chance(fireChance)) b.ench(Enchantment.FIRE_ASPECT, 1);
        return b.build();
    }

    private static ItemStack bow(double powerChance, int minLvl, int maxLvl, double punchChance, double flameChance) {
        Items b = Items.of(Material.BOW);
        if (chance(powerChance)) b.ench(Enchantment.ARROW_DAMAGE, rnd(minLvl, maxLvl));
        if (chance(punchChance)) b.ench(Enchantment.ARROW_KNOCKBACK, 1);
        if (chance(flameChance)) b.ench(Enchantment.ARROW_FIRE, 1);
        return b.build();
    }

    private static ItemStack tool(Material m, int effMin, int effMax) {
        return Items.of(m).ench(Enchantment.DIG_SPEED, rnd(effMin, effMax)).build();
    }

    private static ItemStack potion(PotionType t, int lvl, boolean splash) { return Items.potion(t, lvl, splash, 1).build(); }

    static {
        // ============================== NORMAL - ISLAND ==============================
        NORMAL_ISLAND
                // armor
                .add(Category.ARMOR, 9, () -> armor(Material.IRON_HELMET, 0.05, 1, 1))
                .add(Category.ARMOR, 5, () -> armor(Material.IRON_CHESTPLATE, 0.05, 1, 1))
                .add(Category.ARMOR, 6, () -> armor(Material.IRON_LEGGINGS, 0.05, 1, 1))
                .add(Category.ARMOR, 9, () -> armor(Material.IRON_BOOTS, 0.05, 1, 1))
                .add(Category.ARMOR, 7, () -> armor(Material.CHAINMAIL_HELMET, 0.1, 1, 1))
                .add(Category.ARMOR, 7, () -> armor(Material.CHAINMAIL_CHESTPLATE, 0.1, 1, 1))
                .add(Category.ARMOR, 7, () -> armor(Material.CHAINMAIL_LEGGINGS, 0.1, 1, 1))
                .add(Category.ARMOR, 7, () -> armor(Material.CHAINMAIL_BOOTS, 0.1, 1, 1))
                .add(Category.ARMOR, 5, () -> armor(Material.GOLD_HELMET, 0.15, 1, 2))
                .add(Category.ARMOR, 5, () -> armor(Material.GOLD_CHESTPLATE, 0.15, 1, 2))
                .add(Category.ARMOR, 5, () -> armor(Material.GOLD_LEGGINGS, 0.15, 1, 2))
                .add(Category.ARMOR, 5, () -> armor(Material.GOLD_BOOTS, 0.15, 1, 2))
                .add(Category.ARMOR, 3, () -> armor(Material.DIAMOND_HELMET, 0, 0, 0))
                .add(Category.ARMOR, 3, () -> armor(Material.DIAMOND_BOOTS, 0, 0, 0))
                .add(Category.ARMOR, 1, () -> armor(Material.DIAMOND_CHESTPLATE, 0, 0, 0))
                // swords
                .add(Category.SWORD, 14, () -> sword(Material.STONE_SWORD, 0.08, 1, 1, 0))
                .add(Category.SWORD, 7, () -> sword(Material.IRON_SWORD, 0.1, 1, 1, 0))
                .add(Category.SWORD, 1, () -> sword(Material.DIAMOND_SWORD, 0, 0, 0, 0))
                // blocks
                .add(Category.BLOCKS, 12, () -> item(Material.STONE, 16, 32))
                .add(Category.BLOCKS, 12, () -> item(Material.WOOD, 16, 32))
                .add(Category.BLOCKS, 4, () -> new ItemStack(Material.STONE, 64))
                .add(Category.BLOCKS, 3, () -> item(Material.LOG, 4, 8))
                // projectiles
                .add(Category.PROJECTILE, 8, () -> new ItemStack(Material.SNOW_BALL, 16))
                .add(Category.PROJECTILE, 8, () -> new ItemStack(Material.EGG, 16))
                .add(Category.BOW, 4, () -> bow(0.1, 1, 1, 0, 0))
                .add(Category.PROJECTILE, 5, () -> item(Material.ARROW, 8, 16))
                // food
                .add(Category.FOOD, 8, () -> item(Material.COOKED_BEEF, 3, 8))
                .add(Category.FOOD, 5, () -> item(Material.COOKED_CHICKEN, 3, 8))
                .add(Category.FOOD, 4, () -> item(Material.BREAD, 4, 10))
                .add(Category.FOOD, 3, () -> item(Material.GOLDEN_APPLE, 1, 2))
                // utility
                .add(Category.UTILITY, 2, () -> new ItemStack(Material.ENDER_PEARL, 1))
                .add(Category.UTILITY, 4, () -> new ItemStack(Material.LAVA_BUCKET, 1))
                .add(Category.UTILITY, 5, () -> new ItemStack(Material.WATER_BUCKET, 1))
                .add(Category.UTILITY, 4, () -> new ItemStack(Material.FISHING_ROD, 1))
                .add(Category.UTILITY, 2, () -> new ItemStack(Material.FLINT_AND_STEEL, 1))
                .add(Category.UTILITY, 3, () -> item(Material.EXP_BOTTLE, 4, 8))
                .add(Category.UTILITY, 2, () -> item(Material.TNT, 1, 2))
                // tools
                .add(Category.TOOL, 5, () -> new ItemStack(Material.IRON_PICKAXE, 1))
                .add(Category.TOOL, 2, () -> tool(Material.DIAMOND_PICKAXE, 1, 1))
                .add(Category.TOOL, 4, () -> new ItemStack(Material.IRON_AXE, 1))
                .add(Category.TOOL, 3, () -> new ItemStack(Material.STONE_PICKAXE, 1))
                // potions
                .add(Category.POTION, 2, () -> potion(PotionType.SPEED, 2, false))
                .add(Category.POTION, 1, () -> potion(PotionType.FIRE_RESISTANCE, 1, false))
                .add(Category.POTION, 1, () -> potion(PotionType.INSTANT_HEAL, 1, true));

        // ============================== NORMAL - MID ==============================
        NORMAL_MID
                .add(Category.ARMOR, 6, () -> armor(Material.DIAMOND_HELMET, 0.25, 1, 1))
                .add(Category.ARMOR, 4, () -> armor(Material.DIAMOND_CHESTPLATE, 0.25, 1, 1))
                .add(Category.ARMOR, 4, () -> armor(Material.DIAMOND_LEGGINGS, 0.25, 1, 1))
                .add(Category.ARMOR, 6, () -> armor(Material.DIAMOND_BOOTS, 0.25, 1, 1))
                .add(Category.ARMOR, 3, () -> armor(Material.IRON_CHESTPLATE, 0.6, 1, 2))
                .add(Category.ARMOR, 3, () -> armor(Material.IRON_LEGGINGS, 0.6, 1, 2))
                .add(Category.SWORD, 5, () -> sword(Material.DIAMOND_SWORD, 0.3, 1, 1, 0))
                .add(Category.SWORD, 6, () -> sword(Material.IRON_SWORD, 0.7, 1, 2, 0))
                .add(Category.BOW, 5, () -> bow(0.8, 1, 2, 0.15, 0))
                .add(Category.PROJECTILE, 6, () -> item(Material.ARROW, 12, 24))
                .add(Category.PROJECTILE, 3, () -> new ItemStack(Material.SNOW_BALL, 16))
                .add(Category.UTILITY, 7, () -> item(Material.ENDER_PEARL, 1, 2))
                .add(Category.FOOD, 6, () -> item(Material.GOLDEN_APPLE, 2, 3))
                .add(Category.FOOD, 3, () -> item(Material.COOKED_BEEF, 6, 12))
                .add(Category.POTION, 4, () -> potion(PotionType.SPEED, 2, false))
                .add(Category.POTION, 3, () -> potion(PotionType.INSTANT_HEAL, 2, true))
                .add(Category.POTION, 3, () -> potion(PotionType.REGEN, 2, false))
                .add(Category.POTION, 2, () -> potion(PotionType.FIRE_RESISTANCE, 1, false))
                .add(Category.POTION, 2, () -> potion(PotionType.POISON, 1, true))
                .add(Category.UTILITY, 4, () -> item(Material.TNT, 2, 4))
                .add(Category.UTILITY, 3, () -> new ItemStack(Material.LAVA_BUCKET, 1))
                .add(Category.UTILITY, 3, () -> new ItemStack(Material.FISHING_ROD, 1))
                .add(Category.UTILITY, 2, () -> new ItemStack(Material.FLINT_AND_STEEL, 1))
                .add(Category.UTILITY, 3, () -> item(Material.EXP_BOTTLE, 8, 16))
                .add(Category.TOOL, 3, () -> tool(Material.DIAMOND_PICKAXE, 1, 2))
                .add(Category.TOOL, 2, () -> tool(Material.DIAMOND_AXE, 1, 2))
                .add(Category.BLOCKS, 3, () -> new ItemStack(Material.WOOD, 32));

        // ============================== INSANE - ISLAND ==============================
        INSANE_ISLAND
                .add(Category.ARMOR, 8, () -> armor(Material.DIAMOND_HELMET, 0.6, 1, 2))
                .add(Category.ARMOR, 5, () -> armor(Material.DIAMOND_CHESTPLATE, 0.6, 1, 2))
                .add(Category.ARMOR, 6, () -> armor(Material.DIAMOND_LEGGINGS, 0.6, 1, 2))
                .add(Category.ARMOR, 8, () -> armor(Material.DIAMOND_BOOTS, 0.6, 1, 2))
                .add(Category.ARMOR, 5, () -> armor(Material.IRON_HELMET, 0.9, 1, 2))
                .add(Category.ARMOR, 5, () -> armor(Material.IRON_CHESTPLATE, 0.9, 1, 2))
                .add(Category.ARMOR, 5, () -> armor(Material.IRON_LEGGINGS, 0.9, 1, 2))
                .add(Category.ARMOR, 5, () -> armor(Material.IRON_BOOTS, 0.9, 1, 2))
                .add(Category.SWORD, 6, () -> sword(Material.DIAMOND_SWORD, 0.7, 1, 2, 0))
                .add(Category.SWORD, 8, () -> sword(Material.IRON_SWORD, 0.9, 1, 2, 0))
                .add(Category.BLOCKS, 12, () -> new ItemStack(Material.STONE, 64))
                .add(Category.BLOCKS, 12, () -> new ItemStack(Material.WOOD, 64))
                .add(Category.PROJECTILE, 6, () -> new ItemStack(Material.SNOW_BALL, 16))
                .add(Category.PROJECTILE, 6, () -> new ItemStack(Material.EGG, 16))
                .add(Category.BOW, 5, () -> bow(0.8, 1, 2, 0.1, 0))
                .add(Category.PROJECTILE, 6, () -> item(Material.ARROW, 12, 24))
                .add(Category.UTILITY, 6, () -> item(Material.ENDER_PEARL, 1, 2))
                .add(Category.FOOD, 8, () -> item(Material.GOLDEN_APPLE, 2, 3))
                .add(Category.FOOD, 5, () -> item(Material.COOKED_BEEF, 8, 16))
                .add(Category.UTILITY, 5, () -> new ItemStack(Material.LAVA_BUCKET, 1))
                .add(Category.UTILITY, 5, () -> new ItemStack(Material.WATER_BUCKET, 1))
                .add(Category.UTILITY, 4, () -> new ItemStack(Material.FISHING_ROD, 1))
                .add(Category.UTILITY, 3, () -> item(Material.EXP_BOTTLE, 8, 16))
                .add(Category.TOOL, 4, () -> tool(Material.DIAMOND_PICKAXE, 2, 3))
                .add(Category.TOOL, 3, () -> tool(Material.DIAMOND_AXE, 2, 3))
                .add(Category.POTION, 3, () -> potion(PotionType.INSTANT_HEAL, 2, true))
                .add(Category.POTION, 3, () -> potion(PotionType.SPEED, 2, false))
                .add(Category.POTION, 2, () -> potion(PotionType.FIRE_RESISTANCE, 1, false));

        // ============================== INSANE - MID ==============================
        INSANE_MID
                .add(Category.SWORD, 6, () -> sword(Material.DIAMOND_SWORD, 1.0, 3, 4, 0.3))
                .add(Category.SWORD, 3, () -> sword(Material.DIAMOND_SWORD, 1.0, 5, 5, 0.0))
                .add(Category.ARMOR, 5, () -> armor(Material.DIAMOND_HELMET, 1.0, 3, 4))
                .add(Category.ARMOR, 4, () -> armor(Material.DIAMOND_CHESTPLATE, 1.0, 3, 4))
                .add(Category.ARMOR, 4, () -> armor(Material.DIAMOND_LEGGINGS, 1.0, 3, 4))
                .add(Category.ARMOR, 5, () -> armor(Material.DIAMOND_BOOTS, 1.0, 3, 4))
                .add(Category.BOW, 6, () -> bow(1.0, 3, 5, 0.35, 0.3))
                .add(Category.PROJECTILE, 6, () -> item(Material.ARROW, 24, 32))
                .add(Category.UTILITY, 7, () -> item(Material.ENDER_PEARL, 2, 4))
                .add(Category.FOOD, 6, () -> item(Material.GOLDEN_APPLE, 4, 6))
                .add(Category.FOOD, 1, () -> new ItemStack(Material.GOLDEN_APPLE, 1, (short) 1))
                .add(Category.POTION, 3, () -> potion(PotionType.STRENGTH, 2, false))
                .add(Category.POTION, 4, () -> potion(PotionType.INSTANT_HEAL, 2, true))
                .add(Category.POTION, 3, () -> potion(PotionType.REGEN, 2, false))
                .add(Category.POTION, 3, () -> potion(PotionType.SPEED, 2, false))
                .add(Category.POTION, 2, () -> potion(PotionType.FIRE_RESISTANCE, 1, false))
                .add(Category.POTION, 2, () -> potion(PotionType.POISON, 2, true))
                .add(Category.UTILITY, 3, () -> item(Material.TNT, 4, 8))
                .add(Category.UTILITY, 3, () -> new ItemStack(Material.LAVA_BUCKET, 1))
                .add(Category.UTILITY, 2, () -> new ItemStack(Material.FISHING_ROD, 1))
                .add(Category.UTILITY, 3, () -> item(Material.EXP_BOTTLE, 16, 32))
                .add(Category.UTILITY, 2, () -> Items.of(Material.STICK).ench(Enchantment.KNOCKBACK, 2).build())
                .add(Category.TOOL, 3, () -> tool(Material.DIAMOND_PICKAXE, 3, 4))
                .add(Category.TOOL, 2, () -> Items.of(Material.DIAMOND_AXE).ench(Enchantment.DAMAGE_ALL, rnd(2, 3)).build())
                .add(Category.BLOCKS, 2, () -> new ItemStack(Material.WOOD, 64));
    }
}
