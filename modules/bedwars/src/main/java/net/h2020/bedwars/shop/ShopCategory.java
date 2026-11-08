package net.h2020.bedwars.shop;

import org.bukkit.Material;

/** Top-row categories of the 2020 item shop (slots 0..7). */
public enum ShopCategory {
    QUICK_BUY("Quick Buy", Material.NETHER_STAR, 0),
    BLOCKS("Blocks", Material.STAINED_CLAY, 1),
    MELEE("Melee", Material.GOLD_SWORD, 0),
    ARMOR("Armor", Material.CHAINMAIL_BOOTS, 0),
    TOOLS("Tools", Material.STONE_PICKAXE, 0),
    RANGED("Ranged", Material.BOW, 0),
    POTIONS("Potions", Material.BREWING_STAND_ITEM, 0),
    UTILITY("Utility", Material.TNT, 0);

    public final String displayName;
    public final Material icon;
    public final int data;

    ShopCategory(String displayName, Material icon, int data) {
        this.displayName = displayName; this.icon = icon; this.data = data;
    }
}
