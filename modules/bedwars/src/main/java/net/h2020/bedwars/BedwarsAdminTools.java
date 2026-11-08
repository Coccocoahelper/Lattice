package net.h2020.bedwars;

import net.h2020.api.H2020;
import net.h2020.api.game.BedwarsTools;
import net.h2020.api.game.Game;
import net.h2020.api.game.GameManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** The Bed Wars side of the /h2020 admin commands, registered as a service by the bedwars plugin. */
public final class BedwarsAdminTools implements BedwarsTools {
    private final BedwarsModule module;
    BedwarsAdminTools(BedwarsModule module) { this.module = module; }

    private BedwarsGame game(Player p) {
        GameManager gm = H2020.find(GameManager.class);
        Game g = p == null || gm == null ? null : gm.gameOf(p);
        if (g instanceof BedwarsGame) return (BedwarsGame) g;
        if (gm != null) for (Game any : gm.games()) if (any instanceof BedwarsGame) return (BedwarsGame) any;
        return null;
    }

    @Override public void teamChest(CommandSender s, Player asPlayerOrNull) {
        BedwarsGame g = game(asPlayerOrNull);
        if (g == null) { s.sendMessage(ChatColor.RED + "No bedwars game."); return; }
        for (BwTeam t : g.teams) {
            StringBuilder labels = new StringBuilder();
            if (t.chest != null) {
                java.util.List<String> names = new java.util.ArrayList<String>();
                for (org.bukkit.entity.Entity en : t.chest.getWorld().getNearbyEntities(
                        t.chest.clone().add(0.5, 1.5, 0.5), 1.8, 1.8, 1.8)) {
                    if (en instanceof org.bukkit.entity.ArmorStand && en.getCustomName() != null)
                        names.add(ChatColor.stripColor(en.getCustomName()));
                }
                java.util.Collections.sort(names);
                labels.append(" labels=").append(names);
            }
            s.sendMessage(ChatColor.GRAY + "  " + t.color.displayName + ": " + (t.chest == null ? "none"
                    : t.chest.getBlockX() + "," + t.chest.getBlockY() + "," + t.chest.getBlockZ()
                    + " " + t.chest.getBlock().getType() + labels));
        }
    }

    @Override public void placeCheck(CommandSender s, Block b) {
        BedwarsGame g = game(s instanceof Player ? (Player) s : null);
        if (g == null) { s.sendMessage(ChatColor.RED + "No bedwars game in this world."); return; }
        String deny = g.placeDenial(b);
        s.sendMessage(deny == null ? ChatColor.GREEN + "placement allowed" : ChatColor.RED + "denied: " + deny);
    }

    @Override public void testDeposit(CommandSender s, Player tp, boolean ender) {
        BedwarsGame g = game(tp);
        if (g == null) { s.sendMessage(ChatColor.RED + "That player is not in a bedwars game."); return; }
        BwPlayer bp = g.data(tp);
        BwTeam team = bp.team;
        Block target;
        if (ender) {
            target = tp.getLocation().add(0, 2, 0).getBlock();
            target.setType(org.bukkit.Material.ENDER_CHEST);
        } else {
            if (team == null || team.chest == null) { s.sendMessage(ChatColor.RED + "No team chest for that player."); return; }
            target = team.chest.getBlock();
        }
        org.bukkit.Material[] mats = {org.bukkit.Material.WOOL, org.bukkit.Material.IRON_INGOT,
                org.bukkit.Material.GOLD_INGOT, org.bukkit.Material.DIAMOND, org.bukkit.Material.EMERALD};
        int[] amounts = {16, 5, 3, 2, 1};
        for (int i = 0; i < mats.length; i++) {
            tp.getInventory().clear();
            tp.getInventory().setItem(0, new org.bukkit.inventory.ItemStack(mats[i], amounts[i]));
            tp.getInventory().setHeldItemSlot(0);
            TeamChest.deposit(g, bp, target, tp);   // first of a pair fills the chest
            tp.getInventory().setItem(0, new org.bukkit.inventory.ItemStack(mats[i], amounts[i]));
            tp.getInventory().setHeldItemSlot(0);
            TeamChest.deposit(g, bp, target, tp);   // second proves the running total
        }
        org.bukkit.inventory.ItemStack hand = tp.getItemInHand();
        int stored = 0;
        org.bukkit.inventory.Inventory dest = ender ? tp.getEnderChest()
                : ((org.bukkit.block.Chest) target.getState()).getInventory();
        for (org.bukkit.inventory.ItemStack it : dest.getContents()) if (it != null) stored += it.getAmount();
        s.sendMessage(ChatColor.GREEN + "hand=" + (hand == null ? "empty" : hand.getType() + "x" + hand.getAmount())
                + " slot1=" + (tp.getInventory().getItem(1) == null ? "empty" : tp.getInventory().getItem(1).getAmount() + "x iron")
                + " stored=" + stored + " (" + (ender ? "ender" : "team") + " chest)");
        if (ender) target.setType(org.bukkit.Material.AIR);
    }

    @Override public void tower(CommandSender s, org.bukkit.Location at) {
        net.h2020.bedwars.world.PopUpTower.build(module.plugin(), s instanceof Player ? (Player) s : null, at, (byte) 11, 0f);
    }

    @Override public void reload() {
        net.h2020.bedwars.shop.ShopCatalog.load(new java.io.File(module.plugin().getDataFolder(), "shops/bedwars.yml"));
    }

    @Override public void generators(CommandSender s, Player asPlayerOrNull) {
        BedwarsGame g = game(asPlayerOrNull);
        if (g == null) { s.sendMessage(ChatColor.RED + "No game running."); return; }
        s.sendMessage(ChatColor.GREEN + "  map=" + g.mapName() + " type=" + g.type().id());
        s.sendMessage(ChatColor.GREEN + "  diamond=" + g.diamondGenerators().size()
                + " emerald=" + g.emeraldGenerators().size()
                + " registry diamond=" + net.h2020.api.game.GeneratorRegistry.points(g.world(),
                net.h2020.api.game.GeneratorRegistry.DIAMOND_GEN).size()
                + " emerald=" + net.h2020.api.game.GeneratorRegistry.points(g.world(),
                net.h2020.api.game.GeneratorRegistry.EMERALD_GEN).size());
    }
}
