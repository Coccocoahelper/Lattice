package net.h2020.bedwars.shop;

import net.h2020.api.game.BedwarsShopItem.Currency;
import net.h2020.bedwars.BedwarsGame;
import net.h2020.bedwars.BwPlayer;
import net.h2020.bedwars.BwTeam;
import net.h2020.bedwars.util.Items;
import net.h2020.bedwars.util.Menu;
import net.h2020.bedwars.util.Text;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** "Upgrades & Traps" GUI (team / solo upgrades NPC) and its purchase logic. */
public final class UpgradeShop {
    private UpgradeShop() {}

    private static final Upgrade[] UPGRADE_ORDER = {Upgrade.SHARPNESS, Upgrade.PROTECTION, Upgrade.HASTE,
            Upgrade.FORGE, Upgrade.HEAL_POOL, Upgrade.DRAGON_BUFF};
    private static final int[] UPGRADE_SLOTS = {10, 11, 12, 19, 20, 21};
    private static final Trap[] TRAP_ORDER = {Trap.IT_IS_A_TRAP, Trap.COUNTER_OFFENSIVE, Trap.ALARM, Trap.MINER_FATIGUE};
    private static final int[] TRAP_SLOTS = {14, 15, 16, 23};
    private static final int[] QUEUE_SLOTS = {39, 40, 41};

    private static String diamonds(int n) { return ChatColor.AQUA + "" + n + (n == 1 ? " Diamond" : " Diamonds"); }

    // =================================================================== purchase

    public static boolean purchase(BedwarsGame g, Player p, String id) {
        BwPlayer bp = g.data(p);
        if (bp == null || !bp.playing() || bp.team() == null) return false;
        Upgrade u = Upgrade.byId(id);
        if (u != null) return buyUpgrade(g, bp, u);
        Trap t = Trap.byId(id);
        if (t != null) return buyTrap(g, bp, t);
        return false;
    }

    private static boolean buyUpgrade(BedwarsGame g, BwPlayer bp, Upgrade u) {
        Player p = bp.player();
        BwTeam team = bp.team();
        int lvl = team.upgrades().level(u);
        if (lvl >= u.maxLevel()) {
            p.sendMessage(ChatColor.RED + "You already unlocked this upgrade!");
            p.playSound(p.getLocation(), Sound.ENDERMAN_TELEPORT, 1f, 0.5f);
            return false;
        }
        int cost = u.cost(lvl + 1, g.bigMode());
        if (!pay(p, cost)) return false;
        team.upgrades().setLevel(u, lvl + 1);
        g.onUpgrade(team, u);
        g.broadcastTeam(team, ChatColor.GREEN + p.getName() + " purchased " + ChatColor.GOLD + u.purchaseName(lvl + 1));
        p.playSound(p.getLocation(), Sound.NOTE_PLING, 1f, 2f);
        return true;
    }

    private static boolean buyTrap(BedwarsGame g, BwPlayer bp, Trap t) {
        Player p = bp.player();
        BwTeam team = bp.team();
        List<Trap> q = team.upgrades().traps();
        if (q.size() >= Trap.MAX_QUEUE) {
            p.sendMessage(ChatColor.RED + "Trap queue full!");
            p.playSound(p.getLocation(), Sound.ENDERMAN_TELEPORT, 1f, 0.5f);
            return false;
        }
        if (!pay(p, Trap.cost(q.size()))) return false;
        q.add(t);
        g.broadcastTeam(team, ChatColor.GREEN + p.getName() + " purchased " + ChatColor.GOLD + t.displayName);
        p.playSound(p.getLocation(), Sound.NOTE_PLING, 1f, 2f);
        return true;
    }

    private static boolean pay(Player p, int cost) {
        int have = ItemShop.count(p, Currency.DIAMOND);
        if (have < cost) {
            p.sendMessage(ChatColor.RED + "You don't have enough Diamonds! Need " + (cost - have) + " more!");
            p.playSound(p.getLocation(), Sound.ENDERMAN_TELEPORT, 1f, 0.5f);
            return false;
        }
        ItemShop.take(p, Currency.DIAMOND, cost);
        return true;
    }

    // =================================================================== GUI

    public static void open(BedwarsGame g, Player p) {
        Menu m = new Menu("Upgrades & Traps", 5);
        render(m, g, p);
        m.open(p);
    }

    private static void render(final Menu m, final BedwarsGame g, Player p) {
        m.clear();
        BwPlayer bp = g.data(p);
        if (bp == null || bp.team() == null) return;
        BwTeam team = bp.team();
        int diamonds = ItemShop.count(p, Currency.DIAMOND);
        for (int i = 0; i < UPGRADE_ORDER.length; i++) {
            final Upgrade u = UPGRADE_ORDER[i];
            m.set(UPGRADE_SLOTS[i], upgradeIcon(g, team, u, diamonds), new Menu.Click() {
                @Override public void click(Player pl, ClickType type) {
                    purchase(g, pl, u.id);
                    render(m, g, pl);
                }
            });
        }
        int queued = team.upgrades().traps().size();
        for (int i = 0; i < TRAP_ORDER.length; i++) {
            final Trap t = TRAP_ORDER[i];
            m.set(TRAP_SLOTS[i], trapIcon(t, queued, diamonds), new Menu.Click() {
                @Override public void click(Player pl, ClickType type) {
                    purchase(g, pl, t.id);
                    render(m, g, pl);
                }
            });
        }
        for (int i = 27; i < 36; i++) {
            m.set(i, Items.of(Material.STAINED_GLASS_PANE, 1, 7).name(ChatColor.DARK_GRAY + "⬆ " + ChatColor.GRAY + "Purchasable")
                    .lore(ChatColor.DARK_GRAY + "⬇ " + ChatColor.GRAY + "Traps Queue").build());
        }
        List<Trap> q = team.upgrades().traps();
        for (int i = 0; i < QUEUE_SLOTS.length; i++) {
            String head = "Trap #" + (i + 1) + ": ";
            if (i < q.size()) {
                Trap t = q.get(i);
                List<String> lore = new ArrayList<>(Items.wrap(t.description, ChatColor.GRAY.toString(), 30));
                m.set(QUEUE_SLOTS[i], Items.of(t.icon).name(ChatColor.GREEN + head + t.displayName).lore(lore).hideAll().build());
            } else {
                List<String> lore = new ArrayList<>();
                lore.addAll(Items.wrap("The first enemy to walk into your base will trigger this trap!", ChatColor.GRAY.toString(), 30));
                lore.add("");
                lore.addAll(Items.wrap("Purchasing a trap will queue it here. Its cost will scale based on the number of traps queued.",
                        ChatColor.GRAY.toString(), 30));
                lore.add("");
                lore.add(ChatColor.GRAY + "Next trap: " + diamonds(Trap.cost(q.size())));
                m.set(QUEUE_SLOTS[i], Items.of(Material.STAINED_GLASS, 1, 8).name(ChatColor.RED + head + "No Trap!").lore(lore).build());
            }
        }
    }

    private static ItemStack upgradeIcon(BedwarsGame g, BwTeam team, Upgrade u, int diamonds) {
        int lvl = team.upgrades().level(u);
        boolean maxed = lvl >= u.maxLevel();
        int next = Math.min(lvl + 1, u.maxLevel());
        int cost = u.cost(next, g.bigMode());
        boolean afford = !maxed && diamonds >= cost;
        List<String> lore = new ArrayList<>(Items.wrap(u.description, ChatColor.GRAY.toString(), 30));
        lore.add("");
        String name;
        if (u.maxLevel() == 1) {
            lore.add(ChatColor.GRAY + "Cost: " + diamonds(cost));
            name = u.displayName;
        } else {
            for (int t = 1; t <= u.maxLevel(); t++) {
                ChatColor c = t <= lvl ? ChatColor.GREEN : ChatColor.GRAY;
                lore.add(c + "Tier " + t + ": " + u.tierNames[t - 1] + ", " + diamonds(u.cost(t, g.bigMode())));
            }
            name = u == Upgrade.FORGE ? Upgrade.FORGE_NAMES[next - 1] : u.displayName + " " + Text.roman(next);
        }
        lore.add("");
        if (maxed) lore.add(ChatColor.GREEN + (u.maxLevel() == 1 ? "UNLOCKED" : "MAXED!"));
        else if (afford) lore.add(ChatColor.YELLOW + "Click to purchase!");
        else lore.add(ChatColor.RED + "You don't have enough Diamonds!");
        ItemStack s = Items.of(u.icon).name((maxed || afford ? ChatColor.GREEN : ChatColor.RED) + name).lore(lore).hideAll().build();
        if (lvl > 0) {
            ItemMeta meta = s.getItemMeta();
            meta.addEnchant(org.bukkit.enchantments.Enchantment.DURABILITY, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            s.setItemMeta(meta);
        }
        s.setAmount(Math.max(1, Math.min(lvl + 1, u.maxLevel())));
        return s;
    }

    private static ItemStack trapIcon(Trap t, int queued, int diamonds) {
        int cost = Trap.cost(queued);
        boolean full = queued >= Trap.MAX_QUEUE;
        boolean afford = !full && diamonds >= cost;
        List<String> lore = new ArrayList<>(Items.wrap(t.description, ChatColor.GRAY.toString(), 30));
        lore.add("");
        lore.add(ChatColor.GRAY + "Cost: " + diamonds(cost));
        lore.add("");
        if (full) lore.add(ChatColor.RED + "Trap queue full!");
        else if (afford) lore.add(ChatColor.YELLOW + "Click to purchase!");
        else lore.add(ChatColor.RED + "You don't have enough Diamonds!");
        return Items.of(t.icon).name((afford ? ChatColor.GREEN : ChatColor.RED) + t.displayName).lore(lore).hideAll().build();
    }
}
