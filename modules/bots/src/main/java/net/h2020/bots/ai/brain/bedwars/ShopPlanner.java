package net.h2020.bots.ai.brain.bedwars;

import net.h2020.api.game.BedwarsShopItem;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.inv.Items;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * What a 2020 Bed Wars player buys on a shop trip, in the order they'd click it, given their role, phase and
 * wallet. Purchases are simulated against the wallet so the list is affordable as a whole.
 */
public final class ShopPlanner {
    private ShopPlanner() {}

    public static int iron(Player p) { return Inv.count(p, Material.IRON_INGOT); }
    public static int gold(Player p) { return Inv.count(p, Material.GOLD_INGOT); }
    public static int diamonds(Player p) { return Inv.count(p, Material.DIAMOND); }
    public static int emeralds(Player p) { return Inv.count(p, Material.EMERALD); }

    /** How many potions of one type the bot is carrying (drinkable ones). */
    public static int potions(Player p, org.bukkit.potion.PotionType type) {
        int n = 0;
        for (ItemStack i : p.getInventory().getContents()) {
            if (Items.empty(i) || i.getType() != Material.POTION) continue;
            try {
                if (org.bukkit.potion.Potion.fromItemStack(i).getType() == type) n += i.getAmount();
            } catch (Throwable ignored) { }
        }
        return n;
    }

    public static int wool(Player p) { return Inv.count(p, Inv.type(Material.WOOL)); }

    /** 0 none/wood, 2 stone, 3 iron, 4 diamond. */
    public static int swordTier(Player p) {
        int t = 0;
        for (ItemStack i : p.getInventory().getContents()) {
            if (Items.empty(i)) continue;
            switch (i.getType()) {
                case DIAMOND_SWORD: t = Math.max(t, 4); break;
                case IRON_SWORD: t = Math.max(t, 3); break;
                case STONE_SWORD: t = Math.max(t, 2); break;
                case WOOD_SWORD: t = Math.max(t, 1); break;
                default: break;
            }
        }
        return t;
    }

    /** 1 leather, 2 chain, 3 iron, 4 diamond (from boots, which the permanent armor upgrade replaces). */
    public static int armorTier(Player p) {
        ItemStack b = p.getInventory().getBoots();
        if (Items.empty(b)) return 0;
        switch (b.getType()) {
            case DIAMOND_BOOTS: return 4;
            case IRON_BOOTS: return 3;
            case CHAINMAIL_BOOTS: return 2;
            default: return 1;
        }
    }

    public static final class Wallet {
        public int iron, gold, diamond, emerald;
        Wallet(Player p) { iron = iron(p); gold = gold(p); diamond = diamonds(p); emerald = emeralds(p); }

        boolean take(BedwarsShopItem it) {
            switch (it.currency) {
                case IRON: if (iron < it.cost) return false; iron -= it.cost; return true;
                case GOLD: if (gold < it.cost) return false; gold -= it.cost; return true;
                case DIAMOND: if (diamond < it.cost) return false; diamond -= it.cost; return true;
                case EMERALD: if (emerald < it.cost) return false; emerald -= it.cost; return true;
                default: return false;
            }
        }
    }

    public static List<BedwarsShopItem> plan(Player p, Role role, boolean tryhard, int gameSeconds, boolean finalLife) {
        return plan(p, role, tryhard, gameSeconds, finalLife, false, null);
    }

    public static List<BedwarsShopItem> plan(Player p, Role role, boolean tryhard, int gameSeconds, boolean finalLife,
                                             boolean bedTools) {
        return plan(p, role, tryhard, gameSeconds, finalLife, bedTools, null);
    }

    /**
     * @param mlCategory what the learned shop model expects a player in this state to buy (null = nothing learned yet).
     *                   The hard-coded list below stays the plan; the model adds one item on top when the list does not
     *                   already cover what people in this situation buy.
     */
    public static List<BedwarsShopItem> plan(Player p, Role role, boolean tryhard, int gameSeconds, boolean finalLife,
                                             boolean bedTools, Integer mlCategory) {
        Wallet w = new Wallet(p);
        List<BedwarsShopItem> out = new ArrayList<>();
        int blocks = Inv.count(p, Inv.BLOCK);
        int sword = swordTier(p);
        int armor = armorTier(p);
        boolean hasPick = Inv.has(p, Inv.PICKAXE), hasShears = Inv.has(p, Inv.SHEARS), hasAxe = Inv.has(p, Inv.AXE);

        // 0) one ladder, early: 4 iron buys the movement guide's best defensive item - knockback cancellation,
        //    a climb that keeps your aim on the enemy, and an instant block in hand while you hold it.
        if (!Inv.has(p, Inv.type(Material.LADDER)) && w.iron >= 4 && blocks >= 32) out.add(BedwarsShopItem.LADDER);

        // 1) wool first: everybody needs blocks. Once a stack is in hand, chainmail (40 iron) comes before more wool.
        int woolWanted = role == Role.RUSHER ? 48 : role == Role.DEFENDER ? 32 : 32;
        if (finalLife) woolWanted += 16;
        boolean saveForChain = armor < 2 && (role != Role.RUSHER || gameSeconds > 90);
        while (blocks < woolWanted && (!saveForChain || blocks < 32 || w.iron >= 40 + 4) && w.take(BedwarsShopItem.WOOL)) {
            out.add(BedwarsShopItem.WOOL); blocks += 16;
        }
        if (saveForChain && w.iron >= 40 && blocks >= 16 && w.take(BedwarsShopItem.CHAINMAIL_ARMOR)) {
            out.add(BedwarsShopItem.CHAINMAIL_ARMOR); armor = 2;
        }
        // 1b) bed tools for the bed we are rushing: shears (wool) and a pickaxe (end stone / clay) before anything else
        if (bedTools) {
            if (!hasShears && w.take(BedwarsShopItem.SHEARS)) { out.add(BedwarsShopItem.SHEARS); hasShears = true; }
            if (!hasPick && w.take(BedwarsShopItem.PICKAXE)) { out.add(BedwarsShopItem.PICKAXE); hasPick = true; }
        }

        // 2) defenders: end stone for the second layer, and obsidian for the bed once emeralds are coming in
        if (role == Role.DEFENDER && Inv.count(p, Inv.type(Material.ENDER_STONE)) < 12 && w.iron >= 24 + 4) {
            if (w.take(BedwarsShopItem.END_STONE)) out.add(BedwarsShopItem.END_STONE);
        }
        // a full obsidian wrap is 8 blocks = 8 emeralds (two purchases of four): the defender keeps buying until
        // the bed is wrapped, it is the one purchase that makes a bed last the whole game
        if (role == Role.DEFENDER && Inv.count(p, Inv.type(Material.OBSIDIAN)) < 8 && w.emerald >= 4
                && (gameSeconds > 60 || finalLife)
                && w.take(BedwarsShopItem.OBSIDIAN)) out.add(BedwarsShopItem.OBSIDIAN);
        // 3) sword
        if (sword < 2 && w.take(BedwarsShopItem.STONE_SWORD)) { out.add(BedwarsShopItem.STONE_SWORD); sword = 2; }
        if (sword < 3 && w.gold >= 7 + (armor < 3 ? 12 : 0) && w.take(BedwarsShopItem.IRON_SWORD)) { out.add(BedwarsShopItem.IRON_SWORD); sword = 3; }
        if (sword < 4 && w.emerald >= 4 && (tryhard || gameSeconds > 600) && w.take(BedwarsShopItem.DIAMOND_SWORD)) out.add(BedwarsShopItem.DIAMOND_SWORD);
        // 4) armor (permanent)
        if (armor < 4 && w.emerald >= 6 && w.take(BedwarsShopItem.DIAMOND_ARMOR)) { out.add(BedwarsShopItem.DIAMOND_ARMOR); armor = 4; }
        if (armor < 3 && w.take(BedwarsShopItem.IRON_ARMOR)) { out.add(BedwarsShopItem.IRON_ARMOR); armor = 3; }
        if (armor < 2 && w.iron >= 40 && (role != Role.RUSHER || gameSeconds > 90) && w.take(BedwarsShopItem.CHAINMAIL_ARMOR)) {
            out.add(BedwarsShopItem.CHAINMAIL_ARMOR); armor = 2;
        }
        // 5) tools
        if (role == Role.RUSHER && !hasShears && w.take(BedwarsShopItem.SHEARS)) out.add(BedwarsShopItem.SHEARS);
        if (!hasPick && (role != Role.DEFENDER || gameSeconds > 240) && w.iron >= 10 + (blocks < 16 ? 4 : 0) && w.take(BedwarsShopItem.PICKAXE)) out.add(BedwarsShopItem.PICKAXE);
        if (!hasAxe && tryhard && gameSeconds > 300 && w.take(BedwarsShopItem.AXE)) out.add(BedwarsShopItem.AXE);
        // 6) potions: emeralds that buy speed, jump and a bed run nobody sees coming
        if (role != Role.DEFENDER || gameSeconds > 300) {
            if (potions(p, org.bukkit.potion.PotionType.SPEED) < 1 && w.emerald >= 1 && gameSeconds > 120
                    && w.take(BedwarsShopItem.SPEED_POTION)) out.add(BedwarsShopItem.SPEED_POTION);
            if (potions(p, org.bukkit.potion.PotionType.JUMP) < 1 && w.emerald >= 1 && gameSeconds > 180
                    && (tryhard || role == Role.RUSHER) && w.take(BedwarsShopItem.JUMP_POTION)) out.add(BedwarsShopItem.JUMP_POTION);
            if (potions(p, org.bukkit.potion.PotionType.INVISIBILITY) < 1 && w.emerald >= 2
                    && (finalLife || role == Role.RUSHER || tryhard) && gameSeconds > 150
                    && w.take(BedwarsShopItem.INVISIBILITY_POTION)) out.add(BedwarsShopItem.INVISIBILITY_POTION);
        }
        // 6b) bridge egg: a bridge for one emerald when the blocks are short
        if (blocks < 24 && w.emerald >= 1 && gameSeconds > 120 && !Inv.has(p, Inv.BRIDGE_EGG)
                && w.take(BedwarsShopItem.BRIDGE_EGG)) out.add(BedwarsShopItem.BRIDGE_EGG);

        // 7) clutch kit once our bed is gone: knockback stick, pearls, more blocks, gapples, TNT
        if (finalLife) {
            if (!Inv.has(p, Inv.KNOCKBACK_STICK) && w.gold >= 5 && w.take(BedwarsShopItem.KNOCKBACK_STICK)) out.add(BedwarsShopItem.KNOCKBACK_STICK);
            if (!Inv.has(p, Inv.PEARL) && w.emerald >= 4 && w.take(BedwarsShopItem.ENDER_PEARL)) out.add(BedwarsShopItem.ENDER_PEARL);
            if (!Inv.has(p, Inv.TNT) && w.gold >= 4 + 3 && w.take(BedwarsShopItem.TNT)) out.add(BedwarsShopItem.TNT);
            if (blocks < 64 && w.iron >= 12 && w.take(BedwarsShopItem.WOOL)) out.add(BedwarsShopItem.WOOL);
            if (Inv.count(p, Inv.GAPPLE) < 2 && w.gold >= 3 && w.take(BedwarsShopItem.GOLDEN_APPLE)) out.add(BedwarsShopItem.GOLDEN_APPLE);
        }

        // 8) utility for sweats / late game
        if (tryhard || gameSeconds > 240) {
            if (armor >= 2 && Inv.count(p, Inv.FIREBALL) < 2 && w.iron >= 40 && w.take(BedwarsShopItem.FIREBALL)) out.add(BedwarsShopItem.FIREBALL);
            // pearls are a rush tool: 4 emeralds to land on the bed island when the bridge is contested or missing
            if ((tryhard || gameSeconds > 600 || (role == Role.RUSHER && gameSeconds > 90)) && !Inv.has(p, Inv.PEARL)
                    && w.emerald >= 4 && w.take(BedwarsShopItem.ENDER_PEARL)) out.add(BedwarsShopItem.ENDER_PEARL);
            if (role == Role.RUSHER && gameSeconds > 300 && Inv.count(p, Inv.TNT) < 1 && w.take(BedwarsShopItem.TNT)) out.add(BedwarsShopItem.TNT);
        }
        // A golden apple is the cheapest way back into a fight, and Scareshow's coaching is explicit: pre-gap before
        // a fight rather than eating during it. Three gold is affordable from the first rush on, so the gate is the
        // presence of gold, not the game clock - waiting 200 seconds meant bots entered every early fight with no
        // apple in the hotbar at all.
        boolean lowHp = p.getHealth() <= 16;
        if (Inv.count(p, Inv.GAPPLE) < (finalLife ? 2 : 1) && w.gold >= 3 && (gameSeconds > 60 || finalLife || lowHp)
                && w.take(BedwarsShopItem.GOLDEN_APPLE)) {
            out.add(BedwarsShopItem.GOLDEN_APPLE);
        }
        if (role == Role.FARMER && gameSeconds > 360 && !Inv.has(p, Inv.BOW) && w.gold >= 12 + 2 && w.take(BedwarsShopItem.BOW)) {
            out.add(BedwarsShopItem.BOW);
            if (w.take(BedwarsShopItem.ARROW)) out.add(BedwarsShopItem.ARROW);
        }
        // leftover iron -> more wool (people hoard blocks)
        if (blocks < 64 && w.iron >= 12 && armor >= 2 && w.take(BedwarsShopItem.WOOL)) out.add(BedwarsShopItem.WOOL);

        // the learned suggestion, on top of the hand-written plan
        if (mlCategory != null) {
            BedwarsShopItem extra = learnedPick(mlCategory, w, out, p, sword, armor, blocks);
            if (extra != null) out.add(extra);
        }
        return out;
    }

    /** The item that matches the category the shop model expects, if it is affordable and not already planned. */
    private static BedwarsShopItem learnedPick(int category, Wallet w, List<BedwarsShopItem> out, Player p,
                                               int sword, int armor, int blocks) {
        switch (category) {
            case net.h2020.bots.ai.ml.ShopModel.BLOCKS:
                return blocks < 64 && w.take(BedwarsShopItem.WOOL) ? BedwarsShopItem.WOOL : null;
            case net.h2020.bots.ai.ml.ShopModel.WEAPON:
                if (sword < 2 && w.take(BedwarsShopItem.STONE_SWORD)) return BedwarsShopItem.STONE_SWORD;
                if (sword < 3 && w.gold >= 7 && w.take(BedwarsShopItem.IRON_SWORD)) return BedwarsShopItem.IRON_SWORD;
                return null;
            case net.h2020.bots.ai.ml.ShopModel.ARMOR:
                if (armor < 2 && w.iron >= 40 && w.take(BedwarsShopItem.CHAINMAIL_ARMOR)) return BedwarsShopItem.CHAINMAIL_ARMOR;
                if (armor < 3 && w.gold >= 12 && w.take(BedwarsShopItem.IRON_ARMOR)) return BedwarsShopItem.IRON_ARMOR;
                return null;
            case net.h2020.bots.ai.ml.ShopModel.TOOLS:
                if (!Inv.has(p, Inv.SHEARS) && w.take(BedwarsShopItem.SHEARS)) return BedwarsShopItem.SHEARS;
                if (!Inv.has(p, Inv.PICKAXE) && w.take(BedwarsShopItem.PICKAXE)) return BedwarsShopItem.PICKAXE;
                return null;
            case net.h2020.bots.ai.ml.ShopModel.RANGED:
                if (!Inv.has(p, Inv.BOW) && w.gold >= 12 && w.take(BedwarsShopItem.BOW)) return BedwarsShopItem.BOW;
                return null;
            case net.h2020.bots.ai.ml.ShopModel.POTION:
                if (w.emerald >= 1 && w.take(BedwarsShopItem.SPEED_POTION)) return BedwarsShopItem.SPEED_POTION;
                if (w.emerald >= 1 && w.take(BedwarsShopItem.JUMP_POTION)) return BedwarsShopItem.JUMP_POTION;
                return null;
            case net.h2020.bots.ai.ml.ShopModel.UTILITY:
                if (!Inv.has(p, Inv.PEARL) && w.emerald >= 4 && w.take(BedwarsShopItem.ENDER_PEARL)) return BedwarsShopItem.ENDER_PEARL;
                if (w.iron >= 40 && w.take(BedwarsShopItem.FIREBALL)) return BedwarsShopItem.FIREBALL;
                return null;
            default:
                return null;
        }
    }

    /**
     * Team upgrades worth buying now, in priority order.
     *
     * <p>Scareshow, "101 tips" #19 and #32: if the bed is <b>not</b> under threat, Sharpness first - it costs one
     * diamond and leaves one for a trap or two for Protection. If the bed <b>is</b> in danger, Protection, a trap and
     * heal pool come first, because a bed that falls ends the game however sharp the sword is. Forge and Haste are
     * long-game purchases and never outrank the two that decide fights.
     */
    public static List<String> upgrades(Role role, boolean teams, boolean bedThreatened) {
        List<String> out = new ArrayList<>();
        if (bedThreatened) {
            out.add("protection"); out.add("trap_it_is_a_trap");
            if (teams) out.add("heal_pool");
            out.add("sharpness"); out.add("forge");
            if (!teams) out.add("haste");
        } else if (teams) {
            out.add("sharpness"); out.add("protection"); out.add("trap_it_is_a_trap");
            out.add("heal_pool"); out.add("forge");
        } else {
            out.add("sharpness"); out.add("protection"); out.add("forge");
            out.add("trap_it_is_a_trap"); out.add("haste");
        }
        return out;
    }

    /** Backwards-compatible overload: assume the bed is not under threat. */
    public static List<String> upgrades(Role role, boolean teams) {
        return upgrades(role, teams, false);
    }
}
