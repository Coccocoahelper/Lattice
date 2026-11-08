package net.h2020.bots.ai.inv;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.Potion;

/** Item knowledge: tiers, categories and how much a 2020 PvP player values an item. */
public final class Items {
    private Items() {}

    public enum ArmorSlot { HELMET, CHESTPLATE, LEGGINGS, BOOTS }

    public static boolean empty(ItemStack i) { return i == null || i.getType() == Material.AIR || i.getAmount() <= 0; }

    /** Blocks worth bridging/defending with. */
    public static boolean isBuildBlock(ItemStack i) {
        if (empty(i)) return false;
        switch (i.getType()) {
            case WOOL: case STAINED_CLAY: case HARD_CLAY: case ENDER_STONE: case WOOD: case STONE: case COBBLESTONE:
            case DIRT: case SANDSTONE: case RED_SANDSTONE: case LOG: case LOG_2: case STAINED_GLASS: case GLASS:
            case PRISMARINE: case BRICK: case SMOOTH_BRICK: case NETHERRACK: case QUARTZ_BLOCK: case SNOW_BLOCK:
            case CLAY: case MOSSY_COBBLESTONE: case GRASS: case NETHER_BRICK: case PACKED_ICE: case SEA_LANTERN:
            case OBSIDIAN:
                return true;
            default:
                return false;
        }
    }

    /** Preference for bridging (obsidian is precious, end stone slow to place... all place at same speed but players save them). */
    public static int bridgePreference(ItemStack i) {
        if (!isBuildBlock(i)) return -1;
        switch (i.getType()) {
            case WOOL: return 10;
            case STAINED_CLAY: case HARD_CLAY: return 8;
            case WOOD: case COBBLESTONE: case STONE: case DIRT: case SANDSTONE: case LOG: case LOG_2: return 7;
            case ENDER_STONE: return 4;
            case OBSIDIAN: return 1;
            default: return 6;
        }
    }

    public static boolean isSword(ItemStack i) { return !empty(i) && i.getType().name().endsWith("_SWORD"); }
    public static boolean isAxe(ItemStack i) { return !empty(i) && i.getType().name().endsWith("_AXE"); }
    public static boolean isPickaxe(ItemStack i) { return !empty(i) && i.getType().name().endsWith("_PICKAXE"); }

    public static double materialTier(Material m) {
        String n = m.name();
        if (n.startsWith("DIAMOND")) return 5;
        if (n.startsWith("IRON")) return 4;
        if (n.startsWith("CHAINMAIL")) return 3;
        if (n.startsWith("STONE")) return 3;
        if (n.startsWith("GOLD")) return 2;
        if (n.startsWith("WOOD") || n.startsWith("LEATHER")) return 1;
        return 0;
    }

    /** Melee value of an item (fists = 1). */
    public static double meleeValue(ItemStack i) {
        if (empty(i)) return 1;
        double v;
        switch (i.getType()) {
            case DIAMOND_SWORD: v = 8; break;
            case IRON_SWORD: v = 7; break;
            case STONE_SWORD: v = 6; break;
            case WOOD_SWORD: case GOLD_SWORD: v = 5; break;
            case DIAMOND_AXE: v = 6; break;
            case IRON_AXE: v = 5; break;
            case STONE_AXE: v = 4; break;
            case WOOD_AXE: case GOLD_AXE: v = 3; break;
            default: return 1;
        }
        return v + 1.25 * i.getEnchantmentLevel(Enchantment.DAMAGE_ALL) + 0.6 * i.getEnchantmentLevel(Enchantment.FIRE_ASPECT)
                + 0.3 * i.getEnchantmentLevel(Enchantment.KNOCKBACK);
    }

    public static ArmorSlot armorSlot(ItemStack i) {
        if (empty(i)) return null;
        String n = i.getType().name();
        if (n.endsWith("_HELMET")) return ArmorSlot.HELMET;
        if (n.endsWith("_CHESTPLATE")) return ArmorSlot.CHESTPLATE;
        if (n.endsWith("_LEGGINGS")) return ArmorSlot.LEGGINGS;
        if (n.endsWith("_BOOTS")) return ArmorSlot.BOOTS;
        return null;
    }

    /** Armor points the piece gives (1.8 values) + protection bonus. */
    public static double armorValue(ItemStack i) {
        if (empty(i)) return 0;
        ArmorSlot s = armorSlot(i);
        if (s == null) return 0;
        String n = i.getType().name();
        int[] pts;
        if (n.startsWith("LEATHER")) pts = new int[]{1, 3, 2, 1};
        else if (n.startsWith("GOLD")) pts = new int[]{2, 5, 3, 1};
        else if (n.startsWith("CHAINMAIL")) pts = new int[]{2, 5, 4, 1};
        else if (n.startsWith("IRON")) pts = new int[]{2, 6, 5, 2};
        else if (n.startsWith("DIAMOND")) pts = new int[]{3, 8, 6, 3};
        else return 0;
        return pts[s.ordinal()] + 0.75 * i.getEnchantmentLevel(Enchantment.PROTECTION_ENVIRONMENTAL)
                + 0.3 * i.getEnchantmentLevel(Enchantment.PROTECTION_PROJECTILE);
    }

    public static boolean isFood(ItemStack i) {
        return !empty(i) && i.getType().isEdible() && i.getType() != Material.GOLDEN_APPLE;
    }

    public static boolean isPotion(ItemStack i) { return !empty(i) && i.getType() == Material.POTION; }

    @SuppressWarnings("deprecation")
    public static boolean isSplash(ItemStack i) {
        if (!isPotion(i)) return false;
        try { return Potion.fromItemStack(i).isSplash(); } catch (Throwable t) { return false; }
    }

    /**
     * How much a SkyWars player wants this item from a chest, given what they already have (0 = junk, leave it).
     */
    public static double lootValue(ItemStack i, double bestMelee, double[] wornArmor, int blocks, boolean hasBow) {
        if (empty(i)) return 0;
        Material m = i.getType();
        ArmorSlot as = armorSlot(i);
        if (as != null) {
            double gain = armorValue(i) - wornArmor[as.ordinal()];
            return gain > 0 ? 70 + gain * 6 : 3;
        }
        if (isSword(i) || isAxe(i)) {
            double gain = meleeValue(i) - bestMelee;
            if (gain > 0) return 75 + gain * 5;
            return isAxe(i) ? 8 : 4;
        }
        if (isBuildBlock(i)) return blocks < 64 ? 55 + i.getAmount() * 0.2 : 30;
        switch (m) {
            case ENDER_PEARL: return 66;
            case GOLDEN_APPLE: return 64;
            case BOW: return hasBow ? 12 : 50;
            case ARROW: return hasBow ? 40 : 22;
            case SNOW_BALL: case EGG: return 38;
            case LAVA_BUCKET: return 35;
            case WATER_BUCKET: return 33;
            case FISHING_ROD: return 20;
            case POTION: return 30;
            case FLINT_AND_STEEL: return 18;
            case TNT: return 15;
            case COOKED_BEEF: case COOKED_CHICKEN: case GRILLED_PORK: case BREAD: case BAKED_POTATO: case COOKED_FISH:
            case APPLE: case CARROT_ITEM: case GOLDEN_CARROT:
                return 12;
            case DIAMOND_PICKAXE: case IRON_PICKAXE: case STONE_PICKAXE: return 14;
            case WOOD_PICKAXE: case GOLD_PICKAXE: return 6;
            case EXP_BOTTLE: return 9;
            case ENCHANTED_BOOK: return 5;
            case DIAMOND: case IRON_INGOT: case GOLD_INGOT: return 3;
            default: return 1;
        }
    }
}
