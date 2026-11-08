package net.h2020.bedwars.shop;

import net.h2020.api.game.BedwarsShopItem;
import net.h2020.api.game.BedwarsShopItem.Currency;
import net.h2020.bedwars.BedwarsGame;
import net.h2020.bedwars.BwPlayer;
import net.h2020.bedwars.shop.ShopCatalog.Entry;
import net.h2020.bedwars.shop.ShopCatalog.Price;
import net.h2020.bedwars.shop.ShopCatalog.ToolTier;
import net.h2020.bedwars.util.Items;
import net.h2020.bedwars.util.Menu;
import net.h2020.bedwars.util.Text;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The item shop GUI (Quick Buy + 7 categories) and the purchase logic shared with bots. */
public final class ItemShop {
    private ItemShop() {}

    /** The 21 item slots of every shop page. */
    public static final int[] SLOTS = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    // =================================================================== pricing / ownership

    /** Price of the next purchase of this item for this player, or null if it is owned / maxed. */
    public static Price price(BedwarsGame g, BwPlayer bp, BedwarsShopItem item) {
        switch (item) {
            case PICKAXE: return bp.pickaxeTier >= 4 ? null : ShopCatalog.PICKAXES[bp.pickaxeTier].price;
            case AXE: return bp.axeTier >= 4 ? null : ShopCatalog.AXES[bp.axeTier].price;
            case SHEARS: return bp.shears ? null : ShopCatalog.price(item, g.bigMode());
            case CHAINMAIL_ARMOR: return bp.armorTier >= 1 ? null : ShopCatalog.price(item, g.bigMode());
            case IRON_ARMOR: return bp.armorTier >= 2 ? null : ShopCatalog.price(item, g.bigMode());
            case DIAMOND_ARMOR: return bp.armorTier >= 3 ? null : ShopCatalog.price(item, g.bigMode());
            default: return ShopCatalog.price(item, g.bigMode());
        }
    }

    public static int count(Player p, Currency c) {
        Material m = ShopCatalog.material(c);
        int n = 0;
        for (ItemStack s : p.getInventory().getContents()) if (s != null && s.getType() == m) n += s.getAmount();
        return n;
    }

    public static void take(Player p, Currency c, int amount) {
        Material m = ShopCatalog.material(c);
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize() && amount > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s == null || s.getType() != m) continue;
            int t = Math.min(amount, s.getAmount());
            amount -= t;
            if (t >= s.getAmount()) inv.setItem(i, null);
            else { s.setAmount(s.getAmount() - t); inv.setItem(i, s); }
        }
    }

    public static boolean canAfford(BedwarsGame g, Player p, BedwarsShopItem item) {
        BwPlayer bp = g.data(p);
        if (bp == null || !bp.playing() || bp.team() == null) return false;
        Price pr = price(g, bp, item);
        return pr != null && count(p, pr.currency) >= pr.cost;
    }

    public static boolean purchase(BedwarsGame g, Player p, BedwarsShopItem item) {
        BwPlayer bp = g.data(p);
        if (bp == null || !bp.playing() || bp.team() == null) return false;
        Price pr = price(g, bp, item);
        if (pr == null) {
            p.sendMessage(ChatColor.RED + "You already have this item!");
            p.playSound(p.getLocation(), Sound.ENDERMAN_TELEPORT, 1f, 0.5f);
            return false;
        }
        int have = count(p, pr.currency);
        if (have < pr.cost) {
            p.sendMessage(ChatColor.RED + "You don't have enough " + ShopCatalog.name(pr.currency, 2) + "! Need "
                    + (pr.cost - have) + " more!");
            p.playSound(p.getLocation(), Sound.ENDERMAN_TELEPORT, 1f, 0.5f);
            return false;
        }
        take(p, pr.currency, pr.cost);
        String name = deliver(g, bp, item);
        p.sendMessage(ChatColor.GREEN + "You purchased " + ChatColor.GOLD + name);
        p.playSound(p.getLocation(), Sound.NOTE_PLING, 1f, 2f);
        return true;
    }

    /** Hands out the item and returns the name used in "You purchased X". */
    private static String deliver(BedwarsGame g, BwPlayer bp, BedwarsShopItem item) {
        Player p = bp.player();
        Entry e = ShopCatalog.entry(item);
        int sharp = bp.team().upgrades().level(Upgrade.SHARPNESS);
        PlayerInventory inv = p.getInventory();
        switch (item) {
            case PICKAXE: {
                bp.pickaxeTier++;
                ToolTier t = ShopCatalog.PICKAXES[bp.pickaxeTier - 1];
                replaceOrAdd(p, ShopCatalog.tool(t, sharp), true);
                return t.name;
            }
            case AXE: {
                bp.axeTier++;
                ToolTier t = ShopCatalog.AXES[bp.axeTier - 1];
                replaceOrAdd(p, ShopCatalog.tool(t, sharp), false);
                return t.name;
            }
            case SHEARS:
                bp.shears = true;
                add(p, ShopCatalog.give(item, bp.team().color(), 0));
                return e.name;
            case CHAINMAIL_ARMOR: bp.armorTier = 1; g.applyArmor(bp); return e.name;
            case IRON_ARMOR: bp.armorTier = 2; g.applyArmor(bp); return e.name;
            case DIAMOND_ARMOR: bp.armorTier = 3; g.applyArmor(bp); return e.name;
            case STONE_SWORD: case IRON_SWORD: case DIAMOND_SWORD: {
                ItemStack sword = ShopCatalog.give(item, bp.team().color(), sharp);
                int woodSlot = -1;
                for (int i = 0; i < inv.getSize(); i++) {
                    ItemStack s = inv.getItem(i);
                    if (s != null && s.getType() == Material.WOOD_SWORD) {
                        if (woodSlot < 0) woodSlot = i;
                        inv.setItem(i, null);
                    }
                }
                if (woodSlot >= 0) inv.setItem(woodSlot, sword); else add(p, sword);
                return e.name;
            }
            default:
                add(p, ShopCatalog.give(item, bp.team().color(), sharp));
                return e.name;
        }
    }

    private static void replaceOrAdd(Player p, ItemStack tool, boolean pickaxe) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s == null) continue;
            if (pickaxe ? Items.isPickaxe(s.getType()) : Items.isAxe(s.getType())) {
                inv.setItem(i, tool);
                return;
            }
        }
        add(p, tool);
    }

    private static void add(Player p, ItemStack s) {
        if (s == null) return;
        Map<Integer, ItemStack> left = p.getInventory().addItem(s);
        for (ItemStack l : left.values()) p.getWorld().dropItem(p.getLocation(), l);
    }

    // =================================================================== GUI

    public static void open(BedwarsGame g, Player p, ShopCategory cat) {
        Menu m = new Menu(cat == ShopCategory.QUICK_BUY ? "Quick Buy" : cat.displayName, 6);
        render(m, g, p, cat);
        m.open(p);
    }

    private static void render(final Menu m, final BedwarsGame g, final Player p, final ShopCategory cat) {
        m.clear();
        final BwPlayer bp = g.data(p);
        if (bp == null || bp.team() == null) return;
        for (final ShopCategory c : ShopCategory.values()) {
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.YELLOW + "Click to view!");
            ItemStack icon = Items.of(c.icon, 1, c.data).name(ChatColor.GREEN + c.displayName).lore(lore).hideAll().build();
            m.set(c.ordinal(), icon, new Menu.Click() {
                @Override public void click(Player pl, ClickType type) {
                    if (c != cat) open(g, pl, c);
                }
            });
        }
        for (int i = 0; i < 9; i++) {
            boolean sel = i == cat.ordinal();
            m.set(9 + i, Items.of(Material.STAINED_GLASS_PANE, 1, sel ? 13 : 7)
                    .name(ChatColor.DARK_GRAY + "⬆ " + ChatColor.GRAY + "Categories")
                    .lore(ChatColor.DARK_GRAY + "⬇ " + ChatColor.GRAY + "Items").build());
        }
        if (cat == ShopCategory.QUICK_BUY) {
            final BedwarsShopItem[] qb = g.module().quickBuy(p.getUniqueId());
            for (int i = 0; i < SLOTS.length; i++) {
                final int index = i;
                final BedwarsShopItem item = i < qb.length ? qb[i] : null;
                if (item == null) {
                    m.set(SLOTS[i], Items.of(Material.STAINED_GLASS_PANE, 1, 14).name(ChatColor.RED + "Empty slot!")
                            .lore(ChatColor.GRAY + "This is a Quick Buy Slot!", ChatColor.AQUA + "Sneak Click " + ChatColor.GRAY
                                    + "any item in", ChatColor.GRAY + "the shop to add it here.").build());
                    continue;
                }
                m.set(SLOTS[i], icon(g, bp, item, true), new Menu.Click() {
                    @Override public void click(Player pl, ClickType type) {
                        if (type.isShiftClick()) {
                            qb[index] = null;
                            render(m, g, pl, cat);
                            return;
                        }
                        purchase(g, pl, item);
                        render(m, g, pl, cat);
                    }
                });
            }
        } else {
            List<BedwarsShopItem> items = ShopCatalog.items(cat);
            for (int i = 0; i < items.size() && i < SLOTS.length; i++) {
                final BedwarsShopItem item = items.get(i);
                m.set(SLOTS[i], icon(g, bp, item, false), new Menu.Click() {
                    @Override public void click(Player pl, ClickType type) {
                        if (type.isShiftClick()) {
                            openAddQuickBuy(g, pl, item);
                            return;
                        }
                        purchase(g, pl, item);
                        render(m, g, pl, cat);
                    }
                });
            }
        }
    }

    /** "Adding to Quick Buy..." slot picker. */
    private static void openAddQuickBuy(final BedwarsGame g, Player p, final BedwarsShopItem item) {
        BwPlayer bp = g.data(p);
        if (bp == null || bp.team() == null) return;
        Menu m = new Menu("Adding to Quick Buy...", 6);
        m.set(4, icon(g, bp, item, false));
        final BedwarsShopItem[] qb = g.module().quickBuy(p.getUniqueId());
        for (int i = 0; i < SLOTS.length; i++) {
            final int index = i;
            BedwarsShopItem cur = i < qb.length ? qb[i] : null;
            ItemStack shown;
            if (cur == null) {
                shown = Items.of(Material.STAINED_GLASS_PANE, 1, 14).name(ChatColor.RED + "Empty slot!")
                        .lore(ChatColor.YELLOW + "Click to set!").build();
            } else {
                shown = icon(g, bp, cur, true);
                ItemMeta meta = shown.getItemMeta();
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.YELLOW + "Click to replace!");
                meta.setLore(lore);
                shown.setItemMeta(meta);
            }
            m.set(SLOTS[i], shown, new Menu.Click() {
                @Override public void click(Player pl, ClickType type) {
                    if (index < qb.length) qb[index] = item;
                    open(g, pl, ShopCategory.QUICK_BUY);
                }
            });
        }
        m.open(p);
    }

    private static final Material[] ARMOR_ICONS = {Material.CHAINMAIL_BOOTS, Material.IRON_BOOTS, Material.DIAMOND_BOOTS};

    /** Shop icon with Hypixel 2020 lore. */
    public static ItemStack icon(BedwarsGame g, BwPlayer bp, BedwarsShopItem item, boolean quickBuyPage) {
        Entry e = ShopCatalog.entry(item);
        Price pr = price(g, bp, item);
        boolean tiered = item == BedwarsShopItem.PICKAXE || item == BedwarsShopItem.AXE;
        ItemStack base;
        String name = e.name;
        Price shown;
        int tierShown = 0;
        if (tiered) {
            ToolTier[] arr = item == BedwarsShopItem.PICKAXE ? ShopCatalog.PICKAXES : ShopCatalog.AXES;
            int cur = item == BedwarsShopItem.PICKAXE ? bp.pickaxeTier : bp.axeTier;
            ToolTier t = arr[Math.min(cur, 3)];
            tierShown = Math.min(cur + 1, 4);
            base = ShopCatalog.tool(t, 0);
            name = t.name;
            shown = t.price;
        } else if (item == BedwarsShopItem.CHAINMAIL_ARMOR || item == BedwarsShopItem.IRON_ARMOR || item == BedwarsShopItem.DIAMOND_ARMOR) {
            base = new ItemStack(ARMOR_ICONS[item.ordinal() - BedwarsShopItem.CHAINMAIL_ARMOR.ordinal()]);
            shown = ShopCatalog.price(item, g.bigMode());
        } else {
            base = ShopCatalog.give(item, bp.team().color(), 0);
            shown = ShopCatalog.price(item, g.bigMode());
        }
        if (pr != null) shown = pr;
        boolean owned = pr == null;
        boolean afford = !owned && count(bp.player(), pr.currency) >= pr.cost;

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Cost: " + ShopCatalog.costText(shown));
        if (tiered) lore.add(ChatColor.GRAY + "Tier: " + ChatColor.YELLOW + Text.roman(tierShown));
        lore.add("");
        if (tiered) {
            lore.add(ChatColor.GRAY + "This is an upgradable item.");
            lore.add(ChatColor.GRAY + "It will lose 1 tier upon");
            lore.add(ChatColor.GRAY + "death!");
            lore.add("");
            lore.add(ChatColor.GRAY + "You will permanently");
            lore.add(ChatColor.GRAY + "respawn with at least the");
            lore.add(ChatColor.GRAY + "lowest tier.");
            lore.add("");
        } else if (!e.description.isEmpty()) {
            lore.addAll(Items.wrap(e.description, ChatColor.GRAY.toString(), 28));
            lore.add("");
        }
        lore.add(ChatColor.AQUA + (quickBuyPage ? "Sneak Click to remove from Quick Buy" : "Sneak Click to add to Quick Buy"));
        if (owned) lore.add(tiered ? ChatColor.GREEN + "MAXED!" : ChatColor.GREEN + "UNLOCKED");
        else if (afford) lore.add(ChatColor.YELLOW + "Click to purchase!");
        else lore.add(ChatColor.RED + "You don't have enough " + ShopCatalog.name(pr.currency, 2) + "!");

        ItemMeta meta = base.getItemMeta();
        if (meta != null) {
            meta.setDisplayName((owned || afford ? ChatColor.GREEN : ChatColor.RED) + name);
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_POTION_EFFECTS, ItemFlag.HIDE_UNBREAKABLE);
            base.setItemMeta(meta);
        }
        base.setAmount(Math.max(1, e.amount));
        return base;
    }
}
