package net.h2020.bedwars.shop;

import org.bukkit.Material;

/** Team upgrades (diamond-priced) as in the summer 2020 "Upgrades & Traps" menu. */
public enum Upgrade {
    SHARPNESS("sharpness", "Sharpened Swords", Material.IRON_SWORD,
            "Your team permanently gains Sharpness I on all swords and axes!",
            new int[]{4}, new int[]{8}, new String[]{"Sharpness I"}),
    PROTECTION("protection", "Reinforced Armor", Material.IRON_CHESTPLATE,
            "Your team permanently gains Protection on all armor pieces!",
            new int[]{2, 4, 8, 16}, new int[]{5, 10, 20, 30},
            new String[]{"Protection I", "Protection II", "Protection III", "Protection IV"}),
    HASTE("haste", "Maniac Miner", Material.GOLD_PICKAXE,
            "All players on your team permanently gain Haste.",
            new int[]{2, 4}, new int[]{4, 6}, new String[]{"Haste I", "Haste II"}),
    FORGE("forge", "Forge", Material.FURNACE,
            "Upgrade resource spawning on your island.",
            new int[]{2, 4, 6, 8}, new int[]{4, 8, 12, 16},
            new String[]{"+50% Resources", "+100% Resources", "Spawn emeralds", "+200% Resources"}),
    HEAL_POOL("heal_pool", "Heal Pool", Material.BEACON,
            "Creates a Regeneration field around your base!",
            new int[]{1}, new int[]{3}, new String[]{"Heal Pool"}),
    DRAGON_BUFF("dragon_buff", "Dragon Buff", Material.DRAGON_EGG,
            "Your team will have 2 dragons instead of 1 during deathmatch!",
            new int[]{5}, new int[]{5}, new String[]{"Dragon Buff"});

    public static final String[] FORGE_NAMES = {"Iron Forge", "Golden Forge", "Emerald Forge", "Molten Forge"};

    public final String id;
    public final String displayName;
    public final Material icon;
    public final String description;
    private final int[] smallCosts, bigCosts;
    public final String[] tierNames;

    Upgrade(String id, String displayName, Material icon, String description, int[] smallCosts, int[] bigCosts,
            String[] tierNames) {
        this.id = id; this.displayName = displayName; this.icon = icon; this.description = description;
        this.smallCosts = smallCosts; this.bigCosts = bigCosts; this.tierNames = tierNames;
    }

    public int maxLevel() { return smallCosts.length; }

    /** Diamond cost of reaching `level` (1-based). big = 3v3v3v3 / 4v4v4v4 pricing. */
    public int cost(int level, boolean big) {
        int[] c = big ? bigCosts : smallCosts;
        return c[Math.max(0, Math.min(c.length - 1, level - 1))];
    }

    /** Name used in "X purchased §6Y" for the given new level. */
    public String purchaseName(int level) {
        if (this == FORGE) return FORGE_NAMES[Math.max(0, Math.min(3, level - 1))];
        if (maxLevel() == 1) return displayName;
        return displayName + " " + net.h2020.bedwars.util.Text.roman(level);
    }

    public static Upgrade byId(String id) {
        for (Upgrade u : values()) if (u.id.equalsIgnoreCase(id)) return u;
        return null;
    }
}
