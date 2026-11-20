package net.h2020.bots.ai.brain;

import net.h2020.api.bot.BotBrain;
import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.motor.Motor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * A brain whose whole job is to survive being knocked off: it stands still, keeps blocks in hand, and lets the combat
 * stack's clutch do the work.
 *
 * <p>{@code Clutch} already runs from {@code Combat.tick()}, and it is real crosshair work - flick down at the edge,
 * right-click when the ray hits a support face, chain outward if the knock carries you further. So the brain does not
 * reimplement any of that. What it does is everything a drill needs from a body that is not fighting:
 *
 * <ul>
 *   <li>hold blocks in the hotbar slot the clutch reaches for, and keep the count topped up</li>
 *   <li>stay on the platform instead of wandering off it (a lobby brain walks off and falls, which is what made the
 *       first fireball lane read as a bot that could not clutch)</li>
 *   <li>report each attempt so the drill's console line is not the only evidence</li>
 * </ul>
 *
 * <p>It never attacks. A clutch drill is not a fight, and a brain that swings at the archer would fail the drill for
 * the wrong reason.
 */
public final class ClutchBrain extends BaseBrain implements EmergencyBrain {

    private Location home;
    private int tick;
    private int lastBlocks = -1;
    /** Ticks of free-fall already seen in the current attempt, to tell "falling" from "landed". */
    private int fallTicks;

    /** @param home the block the bot should defend its footing on (the platform centre). */
    public ClutchBrain(Location home) { this.home = home == null ? null : home.clone(); }

    @Override
    protected void onActive() {
        if (home == null) home = ctx.pos().clone();
        ctx.combat.clutchMode = true;              // the clutch kit stays in hand
        holdBlocks();
    }

    @Override
    protected void think() {
        Player p = ctx.player;
        tick++;

        // blocks in hand, topped up: a clutch with an empty hand is not a clutch
        int blocks = Inv.count(p, Inv.BLOCK);
        if (blocks != lastBlocks) {
            lastBlocks = blocks;
            if (blocks < 8) { giveBlocks(p); }
        }
        int held = p.getInventory().getHeldItemSlot();
        if (!isBlock(p.getInventory().getItem(held))) holdBlocks();

        // the combat stack owns the aim and the clicks here: Clutch runs from inside it
        ctx.combat.tick();

        // Stay on the platform - but only while standing on it. The first version of this walked the body home
        // whenever it was more than 2.5 blocks out, which cancelled the knock the drill had just applied: the bot
        // hovered at y=73.3 with vy=0.26 for twenty-five seconds, never falling and never clutching. A body that is
        // airborne is a body in a clutch, and the clutch owns its movement until it lands.
        if (home != null && p.getWorld().equals(home.getWorld()) && p.isOnGround() && ctx.combat.target() == null) {
            double d = p.getLocation().distance(home);
            if (d > 2.5) {
                double ang = Math.atan2(home.getZ() - p.getLocation().getZ(), home.getX() - p.getLocation().getX());
                ctx.motor.moveToward(Math.cos(ang), Math.sin(ang));
                ctx.motor.sprint(d > 4);
            }
        }

        // report the fall, so a run leaves evidence beyond the drill's own counters
        if (!p.isOnGround() && p.getLocation().getY() < (home == null ? p.getLocation().getY() : home.getY() - 0.5)) {
            if (++fallTicks == 6) {
                net.h2020.api.stats.CombatStats.inc("clutchbrain.falling");
            }
        } else if (fallTicks > 0) {
            if (p.isOnGround()) {
                net.h2020.api.stats.CombatStats.inc(p.getLocation().getY() < home.getY() - 1
                        ? "clutchbrain.saved" : "clutchbrain.landed");
            }
            fallTicks = 0;
        }
    }

    @Override
    protected void onInactive() {
        if (ctx != null) ctx.combat.clutchMode = false;
    }

    private static boolean isBlock(ItemStack s) {
        return s != null && s.getType().isBlock() && s.getType() != org.bukkit.Material.AIR;
    }

    /** Put wool in the first hotbar slot and select it: the slot the clutch reaches for first. */
    private void holdBlocks() {
        Player p = ctx.player;
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (isBlock(s)) { p.getInventory().setHeldItemSlot(i); return; }
        }
        giveBlocks(p);
        p.getInventory().setHeldItemSlot(0);
    }

    private void giveBlocks(Player p) {
        p.getInventory().setItem(0, new ItemStack(org.bukkit.Material.WOOL, 64));
        p.getInventory().setHeldItemSlot(0);
        p.updateInventory();
    }

    @Override public String toString() { return "ClutchBrain"; }
}
