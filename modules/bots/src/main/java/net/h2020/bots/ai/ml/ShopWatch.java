package net.h2020.bots.ai.ml;

import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.inv.Inv;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shops are events, not actions: when a player opens the item shop or the upgrades menu, what he is about to do can
 * be read from the 1.5 seconds <b>before</b> he opened it - where he was, what he was carrying, how he moved - plus
 * the currency in his bag right now.
 * <p>
 * Those shop visits are labels the server gets for free: diamonds in hand means the trip is for a team upgrade,
 * emeralds means a pearl or armour, a full iron pocket means blocks and gear. Each one trains the intent model on the
 * snapshot from before the menu opened, so "walks to the shop with 4 emeralds" starts predicting "buying a pearl"
 * without anybody being asked.
 */
public final class ShopWatch {
    private static long visits, upgrades, gear;
    private static final Map<UUID, Object[]> OPEN = new HashMap<UUID, Object[]>();
    private static final Map<UUID, Long> FIRST_SEEN = new HashMap<UUID, Long>();
    /** Who currently has a shop menu open (so the poll and the event cannot both count the same visit). */
    private static final Map<UUID, Boolean> OPEN_NOW = new HashMap<UUID, Boolean>();

    private ShopWatch() {}

    /** Somebody opened a shop/upgrades menu. */
    public static void observed(Player p) {
        if (p == null) return;
        if (OPEN_NOW.put(p.getUniqueId(), Boolean.TRUE) != null) return;      // already counted this visit
        net.h2020.api.stats.CombatStats.inc("shop.open");
        Long first = FIRST_SEEN.get(p.getUniqueId());
        if (first == null) { FIRST_SEEN.put(p.getUniqueId(), System.currentTimeMillis()); first = System.currentTimeMillis(); }
        int gameSeconds = (int) ((System.currentTimeMillis() - first) / 1000);
        boolean bedAlive = true, finalLife = false;
        net.h2020.api.game.GameManager gm = net.h2020.api.H2020.find(net.h2020.api.game.GameManager.class);
        net.h2020.api.game.Game g = gm == null ? null : gm.gameOf(p);
        if (g instanceof net.h2020.api.game.BedwarsView) {
            net.h2020.api.game.BedwarsView bw = (net.h2020.api.game.BedwarsView) g;
            net.h2020.api.game.GameTeam t = g.teamOf(p);
            if (t != null) { bedAlive = bw.bedAlive(t.color()); finalLife = !bedAlive; }
        }
        OPEN.put(p.getUniqueId(), new Object[]{System.currentTimeMillis(),
                ShopModel.features(p, bedAlive, finalLife, gameSeconds, 1), snapshot(p)});
        final Player who = p;
        Bukkit.getScheduler().runTaskLater(net.h2020.bots.ai.BotAI.plugin(), new Runnable() {
            @Override public void run() { settle(who); }
        }, 160L);                                                        // 8 s later: what did the trip actually buy
        // the state 1.5 s ago: what he was doing while deciding to come here
        double[] before = BehaviourModel.featuresBefore(p, 1500);
        int diamonds = Inv.count(p, Material.DIAMOND);
        int emeralds = Inv.count(p, Material.EMERALD);
        int gold = Inv.count(p, Material.GOLD_INGOT);
        int iron = Inv.count(p, Material.IRON_INGOT);

        int intent;
        if (diamonds >= 2) { intent = IntentModel.UPGRADE; upgrades++; }
        else if (emeralds >= 4) { intent = IntentModel.SHOP; gear++; }       // pearl / armour money
        else if (gold >= 7 || iron >= 12) { intent = IntentModel.SHOP; gear++; }
        else { intent = IntentModel.SHOP; }
        IntentModel.learnSnapshot(before, intent);
        QuestionBook.setObserved(p, QuestionBook.SHOPPING, true, 0.9);
        BehaviourModel.noteClass(p, BehaviourModel.SHOPPING);
        visits++;
        net.h2020.api.stats.CombatStats.inc("shop.human");
    }

    /**
     * Polled once every few ticks by the classical checks: if the top inventory is one of our menus and the player is
     * not a bot, that is a shop visit, whether or not the event handler sees the open. Clears the dedupe flag when the
     * window is closed, so the next visit counts again.
     */
    public static void poll(Player p) {
        if (p == null) return;
        if (p.getOpenInventory() != null && p.getOpenInventory().getTopInventory() != null)
            net.h2020.api.stats.CombatStats.inc("poll.menu");
        boolean open = p.getOpenInventory() != null
                && p.getOpenInventory().getTopInventory() != null
                && p.getOpenInventory().getTopInventory().getHolder() instanceof net.h2020.api.gui.Menu;
        if (open) {
            if (!net.h2020.bots.ai.BotAI.isBotPlayer(p)) observed(p);
        } else {
            OPEN_NOW.remove(p.getUniqueId());
        }
    }

    /** Compare the bag with the snapshot taken when the menu opened and label the model with the change. */
    private static void settle(Player p) {
        Object[] o = OPEN.remove(p.getUniqueId());
        if (o == null || p == null || !p.isOnline()) return;
        int[] before = (int[]) o[2];
        int[] now = snapshot(p);
        int what = classify(before, now);
        if (what < 0) return;                                             // nothing changed: no lesson
        net.h2020.api.train.Replay.log("human.shop", "\"x\":" + net.h2020.api.train.Replay.vec((double[]) o[1])
                + ",\"c\":" + what);
        ShopModel.learn((double[]) o[1], what);
    }

    /** The bag in one vector: iron, gold, diamond, emerald, blocks, sword tier, armour tier, tool bits, bow, potion. */
    private static int[] snapshot(Player p) {
        return new int[]{
                Inv.count(p, Material.IRON_INGOT), Inv.count(p, Material.GOLD_INGOT),
                Inv.count(p, Material.DIAMOND), Inv.count(p, Material.EMERALD),
                Inv.count(p, Inv.BLOCK), swordTier(p), armourTier(p),
                (Inv.has(p, Inv.SHEARS) ? 1 : 0) + (Inv.has(p, Inv.PICKAXE) ? 2 : 0) + (Inv.has(p, Inv.AXE) ? 4 : 0),
                Inv.has(p, Inv.BOW) ? 1 : 0, Inv.has(p, Inv.GAPPLE) ? 1 : 0
        };
    }

    private static int swordTier(Player p) {
        int t = 0;
        for (ItemStack i : p.getInventory().getContents()) {
            if (i == null) continue;
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

    private static int armourTier(Player p) {
        ItemStack b = p.getInventory().getBoots();
        if (b == null) return 0;
        switch (b.getType()) {
            case DIAMOND_BOOTS: return 4;
            case IRON_BOOTS: return 3;
            case CHAINMAIL_BOOTS: return 2;
            default: return 1;
        }
    }

    /** Which category the trip bought, from the difference. */
    private static int classify(int[] a, int[] b) {
        if (b[5] > a[5]) return ShopModel.WEAPON;
        if (b[6] > a[6]) return ShopModel.ARMOR;
        if (b[7] != a[7]) return ShopModel.TOOLS;
        if (b[8] > a[8]) return ShopModel.RANGED;
        if (b[9] > a[9]) return ShopModel.UTILITY;
        if (b[4] > a[4]) return ShopModel.BLOCKS;
        if ((a[1] > b[1] || a[3] > b[3]) && b[4] == a[4]) return ShopModel.POTION;   // gold/emeralds spent on consumables
        return -1;
    }

    public static List<String> report() {
        List<String> out = new ArrayList<String>();
        out.add(String.format("§6[ml] §eshop visits seen: §f%d§7 (%d upgrade trips, %d gear trips), learned from the 1.5 s before",
                visits, upgrades, gear));
        return out;
    }

    public static void reset() { visits = 0; upgrades = 0; gear = 0; }
}
