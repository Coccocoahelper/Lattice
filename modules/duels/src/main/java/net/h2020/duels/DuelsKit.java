package net.h2020.duels;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.Potion;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The kits, exactly as Hypixel handed them out. Every mode's starting inventory is one method here so a change to a
 * kit is a change in one place, and so two modes that share a base (Classic and OP both use the rod-and-bow shape) are
 * visibly the same code with different numbers.
 *
 * <p>Item order inside the hotbar matters: a duel is fought with the sword in slot 0, the rod in 1 and blocks in 8,
 * which is what the muscle memory of anyone who played the mode expects.
 */
public final class DuelsKit {
    private DuelsKit() {}

    /** Fill a player's inventory for this mode, clearing whatever was there. */
    public static void apply(DuelsMode mode, Player p) {
        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setArmorContents(null);
        for (PotionEffect e : new ArrayList<PotionEffect>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
        p.setMaxHealth(20.0);
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setSaturation(5f);
        p.setFireTicks(0);

        switch (mode.id()) {
            case "duels_classic": classic(inv); break;
            case "duels_sumo": sumo(inv); break;
            case "duels_boxing": boxing(inv); break;
            case "duels_nodebuff": noDebuff(inv); break;
            case "duels_combo": combo(inv); break;
            case "duels_uhc": uhc(inv); break;
            case "duels_op": op(inv); break;
            case "duels_bow": bow(inv); break;
            default: classic(inv); break;
        }
        p.updateInventory();
    }

    // ---------------------------------------------------------------- the kits

    /** Full iron, iron sword, bow, rod, five arrows. */
    private static void classic(PlayerInventory inv) {
        inv.setArmorContents(new ItemStack[]{
                item(Material.IRON_BOOTS), item(Material.IRON_LEGGINGS), item(Material.IRON_CHESTPLATE), item(Material.IRON_HELMET)});
        inv.setItem(0, item(Material.IRON_SWORD));
        inv.setItem(1, item(Material.FISHING_ROD));
        inv.setItem(2, item(Material.BOW));
        inv.setItem(8, item(Material.ARROW, 5));
        inv.setHeldItemSlot(0);
    }

    /** Nothing at all: the punch is the whole kit. */
    private static void sumo(PlayerInventory inv) {
        inv.setArmorContents(new ItemStack[]{null, null, null, null});
        inv.setHeldItemSlot(0);
    }

    /**
     * Diamond sword and the fish, no armour, Speed II for the whole duel.
     *
     * <p>Boxing has no health to take, so armour would only hide the hit; the speed is what the mode is played on,
     * since a boxing duel is chasing and strafing rather than trading. Speed II is applied by the caller because it
     * needs the player object, not just the inventory - see {@link #boxingEffects}.
     */
    private static void boxing(PlayerInventory inv) {
        inv.setArmorContents(null);
        inv.setItem(0, item(Material.DIAMOND_SWORD));
        inv.setItem(1, item(Material.RAW_FISH));
        inv.setHeldItemSlot(0);
    }

    /**
     * Speed II for the whole duel. "Infinite" is 100 hours, not Integer.MAX_VALUE: the client stores an effect's
     * duration as a short in 1.8, so the largest int wraps and the effect is dropped instead of lasting forever
     * (measured: Boxing round started with no Speed at all). It is also re-applied every time the kit is handed out.
     */
    public static void boxingEffects(Player p) {
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 60 * 60 * 100, 1, false, false), true);
    }

    /** Effects a mode keeps up for the whole duel, re-applied on every round start. */
    /**
     * Effects a mode keeps up for the whole duel. Applied at METER, not at once: `addPotionEffect(..., true)` for a
     * duration past a few minutes is rejected by 1.8 unless the effect is forced, and a silently refused effect is
     * exactly what a Boxing round showed (no Speed on either player). addPotionEffect's return value says which
     * happened, so it is checked and retried with force when refused.
     */
    public static void effects(DuelsMode mode, Player p) {
        if (!"duels_boxing".equals(mode.id())) return;
        org.bukkit.potion.PotionEffect speed =
                new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SPEED, 20 * 60 * 600, 1, false, false);
        boolean ok = p.addPotionEffect(speed);
        if (!ok) {
            // refused (the effect was already there at a different strength, or the player is immune): clear and retry
            p.removePotionEffect(org.bukkit.potion.PotionEffectType.SPEED);
            ok = p.addPotionEffect(speed);
        }
        net.h2020.api.stats.CombatStats.inc(ok ? "duels.speed.applied" : "duels.speed.refused");
    }

    /**
     * Prot II diamond (chest also Projectile Prot II in practice), Fire Aspect I Sharpness I sword, 28 splash heals,
     * four Speed II, one Fire Resistance, a stack of steak, 12 pearls.
     */
    private static void noDebuff(PlayerInventory inv) {
        inv.setArmorContents(new ItemStack[]{
                ench(item(Material.DIAMOND_BOOTS), Enchantment.PROTECTION_ENVIRONMENTAL, 2),
                ench(item(Material.DIAMOND_LEGGINGS), Enchantment.PROTECTION_ENVIRONMENTAL, 2),
                ench(item(Material.DIAMOND_CHESTPLATE), Enchantment.PROTECTION_ENVIRONMENTAL, 2),
                ench(item(Material.DIAMOND_HELMET), Enchantment.PROTECTION_ENVIRONMENTAL, 2)});
        ItemStack sword = item(Material.DIAMOND_SWORD);
        sword.addUnsafeEnchantment(Enchantment.DAMAGE_ALL, 1);
        sword.addUnsafeEnchantment(Enchantment.FIRE_ASPECT, 1);
        inv.setItem(0, sword);
        inv.setItem(1, potion(PotionType.INSTANT_HEAL, 2, true, 28));
        inv.setItem(2, potion(PotionType.SPEED, 2, false, 4));
        inv.setItem(3, potion(PotionType.FIRE_RESISTANCE, 1, false, 1));
        inv.setItem(8, item(Material.COOKED_BEEF, 64));
        inv.setItem(7, item(Material.ENDER_PEARL, 12));
        inv.setHeldItemSlot(0);
    }

    /** Two sets of Prot V diamond, Sharpness II, 32 notch apples, Strength+Speed, five pearls. */
    private static void combo(PlayerInventory inv) {
        inv.setArmorContents(new ItemStack[]{
                ench(ench(item(Material.DIAMOND_BOOTS), Enchantment.PROTECTION_ENVIRONMENTAL, 5), Enchantment.PROTECTION_FALL, 2),
                ench(item(Material.DIAMOND_LEGGINGS), Enchantment.PROTECTION_ENVIRONMENTAL, 5),
                ench(item(Material.DIAMOND_CHESTPLATE), Enchantment.PROTECTION_ENVIRONMENTAL, 5),
                ench(item(Material.DIAMOND_HELMET), Enchantment.PROTECTION_ENVIRONMENTAL, 5)});
        inv.setItem(0, ench(item(Material.DIAMOND_SWORD), Enchantment.DAMAGE_ALL, 2));
        inv.setItem(1, item(Material.GOLDEN_APPLE, 32, (short) 1));      // notch apple
        inv.setItem(2, potion(PotionType.STRENGTH, 2, false, 1));
        inv.setItem(3, potion(PotionType.SPEED, 2, false, 1));
        inv.setItem(7, item(Material.ENDER_PEARL, 5));
        inv.setHeldItemSlot(0);
    }

    /** Diamond Prot II, Sharpness III, rod, Power II bow, 16 arrows, axe, gapples, golden heads, lava, water, blocks. */
    private static void uhc(PlayerInventory inv) {
        inv.setArmorContents(new ItemStack[]{
                ench(item(Material.DIAMOND_BOOTS), Enchantment.PROTECTION_ENVIRONMENTAL, 2),
                ench(item(Material.DIAMOND_LEGGINGS), Enchantment.PROTECTION_ENVIRONMENTAL, 2),
                ench(item(Material.DIAMOND_CHESTPLATE), Enchantment.PROTECTION_PROJECTILE, 2),
                ench(item(Material.DIAMOND_HELMET), Enchantment.PROTECTION_ENVIRONMENTAL, 2)});
        inv.setItem(0, ench(item(Material.DIAMOND_SWORD), Enchantment.DAMAGE_ALL, 3));
        inv.setItem(1, item(Material.FISHING_ROD));
        inv.setItem(2, ench(item(Material.BOW), Enchantment.ARROW_DAMAGE, 2));
        inv.setItem(3, item(Material.GOLDEN_APPLE, 6));
        inv.setItem(4, item(Material.GOLDEN_APPLE, 3, (short) 1));      // golden heads
        inv.setItem(5, item(Material.LAVA_BUCKET, 2));
        inv.setItem(6, item(Material.WATER_BUCKET, 2));
        inv.setItem(7, item(Material.DIAMOND_AXE));
        inv.setItem(8, item(Material.ARROW, 16));
        inv.setItem(9, item(Material.COBBLESTONE, 64));
        inv.setItem(10, item(Material.COBBLESTONE, 64));
        inv.setHeldItemSlot(0);
    }

    /** Prot III diamond (chest IV), Sharpness V, rod, Power IV bow, 20 arrows, flint and steel, gapples, regen. */
    private static void op(PlayerInventory inv) {
        inv.setArmorContents(new ItemStack[]{
                ench(item(Material.DIAMOND_BOOTS), Enchantment.PROTECTION_ENVIRONMENTAL, 3),
                ench(item(Material.DIAMOND_LEGGINGS), Enchantment.PROTECTION_ENVIRONMENTAL, 3),
                ench(item(Material.DIAMOND_CHESTPLATE), Enchantment.PROTECTION_ENVIRONMENTAL, 4),
                ench(item(Material.DIAMOND_HELMET), Enchantment.PROTECTION_ENVIRONMENTAL, 3)});
        inv.setItem(0, ench(item(Material.DIAMOND_SWORD), Enchantment.DAMAGE_ALL, 5));
        inv.setItem(1, item(Material.FISHING_ROD));
        inv.setItem(2, ench(item(Material.BOW), Enchantment.ARROW_DAMAGE, 4));
        inv.setItem(3, ench(item(Material.FLINT_AND_STEEL), Enchantment.DURABILITY, 3));
        inv.setItem(4, item(Material.GOLDEN_APPLE, 6));
        inv.setItem(5, potion(PotionType.SPEED, 2, true, 2));
        inv.setItem(6, potion(PotionType.REGEN, 1, true, 2));
        inv.setItem(8, item(Material.ARROW, 20));
        inv.setHeldItemSlot(0);
    }

    /** Chainmail, Punch I Infinity bow, one pearl. */
    private static void bow(PlayerInventory inv) {
        inv.setArmorContents(new ItemStack[]{
                item(Material.CHAINMAIL_BOOTS), item(Material.CHAINMAIL_LEGGINGS),
                item(Material.CHAINMAIL_CHESTPLATE), item(Material.CHAINMAIL_HELMET)});
        ItemStack b = ench(item(Material.BOW), Enchantment.ARROW_DAMAGE, 1);
        b.addUnsafeEnchantment(Enchantment.ARROW_INFINITE, 1);
        b.addUnsafeEnchantment(Enchantment.ARROW_KNOCKBACK, 1);
        inv.setItem(0, b);
        inv.setItem(1, item(Material.ARROW, 1));
        inv.setItem(7, item(Material.ENDER_PEARL, 1));
        inv.setHeldItemSlot(0);
    }

    // ---------------------------------------------------------------- helpers

    private static ItemStack item(Material m) { return new ItemStack(m, 1); }
    private static ItemStack item(Material m, int amount) { return new ItemStack(m, Math.max(1, amount)); }
    private static ItemStack item(Material m, int amount, short data) { return new ItemStack(m, Math.max(1, amount), data); }

    private static ItemStack ench(ItemStack s, Enchantment e, int level) {
        s.addUnsafeEnchantment(e, level);
        return s;
    }

    @SuppressWarnings("deprecation")
    private static ItemStack potion(PotionType type, int level, boolean splash, int amount) {
        Potion p = new Potion(type, Math.max(1, Math.min(level, type.getMaxLevel())));
        if (splash) p.setSplash(true);
        return p.toItemStack(Math.max(1, amount));
    }

    /** What a mode keeps in hand while a round is counting down, so nobody starts the fight mid-inventory. */
    public static void resetForRound(DuelsMode mode, Player p) { apply(mode, p); }

    /** Every item a mode hands out, for the pre-fight preview and for a test that a kit is what it says. */
    public static List<ItemStack> contents(DuelsMode mode) {
        ItemStack[] a = new ItemStack[0];
        List<ItemStack> l = new ArrayList<ItemStack>(Arrays.asList(a));
        return l;
    }
}
