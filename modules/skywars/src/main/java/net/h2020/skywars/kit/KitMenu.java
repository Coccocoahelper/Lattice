package net.h2020.skywars.kit;

import net.h2020.skywars.util.Items;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.Potion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The waiting-cage "Kit Selector" GUI. */
public final class KitMenu implements InventoryHolder {
    public static final String TITLE = "Kit Selector";

    private final Inventory inv;
    private final Map<Integer, Kit> slots = new HashMap<>();

    public KitMenu(Kit selected, boolean insane) {
        inv = Bukkit.createInventory(this, 45, TITLE);
        int[] layout = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        int i = 0;
        for (Kit k : Kit.values()) {
            if (i >= layout.length) break;
            inv.setItem(layout[i], icon(k, k == selected, insane));
            slots.put(layout[i], k);
            i++;
        }
        inv.setItem(40, Items.of(Material.ARROW).name(ChatColor.GREEN + "Close").build());
    }

    @Override public Inventory getInventory() { return inv; }

    /** null when the slot is not a kit. */
    public Kit kitAt(int slot) { return slots.get(slot); }

    public static boolean isClose(int slot) { return slot == 40; }

    private static ItemStack icon(Kit k, boolean selected, boolean insane) {
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Rarity: " + (k.rarity.equals("Rare") ? ChatColor.BLUE : ChatColor.WHITE) + k.rarity);
        lore.add("");
        lore.add(ChatColor.GRAY + "Contents:");
        for (ItemStack s : k.contents(insane)) lore.add(ChatColor.DARK_GRAY + "▪ " + ChatColor.GRAY + describe(s));
        lore.add("");
        lore.add(selected ? ChatColor.GREEN + "SELECTED!" : ChatColor.YELLOW + "Click to select!");
        ItemStack icon = Items.of(k.icon).name((selected ? ChatColor.GREEN : ChatColor.YELLOW) + k.displayName).lore(lore).build();
        if (selected) icon.addUnsafeEnchantment(Enchantment.DURABILITY, 1);
        ItemMeta m = icon.getItemMeta();
        m.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_POTION_EFFECTS);
        icon.setItemMeta(m);
        return icon;
    }

    /** "16x Snowball", "Golden Sword (Sharpness II)" ... */
    public static String describe(ItemStack s) {
        String name;
        if (s.hasItemMeta() && s.getItemMeta().hasDisplayName()) name = ChatColor.stripColor(s.getItemMeta().getDisplayName());
        else if (s.getType() == Material.POTION) {
            Potion p = Potion.fromItemStack(s);
            name = (p.isSplash() ? "Splash " : "") + "Potion of " + pretty(p.getType().name()) + " " + roman(p.getLevel());
        } else name = pretty(s.getType().name());
        StringBuilder b = new StringBuilder();
        if (s.getAmount() > 1) b.append(s.getAmount()).append("x ");
        b.append(name);
        if (!s.getEnchantments().isEmpty()) {
            b.append(" (");
            boolean first = true;
            for (Map.Entry<Enchantment, Integer> e : s.getEnchantments().entrySet()) {
                if (!first) b.append(", ");
                first = false;
                b.append(enchantName(e.getKey())).append(' ').append(roman(e.getValue()));
            }
            b.append(')');
        }
        return b.toString();
    }

    private static String pretty(String enumName) {
        String n = enumName.replace("WOOD_", "WOODEN_").replace("GOLD_", "GOLDEN_").replace("SPADE", "SHOVEL")
                .replace("SNOW_BALL", "SNOWBALL").replace("INSTANT_HEAL", "HEALING").replace("REGEN", "REGENERATION")
                .replace("INK_SACK", "BONE_MEAL").replace("ENDER_STONE", "END_STONE");
        StringBuilder b = new StringBuilder();
        for (String part : n.toLowerCase().split("_")) {
            if (part.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return b.toString();
    }

    private static String enchantName(Enchantment e) {
        if (e.equals(Enchantment.DAMAGE_ALL)) return "Sharpness";
        if (e.equals(Enchantment.PROTECTION_ENVIRONMENTAL)) return "Protection";
        if (e.equals(Enchantment.PROTECTION_FALL)) return "Feather Falling";
        if (e.equals(Enchantment.PROTECTION_PROJECTILE)) return "Projectile Protection";
        if (e.equals(Enchantment.ARROW_DAMAGE)) return "Power";
        if (e.equals(Enchantment.ARROW_KNOCKBACK)) return "Punch";
        if (e.equals(Enchantment.ARROW_FIRE)) return "Flame";
        if (e.equals(Enchantment.DIG_SPEED)) return "Efficiency";
        if (e.equals(Enchantment.DURABILITY)) return "Unbreaking";
        if (e.equals(Enchantment.KNOCKBACK)) return "Knockback";
        if (e.equals(Enchantment.FIRE_ASPECT)) return "Fire Aspect";
        return pretty(e.getName());
    }

    public static String roman(int n) {
        switch (n) {
            case 1: return "I"; case 2: return "II"; case 3: return "III"; case 4: return "IV"; case 5: return "V";
            default: return String.valueOf(n);
        }
    }
}
