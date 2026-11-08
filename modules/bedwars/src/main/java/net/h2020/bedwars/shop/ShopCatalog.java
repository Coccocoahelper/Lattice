package net.h2020.bedwars.shop;

import net.h2020.api.game.BedwarsShopItem;
import net.h2020.api.game.BedwarsShopItem.Currency;
import net.h2020.api.game.TeamColor;
import net.h2020.bedwars.util.Items;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The summer 2020 item shop: categories, names, descriptions, prices and the items handed out. Prices follow the
 * 2020 shop (TNT is 8 gold in 3v3v3v3/4v4v4v4); tiered tools are priced per tier.
 */
public final class ShopCatalog {
    private ShopCatalog() {}

    public static final class Price {
        public final Currency currency;
        public final int cost;
        public Price(Currency currency, int cost) { this.currency = currency; this.cost = cost; }
    }

    public static final class Entry {
        public final BedwarsShopItem item;
        public final ShopCategory category;
        public final String name;
        public final String description;
        public final int amount;

        Entry(BedwarsShopItem item, ShopCategory category, String name, String description, int amount) {
            this.item = item; this.category = category; this.name = name; this.description = description;
            this.amount = amount;
        }
    }

    /** One step of a tiered tool (pickaxe / axe). */
    public static final class ToolTier {
        public final String name;
        public final Material material;
        public final Price price;
        public final int efficiency, sharpness;

        ToolTier(String name, Material material, Currency c, int cost, int efficiency, int sharpness) {
            this.name = name; this.material = material; this.price = new Price(c, cost);
            this.efficiency = efficiency; this.sharpness = sharpness;
        }
    }

    public static final ToolTier[] PICKAXES = {
            new ToolTier("Wooden Pickaxe", Material.WOOD_PICKAXE, Currency.IRON, 10, 1, 0),
            new ToolTier("Iron Pickaxe", Material.IRON_PICKAXE, Currency.IRON, 10, 2, 0),
            new ToolTier("Golden Pickaxe", Material.GOLD_PICKAXE, Currency.GOLD, 3, 3, 2),
            new ToolTier("Diamond Pickaxe", Material.DIAMOND_PICKAXE, Currency.GOLD, 6, 3, 0)};
    public static final ToolTier[] AXES = {
            new ToolTier("Wooden Axe", Material.WOOD_AXE, Currency.IRON, 10, 1, 0),
            new ToolTier("Stone Axe", Material.STONE_AXE, Currency.IRON, 10, 1, 0),
            new ToolTier("Iron Axe", Material.IRON_AXE, Currency.GOLD, 3, 2, 0),
            new ToolTier("Diamond Axe", Material.DIAMOND_AXE, Currency.GOLD, 6, 3, 0)};

    private static final Map<BedwarsShopItem, Entry> ENTRIES = new EnumMap<>(BedwarsShopItem.class);
    /** shops/bedwars.yml overrides: a price wins over the code table, enabled=false hides the item. */
    private static final Map<BedwarsShopItem, Price> PRICE_OVERRIDE = new EnumMap<>(BedwarsShopItem.class);
    private static final java.util.Set<BedwarsShopItem> DISABLED = java.util.EnumSet.noneOf(BedwarsShopItem.class);

    private static void add(BedwarsShopItem i, ShopCategory c, String name, String desc) {
        ENTRIES.put(i, new Entry(i, c, name, desc, i.amount));
    }

    static {
        add(BedwarsShopItem.WOOL, ShopCategory.BLOCKS, "Wool", "Great for bridging across islands. Turns into your team's color.");
        add(BedwarsShopItem.HARDENED_CLAY, ShopCategory.BLOCKS, "Hardened Clay", "Basic block to defend your bed.");
        add(BedwarsShopItem.BLAST_PROOF_GLASS, ShopCategory.BLOCKS, "Blast-Proof Glass", "Immune to explosions.");
        add(BedwarsShopItem.END_STONE, ShopCategory.BLOCKS, "End Stone", "Solid block to defend your bed.");
        add(BedwarsShopItem.LADDER, ShopCategory.BLOCKS, "Ladder", "Useful to save cats stuck in trees.");
        add(BedwarsShopItem.WOOD, ShopCategory.BLOCKS, "Oak Wood Planks", "Good block to defend your bed. Strong against pickaxes.");
        add(BedwarsShopItem.OBSIDIAN, ShopCategory.BLOCKS, "Obsidian", "Extreme protection for your bed.");

        add(BedwarsShopItem.STONE_SWORD, ShopCategory.MELEE, "Stone Sword", "");
        add(BedwarsShopItem.IRON_SWORD, ShopCategory.MELEE, "Iron Sword", "");
        add(BedwarsShopItem.DIAMOND_SWORD, ShopCategory.MELEE, "Diamond Sword", "");
        add(BedwarsShopItem.KNOCKBACK_STICK, ShopCategory.MELEE, "Stick (Knockback I)", "");

        add(BedwarsShopItem.CHAINMAIL_ARMOR, ShopCategory.ARMOR, "Permanent Chainmail Armor",
                "Chainmail leggings and boots which you will always spawn with.");
        add(BedwarsShopItem.IRON_ARMOR, ShopCategory.ARMOR, "Permanent Iron Armor",
                "Iron leggings and boots which you will always spawn with.");
        add(BedwarsShopItem.DIAMOND_ARMOR, ShopCategory.ARMOR, "Permanent Diamond Armor",
                "Diamond leggings and boots which you will always spawn with.");

        add(BedwarsShopItem.SHEARS, ShopCategory.TOOLS, "Permanent Shears",
                "Great to get rid of wool. You will always spawn with these shears.");
        add(BedwarsShopItem.PICKAXE, ShopCategory.TOOLS, "Pickaxe", "");
        add(BedwarsShopItem.AXE, ShopCategory.TOOLS, "Axe", "");

        add(BedwarsShopItem.ARROW, ShopCategory.RANGED, "Arrow", "");
        add(BedwarsShopItem.BOW, ShopCategory.RANGED, "Bow", "");
        add(BedwarsShopItem.POWER_BOW, ShopCategory.RANGED, "Bow (Power I)", "");
        add(BedwarsShopItem.PUNCH_BOW, ShopCategory.RANGED, "Bow (Power I, Punch I)", "");

        add(BedwarsShopItem.SPEED_POTION, ShopCategory.POTIONS, "Speed II Potion (45 seconds)", "Speed II (0:45)");
        add(BedwarsShopItem.JUMP_POTION, ShopCategory.POTIONS, "Jump V Potion (45 seconds)", "Jump Boost V (0:45)");
        add(BedwarsShopItem.INVISIBILITY_POTION, ShopCategory.POTIONS, "Invisibility Potion (30 seconds)",
                "Complete Invisibility (0:30)");

        add(BedwarsShopItem.GOLDEN_APPLE, ShopCategory.UTILITY, "Golden Apple", "Well-rounded healing.");
        add(BedwarsShopItem.BEDBUG, ShopCategory.UTILITY, "Bedbug",
                "Spawns silverfish where the snowball lands to distract your enemies. Lasts 15 seconds.");
        add(BedwarsShopItem.DREAM_DEFENDER, ShopCategory.UTILITY, "Dream Defender",
                "Iron Golem to help defend your base. Lasts 4 minutes.");
        add(BedwarsShopItem.FIREBALL, ShopCategory.UTILITY, "Fireball",
                "Right-click to launch! Great to knock back enemies walking on thin bridges.");
        add(BedwarsShopItem.TNT, ShopCategory.UTILITY, "TNT", "Instantly ignites, appropriate to explode things!");
        add(BedwarsShopItem.ENDER_PEARL, ShopCategory.UTILITY, "Ender Pearl", "The quickest way to invade enemy bases.");
        add(BedwarsShopItem.WATER_BUCKET, ShopCategory.UTILITY, "Water Bucket",
                "Great to slow down approaching enemies. Can also protect against TNT.");
        add(BedwarsShopItem.BRIDGE_EGG, ShopCategory.UTILITY, "Bridge Egg",
                "This egg creates a bridge in its trail after being thrown.");
        add(BedwarsShopItem.MAGIC_MILK, ShopCategory.UTILITY, "Magic Milk",
                "Avoid triggering traps for 30 seconds after consuming.");
        add(BedwarsShopItem.SPONGE, ShopCategory.UTILITY, "Sponge", "Great tool for soaking up water.");
        add(BedwarsShopItem.POPUP_TOWER, ShopCategory.UTILITY, "Pop-up Tower",
                "A tower in a box: 3x3 walls, a ladder shaft and a roofed firing step.");
    }

    public static Entry entry(BedwarsShopItem i) { return ENTRIES.get(i); }

    public static List<BedwarsShopItem> items(ShopCategory c) {
        List<BedwarsShopItem> out = new ArrayList<BedwarsShopItem>();
        for (BedwarsShopItem i : BedwarsShopItem.values()) {
            Entry e = ENTRIES.get(i);
            if (e != null && e.category == c && !DISABLED.contains(i)) out.add(i);
        }
        return out;
    }

    /** Write the current catalogue to shops/bedwars.yml so a server can edit prices without touching code. */
    public static void export(java.io.File f) {
        try {
            if (f.isFile()) return;
            f.getParentFile().mkdirs();
            List<String> lines = new ArrayList<String>();
            lines.add("# Bed Wars shop catalogue. Edit prices or set enabled: false to hide an item.");
            lines.add("# Currency: IRON, GOLD, EMERALD, DIAMOND. Restart or /h2020 reload to apply.");
            for (BedwarsShopItem i : BedwarsShopItem.values()) {
                Entry e = ENTRIES.get(i);
                if (e == null) continue;
                Price p = new Price(i.currency, i.cost);
                lines.add("");
                lines.add(i.name().toLowerCase() + ":");
                lines.add("  name: \"" + e.name + "\"");
                lines.add("  category: " + e.category.name());
                lines.add("  currency: " + p.currency.name());
                lines.add("  price: " + p.cost);
                lines.add("  enabled: true");
            }
            java.nio.file.Files.write(f.toPath(), lines, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            net.h2020.api.H2020.plugin().getLogger().warning("shop export failed: " + ex.getMessage());
        }
    }

    /** Apply shops/bedwars.yml: price and enabled. Unknown ids and currencies are ignored, not fatal. */
    public static void load(java.io.File f) {
        if (!f.isFile()) return;
        org.bukkit.configuration.file.FileConfiguration c =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
        for (String key : c.getKeys(false)) {
            BedwarsShopItem item;
            try { item = BedwarsShopItem.valueOf(key.toUpperCase()); } catch (IllegalArgumentException e) { continue; }
            if (c.isSet(key + ".enabled") && !c.getBoolean(key + ".enabled")) DISABLED.add(item);
            else DISABLED.remove(item);
            Currency cur = item.currency;
            if (c.isString(key + ".currency")) {
                try { cur = Currency.valueOf(c.getString(key + ".currency").toUpperCase()); }
                catch (IllegalArgumentException ignored) { }
            }
            if (c.isSet(key + ".price")) PRICE_OVERRIDE.put(item, new Price(cur, Math.max(1, c.getInt(key + ".price"))));
            else if (c.isString(key + ".currency")) PRICE_OVERRIDE.put(item, new Price(cur, item.cost));
        }
    }

    /** Default 2020 Quick Buy layout (21 slots, null = empty slot). */
    public static BedwarsShopItem[] defaultQuickBuy() {
        return new BedwarsShopItem[]{
                BedwarsShopItem.WOOL, BedwarsShopItem.STONE_SWORD, BedwarsShopItem.CHAINMAIL_ARMOR, BedwarsShopItem.PICKAXE,
                BedwarsShopItem.BOW, BedwarsShopItem.SPEED_POTION, BedwarsShopItem.TNT,
                BedwarsShopItem.WOOD, BedwarsShopItem.IRON_SWORD, BedwarsShopItem.IRON_ARMOR, BedwarsShopItem.SHEARS,
                BedwarsShopItem.ARROW, BedwarsShopItem.JUMP_POTION, BedwarsShopItem.WATER_BUCKET,
                BedwarsShopItem.HARDENED_CLAY, BedwarsShopItem.GOLDEN_APPLE, BedwarsShopItem.FIREBALL,
                BedwarsShopItem.ENDER_PEARL, BedwarsShopItem.AXE, BedwarsShopItem.INVISIBILITY_POTION, null};
    }

    /** Price of a non-tiered item for this mode. */
    public static Price price(BedwarsShopItem i, boolean bigMode) {
        Price over = PRICE_OVERRIDE.get(i);
        if (over != null) return over;
        if (bigMode) {
            // 3v3v3v3/4v4v4v4 prices that differ from Solo/Doubles (Hypixel wiki snapshot 2020-05-25)
            switch (i) {
                case TNT: return new Price(Currency.GOLD, 8);
                case WATER_BUCKET: return new Price(Currency.GOLD, 6);
                case SPONGE: return new Price(Currency.GOLD, 6);
                case POPUP_TOWER: return new Price(Currency.IRON, 24);
                case BRIDGE_EGG: return new Price(Currency.EMERALD, 1);
                case DIAMOND_SWORD: return new Price(Currency.EMERALD, 3);
                default: break;
            }
        }
        return new Price(i.currency, i.cost);
    }

    // ------------------------------------------------------------------ currency

    public static Material material(Currency c) {
        switch (c) {
            case IRON: return Material.IRON_INGOT;
            case GOLD: return Material.GOLD_INGOT;
            case DIAMOND: return Material.DIAMOND;
            default: return Material.EMERALD;
        }
    }

    public static ChatColor color(Currency c) {
        switch (c) {
            case IRON: return ChatColor.WHITE;
            case GOLD: return ChatColor.GOLD;
            case DIAMOND: return ChatColor.AQUA;
            default: return ChatColor.DARK_GREEN;
        }
    }

    public static String name(Currency c, int amount) {
        switch (c) {
            case IRON: return "Iron";
            case GOLD: return "Gold";
            case DIAMOND: return amount == 1 ? "Diamond" : "Diamonds";
            default: return amount == 1 ? "Emerald" : "Emeralds";
        }
    }

    /** "§f4 Iron" */
    public static String costText(Price p) { return color(p.currency) + "" + p.cost + " " + name(p.currency, p.cost); }

    // ------------------------------------------------------------------ items handed out

    public static ItemStack tool(ToolTier t, int sharpnessUpgrade) {
        Items b = Items.of(t.material).unbreakable();
        if (t.efficiency > 0) b.enchant(Enchantment.DIG_SPEED, t.efficiency);
        int sharp = Math.max(t.sharpness, Items.isAxe(t.material) ? sharpnessUpgrade : 0);
        if (sharp > 0) b.enchant(Enchantment.DAMAGE_ALL, sharp);
        return b.build();
    }

    /** The physical item(s) bought (non-armor, non-tool). */
    @SuppressWarnings("deprecation")
    public static ItemStack give(BedwarsShopItem i, TeamColor team, int sharpness) {
        int amount = i.amount;
        switch (i) {
            case WOOL: return new ItemStack(Material.WOOL, amount, team.woolData());
            case HARDENED_CLAY: return new ItemStack(Material.STAINED_CLAY, amount, team.woolData());
            case BLAST_PROOF_GLASS: return new ItemStack(Material.STAINED_GLASS, amount, team.woolData());
            case END_STONE: return new ItemStack(Material.ENDER_STONE, amount);
            case LADDER: return new ItemStack(Material.LADDER, amount);
            case WOOD: return new ItemStack(Material.WOOD, amount);
            case OBSIDIAN: return new ItemStack(Material.OBSIDIAN, amount);
            case STONE_SWORD: return sword(Material.STONE_SWORD, sharpness);
            case IRON_SWORD: return sword(Material.IRON_SWORD, sharpness);
            case DIAMOND_SWORD: return sword(Material.DIAMOND_SWORD, sharpness);
            case KNOCKBACK_STICK: return Items.of(Material.STICK).enchant(Enchantment.KNOCKBACK, 1).build();
            case SHEARS: return Items.of(Material.SHEARS).unbreakable().build();
            case ARROW: return new ItemStack(Material.ARROW, amount);
            case BOW: return Items.of(Material.BOW).unbreakable().build();
            case POWER_BOW: return Items.of(Material.BOW).unbreakable().enchant(Enchantment.ARROW_DAMAGE, 1).build();
            case PUNCH_BOW: return Items.of(Material.BOW).unbreakable().enchant(Enchantment.ARROW_DAMAGE, 1)
                    .enchant(Enchantment.ARROW_KNOCKBACK, 1).build();
            case SPEED_POTION: return potion(8194, PotionEffectType.SPEED, 45 * 20, 1, "Speed II Potion (45 seconds)");
            case JUMP_POTION: return potion(8203, PotionEffectType.JUMP, 45 * 20, 4, "Jump V Potion (45 seconds)");
            case INVISIBILITY_POTION:
                return potion(8206, PotionEffectType.INVISIBILITY, 30 * 20, 0, "Invisibility Potion (30 seconds)");
            case GOLDEN_APPLE: return new ItemStack(Material.GOLDEN_APPLE, amount);
            case BEDBUG: return Items.of(Material.SNOW_BALL).name(ChatColor.RESET + "Bedbug").build();
            case DREAM_DEFENDER: return Items.of(Material.MONSTER_EGG, 1, 99).name(ChatColor.RESET + "Dream Defender").build();
            case FIREBALL: return Items.of(Material.FIREBALL).name(ChatColor.RESET + "Fireball").build();
            case TNT: return new ItemStack(Material.TNT, amount);
            case ENDER_PEARL: return new ItemStack(Material.ENDER_PEARL, amount);
            case WATER_BUCKET: return new ItemStack(Material.WATER_BUCKET, amount);
            case BRIDGE_EGG: return Items.of(Material.EGG).name(ChatColor.RESET + "Bridge Egg").build();
            case MAGIC_MILK: return Items.of(Material.MILK_BUCKET).name(ChatColor.RESET + "Magic Milk").build();
            case SPONGE: return new ItemStack(Material.SPONGE, amount);
            case POPUP_TOWER: return Items.of(Material.CHEST).name(ChatColor.RESET + "Pop-up Tower").build();
            default: return null;
        }
    }

    public static ItemStack sword(Material m, int sharpness) {
        Items b = Items.of(m).unbreakable();
        if (sharpness > 0) b.enchant(Enchantment.DAMAGE_ALL, sharpness);
        return b.build();
    }

    private static ItemStack potion(int data, PotionEffectType type, int ticks, int amp, String name) {
        ItemStack s = new ItemStack(Material.POTION, 1, (short) data);
        PotionMeta pm = (PotionMeta) s.getItemMeta();
        pm.clearCustomEffects();
        pm.addCustomEffect(new PotionEffect(type, ticks, amp), true);
        pm.setMainEffect(type);
        pm.setDisplayName(ChatColor.RESET + name);
        pm.addItemFlags(ItemFlag.HIDE_POTION_EFFECTS);
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.BLUE + effectLine(type, amp, ticks / 20));
        pm.setLore(lore);
        s.setItemMeta(pm);
        return s;
    }

    private static String effectLine(PotionEffectType type, int amp, int seconds) {
        String n = type.equals(PotionEffectType.SPEED) ? "Speed" : type.equals(PotionEffectType.JUMP) ? "Jump Boost" : "Invisibility";
        String lvl = amp > 0 ? " " + net.h2020.bedwars.util.Text.roman(amp + 1) : "";
        return n + lvl + " (" + net.h2020.bedwars.util.Text.clock(seconds) + ")";
    }
}
