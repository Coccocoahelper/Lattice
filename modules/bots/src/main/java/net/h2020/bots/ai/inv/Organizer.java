package net.h2020.bots.ai.inv;

import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.util.AIMath;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Own-inventory housekeeping with human pacing (window 0, no container open): equip better armor (shift-click the
 * worn piece out, then the new one in) and arrange the hotbar (sword 0, blocks 1, then bow/pearl/gapple...).
 * Each tick() does at most one click when its gap has elapsed; returns false when there is nothing left to do.
 */
public final class Organizer {
    private final BotContext ctx;
    private int wait;
    private int clicks;
    private final Inv.Pred[] layout;

    public Organizer(BotContext ctx) {
        this.ctx = ctx;
        // personal hotbar layout (sword first for everyone; the rest varies)
        // (the tail: fight utility kept on the hotbar so it can be used without opening the inventory mid-fight -
        // throwables, the clutch water bucket, lava, a potion)
        Inv.Pred water = Inv.type(org.bukkit.Material.WATER_BUCKET), lava = Inv.type(org.bukkit.Material.LAVA_BUCKET);
        Inv.Pred potion = new Inv.Pred() { public boolean test(org.bukkit.inventory.ItemStack i) { return Items.isPotion(i) && i.getDurability() != 0; } };
        if (ctx.persona.rodder) layout = new Inv.Pred[]{Inv.SWORD, Inv.ROD, Inv.BLOCK, Inv.BOW, Inv.GAPPLE, Inv.PEARL, Inv.THROWABLE, water, potion};
        else if (ctx.rng.nextBoolean()) layout = new Inv.Pred[]{Inv.SWORD, Inv.BLOCK, Inv.BOW, Inv.PEARL, Inv.GAPPLE, Inv.THROWABLE, water, lava, potion};
        else layout = new Inv.Pred[]{Inv.SWORD, Inv.BLOCK, Inv.GAPPLE, Inv.PEARL, Inv.BOW, water, Inv.THROWABLE, lava, potion};
    }

    public void reset() { wait = AIMath.range(ctx.rng, 2, 5); clicks = 0; }

    /** @return true while it still has work (it may have clicked this tick). */
    public boolean tick() {
        boolean own = Inv.ownInventoryOpen(ctx.player, ctx.c);
        if (ctx.c.containerOpen() && !own) return false; // a chest/shop is open: not our business
        if (wait > 0) { wait--; return true; }
        if (clicks <= 30 && (armorStep() || hotbarStep())) {
            clicks++;
            wait = Math.max(1, (int) Math.round(AIMath.gauss(ctx.rng, 170 - 90 * ctx.persona.skill.overall, 40, 60, 380) / 50.0));
            return true;
        }
        // done: close the inventory screen we opened (the first click opened it), or input stays swallowed
        if (own) ctx.c.closeWindow();
        return false;
    }

    /** Abort (e.g. a fight started): close the own inventory if it is open. */
    public void cancel() {
        if (Inv.ownInventoryOpen(ctx.player, ctx.c)) ctx.c.closeWindow();
        wait = 0;
    }

    public boolean needed() {
        return armorUpgrade() >= 0 || hotbarFix() != null;
    }

    private int armorUpgrade() {
        PlayerInventory inv = ctx.player.getInventory();
        double[] worn = Inv.wornArmor(ctx.player);
        int best = -1; double gain = 0.01;
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            Items.ArmorSlot s = Items.armorSlot(it);
            if (s == null) continue;
            double g = Items.armorValue(it) - worn[s.ordinal()];
            if (g > gain) { gain = g; best = i; }
        }
        return best;
    }

    private boolean armorStep() {
        int idx = armorUpgrade();
        if (idx < 0) return false;
        ItemStack it = ctx.player.getInventory().getItem(idx);
        Items.ArmorSlot s = Items.armorSlot(it);
        ItemStack[] a = ctx.player.getInventory().getArmorContents(); // boots, legs, chest, helmet
        ItemStack worn = a[3 - s.ordinal()];
        if (!Items.empty(worn)) {
            if (ctx.player.getInventory().firstEmpty() < 0) return false;
            ctx.c.clickWindow(Inv.rawArmorSlot(s), 0, 1); // take the old piece off
        } else {
            ctx.c.clickWindow(Inv.rawSlot(idx), 0, 1);     // shift-click the new piece on
        }
        return true;
    }

    /** {invIndex, hotbarSlot} of the next hotbar fix, or null. */
    private int[] hotbarFix() {
        PlayerInventory inv = ctx.player.getInventory();
        for (int slot = 0; slot < layout.length; slot++) {
            Inv.Pred p = layout[slot];
            ItemStack cur = inv.getItem(slot);
            if (p.test(cur)) {
                if (p == Inv.SWORD) {
                    // a better sword elsewhere?
                    int b = Inv.bestMeleeAnywhere(ctx.player);
                    if (b >= 0 && b != slot && Items.meleeValue(inv.getItem(b)) > Items.meleeValue(cur) + 0.1) return new int[]{b, slot};
                }
                continue;
            }
            int src = -1;
            if (p == Inv.SWORD) src = Inv.bestMeleeAnywhere(ctx.player);
            else for (int i = 0; i < 36; i++) {
                if (i < layout.length && layout[i] != null && layout[i].test(inv.getItem(i))) continue; // already placed
                if (p.test(inv.getItem(i))) { src = i; break; }
            }
            if (src >= 0 && src != slot) {
                // don't steal an item that already sits in its own layout slot
                if (src < layout.length && layout[src].test(inv.getItem(src)) && p != Inv.SWORD) continue;
                return new int[]{src, slot};
            }
        }
        return null;
    }

    private boolean hotbarStep() {
        int[] fix = hotbarFix();
        if (fix == null) return false;
        ctx.c.clickWindow(Inv.rawSlot(fix[0]), fix[1], 2);
        return true;
    }
}
