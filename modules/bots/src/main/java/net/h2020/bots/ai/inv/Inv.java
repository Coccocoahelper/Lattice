package net.h2020.bots.ai.inv;

import net.h2020.api.bot.BotController;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Inventory queries + client-style actions on the bot's own inventory (window 0). Raw slots for window 0:
 * 5-8 armor (helmet..boots), 9-35 main, 36-44 hotbar. Callers handle human pacing.
 */
public final class Inv {
    public interface Pred { boolean test(ItemStack i); }

    public static final Pred SWORD = new Pred() { public boolean test(ItemStack i) { return Items.isSword(i); } };
    public static final Pred BLOCK = new Pred() { public boolean test(ItemStack i) { return Items.isBuildBlock(i) && i.getType() != Material.OBSIDIAN; } };
    public static final Pred ANY_BLOCK = new Pred() { public boolean test(ItemStack i) { return Items.isBuildBlock(i); } };
    public static final Pred BOW = type(Material.BOW);
    public static final Pred ARROW = type(Material.ARROW);
    public static final Pred GAPPLE = type(Material.GOLDEN_APPLE);
    public static final Pred PEARL = type(Material.ENDER_PEARL);
    public static final Pred FIREBALL = type(Material.FIREBALL);
    public static final Pred TNT = type(Material.TNT);
    public static final Pred ROD = type(Material.FISHING_ROD);
    public static final Pred THROWABLE = new Pred() {
        public boolean test(ItemStack i) { return !Items.empty(i) && (i.getType() == Material.SNOW_BALL || i.getType() == Material.EGG); }
    };
    public static final Pred PICKAXE = new Pred() { public boolean test(ItemStack i) { return Items.isPickaxe(i); } };
    public static final Pred AXE = new Pred() { public boolean test(ItemStack i) { return Items.isAxe(i); } };
    public static final Pred SHEARS = type(Material.SHEARS);
    public static final Pred FOOD = new Pred() { public boolean test(ItemStack i) { return Items.isFood(i); } };
    /** Bed Wars knockback stick (a stick with Knockback I): not a sword, but the best void-kill tool there is. */
    public static final Pred KNOCKBACK_STICK = new Pred() {
        public boolean test(ItemStack i) {
            return !Items.empty(i) && i.getType() == Material.STICK
                    && i.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.KNOCKBACK) > 0;
        }
    };
    /** Bridge egg (a thrown egg that lays a wool line): one emerald worth of bridge when the blocks ran out. */
    public static final Pred BRIDGE_EGG = type(Material.EGG);

    public static Pred type(final Material m) {
        return new Pred() { public boolean test(ItemStack i) { return !Items.empty(i) && i.getType() == m; } };
    }

    private Inv() {}

    public static int findHotbar(Player p, Pred pred) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) if (pred.test(inv.getItem(i))) return i;
        return -1;
    }

    /** Index in PlayerInventory (0-35) or -1. Hotbar first. */
    public static int find(Player p, Pred pred) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) if (pred.test(inv.getItem(i))) return i;
        return -1;
    }

    public static boolean has(Player p, Pred pred) { return find(p, pred) >= 0; }

    public static int count(Player p, Pred pred) {
        int n = 0;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) { ItemStack it = inv.getItem(i); if (pred.test(it)) n += it.getAmount(); }
        return n;
    }

    public static int count(Player p, Material m) { return count(p, type(m)); }

    /** Hotbar slot with the best melee weapon (sword > axe > anything). */
    public static int bestMeleeHotbar(Player p) {
        PlayerInventory inv = p.getInventory();
        int best = -1; double bv = 1.0;
        for (int i = 0; i < 9; i++) {
            double v = Items.meleeValue(inv.getItem(i));
            if (v > bv) { bv = v; best = i; }
        }
        return best;
    }

    public static int bestMeleeAnywhere(Player p) {
        PlayerInventory inv = p.getInventory();
        int best = -1; double bv = 1.0;
        for (int i = 0; i < 36; i++) {
            double v = Items.meleeValue(inv.getItem(i));
            if (v > bv) { bv = v; best = i; }
        }
        return best;
    }

    public static double bestMeleeValue(Player p) {
        int s = bestMeleeAnywhere(p);
        return s < 0 ? 1 : Items.meleeValue(p.getInventory().getItem(s));
    }

    /** Hotbar slot of the preferred bridging block stack. */
    public static int bestBlockHotbar(Player p) {
        PlayerInventory inv = p.getInventory();
        int best = -1, bp = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack it = inv.getItem(i);
            int pr = Items.bridgePreference(it);
            if (pr > bp || (pr == bp && pr >= 0 && it.getAmount() > inv.getItem(best).getAmount())) { bp = pr; best = i; }
        }
        return bp < 0 ? -1 : best;
    }

    public static double[] wornArmor(Player p) {
        ItemStack[] a = p.getInventory().getArmorContents(); // boots, legs, chest, helmet
        double[] v = new double[4];
        v[Items.ArmorSlot.HELMET.ordinal()] = Items.armorValue(a[3]);
        v[Items.ArmorSlot.CHESTPLATE.ordinal()] = Items.armorValue(a[2]);
        v[Items.ArmorSlot.LEGGINGS.ordinal()] = Items.armorValue(a[1]);
        v[Items.ArmorSlot.BOOTS.ordinal()] = Items.armorValue(a[0]);
        return v;
    }

    public static double totalArmor(Player p) {
        double[] v = wornArmor(p);
        return v[0] + v[1] + v[2] + v[3];
    }

    /** Raw window-0 slot of a PlayerInventory index. */
    public static int rawSlot(int invIndex) { return invIndex < 9 ? 36 + invIndex : invIndex; }

    /** Raw window-0 armor slot for an armor type (5 helmet .. 8 boots). */
    public static int rawArmorSlot(Items.ArmorSlot s) { return 5 + s.ordinal(); }

    /** Raw slot in an open container window for a PlayerInventory index (container has topSize slots). */
    public static int rawSlotInContainer(int topSize, int invIndex) {
        return invIndex < 9 ? topSize + 27 + invIndex : topSize + (invIndex - 9);
    }

    /**
     * Make sure an item matching pred sits in the hotbar and select it. Returns true if it is now held; false if a
     * swap click was sent (try again next tick) or nothing matches.
     */
    public static boolean equip(Player p, BotController c, Pred pred, int preferredSlot) {
        int hb = findHotbar(p, pred);
        if (hb >= 0) {
            // the swap click opened the own inventory: close it (E) so input and hotbar keys work again
            if (ownInventoryOpen(p, c)) c.closeWindow();
            if (c.heldSlot() != hb) c.selectHotbar(hb);
            return true;
        }
        int idx = find(p, pred);
        if (idx < 0) return false;
        if (c.containerOpen() && !ownInventoryOpen(p, c)) return false;
        int target = preferredSlot >= 0 && preferredSlot < 9 ? preferredSlot : c.heldSlot();
        c.clickWindow(rawSlot(idx), target, 2);
        return false;
    }

    /** True when the GUI the client has open is its own inventory (window 0), not a server container. */
    public static boolean ownInventoryOpen(Player p, BotController c) {
        if (!c.containerOpen()) return false;
        if (c instanceof net.h2020.bots.client.VirtualClient) return ((net.h2020.bots.client.VirtualClient) c).windowId() == 0;
        return p.getOpenInventory().getType() == org.bukkit.event.inventory.InventoryType.CRAFTING;
    }

    public static boolean holding(Player p, Pred pred) {
        return pred.test(p.getInventory().getItemInHand());
    }
}
