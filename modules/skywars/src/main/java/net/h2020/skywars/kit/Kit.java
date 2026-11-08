package net.h2020.skywars.kit;

import net.h2020.skywars.util.Items;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.List;

/**
 * The 2020 free/starter SkyWars kits (Normal / Insane variants). Applied when the cages open.
 * Contents follow the 2020 kit descriptions as closely as known.
 */
public enum Kit {
    DEFAULT("Default", Material.WOOD_PICKAXE, "Common") {
        @Override List<ItemStack> items(boolean insane) {
            List<ItemStack> l = new ArrayList<>();
            l.add(new ItemStack(Material.WOOD_PICKAXE));
            l.add(new ItemStack(Material.WOOD_AXE));
            l.add(new ItemStack(Material.WOOD_SPADE));
            return l;
        }
    },
    ARMORER("Armorer", Material.GOLD_CHESTPLATE, "Common") {
        @Override List<ItemStack> items(boolean insane) {
            List<ItemStack> l = new ArrayList<>();
            l.add(Items.of(Material.GOLD_HELMET).ench(Enchantment.PROTECTION_ENVIRONMENTAL, insane ? 2 : 0).build());
            l.add(Items.of(Material.GOLD_CHESTPLATE).ench(Enchantment.PROTECTION_ENVIRONMENTAL, insane ? 2 : 1).build());
            l.add(Items.of(Material.GOLD_LEGGINGS).ench(Enchantment.PROTECTION_ENVIRONMENTAL, insane ? 2 : 0).build());
            l.add(Items.of(Material.GOLD_BOOTS).ench(Enchantment.PROTECTION_ENVIRONMENTAL, insane ? 2 : 0).build());
            return l;
        }
    },
    SCOUT("Scout", Material.DIAMOND_BOOTS, "Common") {
        @Override List<ItemStack> items(boolean insane) {
            List<ItemStack> l = new ArrayList<>();
            l.add(Items.of(Material.CHAINMAIL_BOOTS).ench(Enchantment.PROTECTION_FALL, insane ? 2 : 1).build());
            l.add(Items.potion(PotionType.SPEED, 2, true, insane ? 3 : 2).build());
            return l;
        }
    },
    KNIGHT("Knight", Material.GOLD_SWORD, "Common") {
        @Override List<ItemStack> items(boolean insane) {
            List<ItemStack> l = new ArrayList<>();
            l.add(Items.of(Material.GOLD_SWORD).ench(Enchantment.DAMAGE_ALL, insane ? 3 : 2)
                    .ench(Enchantment.DURABILITY, 3).build());
            l.add(Items.of(Material.GOLD_HELMET).ench(Enchantment.PROTECTION_ENVIRONMENTAL, insane ? 1 : 0).build());
            return l;
        }
    },
    ARCHER("Archer", Material.BOW, "Common") {
        @Override List<ItemStack> items(boolean insane) {
            List<ItemStack> l = new ArrayList<>();
            l.add(Items.of(Material.BOW).ench(Enchantment.ARROW_DAMAGE, insane ? 1 : 0).build());
            l.add(new ItemStack(Material.ARROW, insane ? 16 : 10));
            l.add(Items.potion(PotionType.INSTANT_HEAL, 1, true, 1).build());
            return l;
        }
    },
    ECOLOGIST("Ecologist", Material.SAPLING, "Common") {
        @Override List<ItemStack> items(boolean insane) {
            List<ItemStack> l = new ArrayList<>();
            l.add(Items.of(Material.IRON_AXE).ench(Enchantment.DIG_SPEED, insane ? 2 : 1).build());
            l.add(new ItemStack(Material.SAPLING, 16));
            l.add(new ItemStack(Material.INK_SACK, 8, (short) 15));
            return l;
        }
    },
    HEALER("Healer", Material.POTION, "Common") {
        @Override List<ItemStack> items(boolean insane) {
            List<ItemStack> l = new ArrayList<>();
            l.add(Items.potion(PotionType.INSTANT_HEAL, 2, true, insane ? 3 : 2).build());
            l.add(Items.potion(PotionType.REGEN, 1, false, 1).build());
            return l;
        }
    },
    SNOWMAN("Snowman", Material.SNOW_BALL, "Common") {
        @Override List<ItemStack> items(boolean insane) {
            List<ItemStack> l = new ArrayList<>();
            l.add(Items.of(Material.DIAMOND_SPADE).ench(Enchantment.DIG_SPEED, 1).build());
            l.add(new ItemStack(Material.SNOW_BALL, 16));
            l.add(new ItemStack(Material.SNOW_BLOCK, insane ? 32 : 16));
            return l;
        }
    },
    PYRO("Pyro", Material.FLINT_AND_STEEL, "Rare") {
        @Override List<ItemStack> items(boolean insane) {
            List<ItemStack> l = new ArrayList<>();
            l.add(new ItemStack(Material.FLINT_AND_STEEL));
            l.add(new ItemStack(Material.LAVA_BUCKET));
            l.add(Items.potion(PotionType.FIRE_RESISTANCE, 1, false, 1).build());
            return l;
        }
    },
    ENDERMAN("Enderman", Material.ENDER_PEARL, "Rare") {
        @Override List<ItemStack> items(boolean insane) {
            List<ItemStack> l = new ArrayList<>();
            l.add(new ItemStack(Material.ENDER_PEARL, insane ? 2 : 1));
            l.add(new ItemStack(Material.ENDER_STONE, insane ? 32 : 16));
            return l;
        }
    },
    BASEBALL_PLAYER("Baseball Player", Material.WOOD_SWORD, "Rare") {
        @Override List<ItemStack> items(boolean insane) {
            List<ItemStack> l = new ArrayList<>();
            l.add(Items.of(Material.WOOD_SWORD).name("Baseball Bat").ench(Enchantment.KNOCKBACK, insane ? 2 : 1)
                    .ench(Enchantment.DURABILITY, 3).build());
            l.add(Items.of(Material.IRON_HELMET).ench(Enchantment.PROTECTION_PROJECTILE, 1).build());
            return l;
        }
    };

    public final String displayName;
    public final Material icon;
    public final String rarity;

    Kit(String displayName, Material icon, String rarity) {
        this.displayName = displayName; this.icon = icon; this.rarity = rarity;
    }

    abstract List<ItemStack> items(boolean insane);

    public List<ItemStack> contents(boolean insane) { return items(insane); }

    /** Put the kit in the player's inventory, equipping armour into empty armour slots. */
    public void apply(Player p, boolean insane) {
        PlayerInventory inv = p.getInventory();
        for (ItemStack s : items(insane)) {
            String n = s.getType().name();
            if (n.endsWith("_HELMET") && inv.getHelmet() == null) inv.setHelmet(s);
            else if (n.endsWith("_CHESTPLATE") && inv.getChestplate() == null) inv.setChestplate(s);
            else if (n.endsWith("_LEGGINGS") && inv.getLeggings() == null) inv.setLeggings(s);
            else if (n.endsWith("_BOOTS") && inv.getBoots() == null) inv.setBoots(s);
            else inv.addItem(s);
        }
        p.updateInventory();
    }

    public static Kit byName(String s) {
        for (Kit k : values()) if (k.name().equalsIgnoreCase(s) || k.displayName.equalsIgnoreCase(s)) return k;
        return null;
    }
}
