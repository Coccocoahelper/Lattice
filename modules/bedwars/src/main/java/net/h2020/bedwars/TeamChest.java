package net.h2020.bedwars;

import net.h2020.api.stats.CombatStats;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Set;

/**
 * Punch your team's chest to deposit. Items you cannot use again (swords, tools, shears, compass, armour) stay with
 * you; everything else goes into the shared chest so a teammate can take it. An enemy chest refuses.
 */
public final class TeamChest {
    private static final Set<Material> KEEP = EnumSet.of(
            Material.WOOD_SWORD, Material.STONE_SWORD, Material.IRON_SWORD, Material.DIAMOND_SWORD, Material.GOLD_SWORD,
            Material.WOOD_PICKAXE, Material.STONE_PICKAXE, Material.IRON_PICKAXE, Material.DIAMOND_PICKAXE, Material.GOLD_PICKAXE,
            Material.WOOD_AXE, Material.STONE_AXE, Material.IRON_AXE, Material.DIAMOND_AXE, Material.GOLD_AXE,
            Material.SHEARS, Material.COMPASS,
            Material.LEATHER_HELMET, Material.LEATHER_CHESTPLATE, Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS,
            Material.CHAINMAIL_HELMET, Material.CHAINMAIL_CHESTPLATE, Material.CHAINMAIL_LEGGINGS, Material.CHAINMAIL_BOOTS,
            Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS,
            Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS);

    private TeamChest() {}

    /** The team that owns a chest block: the registered team chest first, then whoever's island it stands on. */
    public static BwTeam owner(BedwarsGame g, Block b) {
        for (BwTeam t : g.teams) {
            if (t.chest != null && t.chest.getWorld() == b.getWorld()
                    && t.chest.getBlockX() == b.getX() && t.chest.getBlockY() == b.getY()
                    && t.chest.getBlockZ() == b.getZ()) return t;
        }
        for (BwTeam t : g.teams) if (t.inBase(b.getLocation())) return t;
        return null;
    }

    /**
     * Punch a chest with a stack in hand: that stack, and only that stack, goes in. A team chest refuses strangers;
     * an ender chest is always yours. No cooldown: the click is the rate limit.
     */
    public static void deposit(BedwarsGame g, BwPlayer bp, Block block, Player p) {
        boolean ender = block.getType() == Material.ENDER_CHEST;
        if (!ender) {
            BwTeam owner = owner(g, block);
            if (owner != null && owner != bp.team) {
                CombatStats.inc("deposit.refused");
                p.sendMessage(net.h2020.api.core.Messages.get("bedwars.chest.not-yours",
                        "colour", owner.color.chat, "team", owner.color.displayName));
                return;
            }
        }
        ItemStack held = p.getItemInHand();
        if (held == null || held.getType() == Material.AIR) {
            net.h2020.api.core.Messages.send(p, "bedwars.teamchest.hold-the-stack-you");
            return;
        }
        if (KEEP.contains(held.getType())) {
            CombatStats.inc("deposit.refused");
            net.h2020.api.core.Messages.send(p, "bedwars.teamchest.you-keep-your-tools");
            return;
        }
        Inventory target;
        if (ender) {
            target = p.getEnderChest();
        } else {
            if (!(block.getState() instanceof Chest)) return;
            target = ((Chest) block.getState()).getInventory();
        }
        ItemStack moving = held.clone();
        java.util.Map<Integer, ItemStack> left = target.addItem(moving);
        if (left.isEmpty()) {
            p.setItemInHand(null);
            CombatStats.inc("deposit.stacks");
            p.playSound(p.getLocation(), ender ? Sound.PORTAL_TRAVEL : Sound.CHEST_OPEN, 0.5f, ender ? 1.9f : 1.6f);
            String msg = line(moving, moving.getAmount(), totalIn(target, moving.getType()), ender);
            p.sendMessage(msg);
            org.bukkit.Bukkit.getLogger().info("[deposit " + p.getName() + "] " + msg.replace('\u00a7', '&'));
        } else if (left.size() == 1 && left.values().iterator().next().getAmount() < moving.getAmount()) {
            // part of the stack fit: keep only what did not
            ItemStack rest = left.values().iterator().next();
            p.setItemInHand(rest);
            CombatStats.inc("deposit.stacks");
            p.sendMessage(net.h2020.api.core.Messages.get("bedwars.teamchest.left-in-hand","p1",line(moving, moving.getAmount() - rest.getAmount(),
                    totalIn(target, moving.getType()), ender),"p2",rest.getAmount()));
        } else {
            p.sendMessage(ChatColor.RED + net.h2020.api.core.Messages.raw("bedwars.chest.full", "chest", ender ? "ender chest" : "team chest"));
        }
    }

    /**
     * Hypixel's deposit line, assembled from the parts it is made of:
     * <pre>
     *   §7Deposited §fx16 Iron Ingots §7into §bTeam Chest §8(64 Total)
     *   §7Deposited §6x8 Gold Ingots §7into §5Ender Chest §8(24 Total)
     * </pre>
     * Iron is white, gold orange, diamond aqua, emerald green, anything else yellow. The noun pluralises past one, and
     * the bracketed figure is how much of that item the chest holds now, not what just went in.
     */
    static String line(ItemStack it, int amount, int total, boolean ender) {
        Material m = it.getType();
        String name;
        String col;
        boolean plural;
        if (m == Material.IRON_INGOT) { name = "Iron Ingot"; col = ChatColor.WHITE.toString(); plural = true; }
        else if (m == Material.GOLD_INGOT) { name = "Gold Ingot"; col = ChatColor.GOLD.toString(); plural = true; }
        else if (m == Material.DIAMOND) { name = "Diamond"; col = ChatColor.AQUA.toString(); plural = true; }
        else if (m == Material.EMERALD) { name = "Emerald"; col = ChatColor.GREEN.toString(); plural = true; }
        else {
            // everything else is a mass noun on Hypixel: "16 Wool", never "16 Wools"
            name = m == Material.WOOL ? woolName(it.getData().getData()) : pretty(m);
            col = ChatColor.YELLOW.toString();
            plural = false;
        }
        return net.h2020.api.core.Messages.get("bedwars.chest.deposited",
                "colour", col, "amount", amount, "item", name, "plural", plural && amount != 1 ? "s" : "",
                "chest", (ender ? ChatColor.DARK_PURPLE + "Ender Chest" : ChatColor.AQUA + "Team Chest"),
                "total", total);
    }

    /** How much of one item the chest holds now, counted after the deposit landed. */
    static int totalIn(Inventory inv, Material m) {
        int n = 0;
        for (ItemStack it : inv.getContents()) if (it != null && it.getType() == m) n += it.getAmount();
        return n;
    }

    private static final String[] DYE = {"White", "Orange", "Magenta", "Light Blue", "Yellow", "Lime", "Pink", "Gray",
            "Light Gray", "Cyan", "Purple", "Blue", "Brown", "Green", "Red", "Black"};

    private static String woolName(byte data) {
        return DYE[Math.max(0, Math.min(DYE.length - 1, data))] + " Wool";
    }

    private static String pretty(Material m) {
        String n = m.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
