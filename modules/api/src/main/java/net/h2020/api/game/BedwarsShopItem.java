package net.h2020.api.game;

/**
 * Hypixel Bed Wars item shop as of 2020 (ids are stable; the bedwars module owns the actual GUI and may correct
 * prices/amounts to match 2020 exactly). Bots buy through BedwarsView.purchase(player, item).
 */
public enum BedwarsShopItem {
    // Blocks
    WOOL(Currency.IRON, 4, 16), HARDENED_CLAY(Currency.IRON, 12, 16), BLAST_PROOF_GLASS(Currency.IRON, 12, 4),
    END_STONE(Currency.IRON, 24, 12), LADDER(Currency.IRON, 4, 16), WOOD(Currency.GOLD, 4, 16),
    OBSIDIAN(Currency.EMERALD, 4, 4),
    // Melee
    STONE_SWORD(Currency.IRON, 10, 1), IRON_SWORD(Currency.GOLD, 7, 1), DIAMOND_SWORD(Currency.EMERALD, 4, 1),
    KNOCKBACK_STICK(Currency.GOLD, 5, 1),
    // Armor (permanent)
    CHAINMAIL_ARMOR(Currency.IRON, 40, 1), IRON_ARMOR(Currency.GOLD, 12, 1), DIAMOND_ARMOR(Currency.EMERALD, 6, 1),
    // Tools (tiered, permanent, downgrade on death)
    SHEARS(Currency.IRON, 20, 1), PICKAXE(Currency.IRON, 10, 1), AXE(Currency.IRON, 10, 1),
    // Ranged
    ARROW(Currency.GOLD, 2, 8), BOW(Currency.GOLD, 12, 1), POWER_BOW(Currency.GOLD, 24, 1), PUNCH_BOW(Currency.EMERALD, 6, 1),
    // Potions
    SPEED_POTION(Currency.EMERALD, 1, 1), JUMP_POTION(Currency.EMERALD, 1, 1), INVISIBILITY_POTION(Currency.EMERALD, 2, 1),
    // Utility
    GOLDEN_APPLE(Currency.GOLD, 3, 1), BEDBUG(Currency.IRON, 40, 1), DREAM_DEFENDER(Currency.IRON, 120, 1),
    FIREBALL(Currency.IRON, 40, 1), TNT(Currency.GOLD, 4, 1), ENDER_PEARL(Currency.EMERALD, 4, 1),
    WATER_BUCKET(Currency.GOLD, 3, 1), BRIDGE_EGG(Currency.EMERALD, 2, 1), MAGIC_MILK(Currency.GOLD, 4, 1),
    SPONGE(Currency.GOLD, 3, 4), POPUP_TOWER(Currency.IRON, 24, 1);

    public enum Currency { IRON, GOLD, DIAMOND, EMERALD }

    public final Currency currency;
    /** Base cost (tiered tools: cost of the next tier is decided by the shop). */
    public final int cost;
    public final int amount;

    BedwarsShopItem(Currency currency, int cost, int amount) { this.currency = currency; this.cost = cost; this.amount = amount; }
}
