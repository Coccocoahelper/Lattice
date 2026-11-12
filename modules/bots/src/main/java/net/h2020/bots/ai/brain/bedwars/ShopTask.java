package net.h2020.bots.ai.brain.bedwars;

import net.h2020.api.stats.CombatStats;
import net.h2020.api.bot.AimIntent;
import net.h2020.api.game.BedwarsShopItem;
import net.h2020.api.game.TeamColor;
import net.h2020.bots.ai.brain.BedwarsBrain;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.motor.Motor;
import net.h2020.bots.ai.nav.NavOptions;
import net.h2020.bots.ai.util.AIMath;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * A shop (or team-upgrades) visit: walk to the NPC, look at it, right-click it so the GUI visibly opens, browse for
 * a human moment, buy a few things with click gaps (longer when switching categories), close.
 */
public final class ShopTask extends BwTask {
    private enum Phase { WALK, CLICK, BROWSE, BUY, CLOSE }

    private final boolean upgrades;
    private Phase phase = Phase.WALK;
    private long phaseStart;
    private int wait;
    private Entity npc;
    private int bought;
    private boolean tried;
    private BedwarsShopItem lastItem;
    private int upgradeIdx;

    public ShopTask(BedwarsBrain brain, boolean upgrades) {
        super(brain);
        this.upgrades = upgrades;
        this.phaseStart = ctx.tick;
    }

    @Override public String name() { return upgrades ? "upgrades" : "shop"; }

    /** How many things were bought on this trip. */
    /** Did it get as far as trying to buy something? (a plan that was empty from the start is not a failure) */
    public boolean tried() { return tried; }

    public int bought() { return bought; }

    private Location npcLocation() {
        TeamColor c = brain.color();
        if (c == null) return null;
        return upgrades ? view.upgradesNpc(c) : view.shopNpc(c);
    }

    private void phase(Phase p) { phase = p; phaseStart = ctx.tick; }

    @Override
    public boolean tick() {
        Location at = npcLocation();
        if (at == null) return false;
        long inPhase = ctx.tick - phaseStart;
        switch (phase) {
            case WALK: {
                double d = ctx.pos().distance(at);
                if (d < 3.1) { ctx.nav.stop(); npc = findNpc(at); phase(Phase.CLICK); return true; }
                ctx.nav.goTo(at, 2.4, NavOptions.walk());
                ctx.nav.tick();
                if (ctx.nav.failed() || age() > 20 * 40) return false;
                return true;
            }
            case CLICK: {
                if (npc != null && !npc.isDead()) {
                    ctx.motor.aimEntity(Motor.P_TASK, npc, AimIntent.PRECISE);
                    if (ctx.c.targetedEntity() == npc && inPhase > 2) {
                        Inv.equip(ctx.player, ctx.c, Inv.SWORD, 0);
                        ctx.c.rightClick();
                        phase(Phase.BROWSE);
                        wait = AIMath.msToTicks(ctx.rng, 600 - 330 * ctx.persona.skill.overall);
                        return true;
                    }
                }
                if (inPhase > 30) { phase(Phase.BROWSE); wait = AIMath.range(ctx.rng, 4, 12); }
                return true;
            }
            case BROWSE:
                if (inPhase >= wait) { phase(Phase.BUY); wait = 0; }
                return true;
            case BUY: {
                if (inPhase < wait) return true;
                tried = true;
                boolean did = upgrades ? buyUpgrade() : buyItem();
                if (!did) {
                    net.h2020.api.stats.CombatStats.inc("shop.nothing");
                    phase(Phase.CLOSE); wait = AIMath.range(ctx.rng, 2, 6); return true;
                }
                net.h2020.api.stats.CombatStats.inc("shop.ok");
                bought++;
                phaseStart = ctx.tick;
                wait = Math.max(2, AIMath.msToTicks(ctx.rng, 320 - 160 * ctx.persona.skill.overall));
                return true;
            }
            case CLOSE:
            default:
                if (inPhase >= wait) {
                    if (ctx.c.containerOpen()) ctx.c.closeWindow();
                    Inv.equip(ctx.player, ctx.c, Inv.SWORD, 0);
                    return false;
                }
                return true;
        }
    }

    private boolean buyItem() {
        if (bought > 12) return false;
        Player p = ctx.player;
        net.h2020.api.stats.CombatStats.inc("shop.plan");
        Integer mlPick = net.h2020.bots.ai.ml.ShopModel.preferred(ctx, brain.bedAliveNow(), brain.finalLife(),
                brain.gameSeconds(), brain.roleIndexPublic());
        List<BedwarsShopItem> plan = ShopPlanner.plan(p, brain.role(), ctx.persona.tryhard, brain.gameSeconds(),
                brain.finalLife(), brain.needsBedTools(), mlPick);
        for (BedwarsShopItem it : plan) {
            if (!view.canAfford(p, it)) continue;
            if (view.purchase(p, it)) {
                if (lastItem != null && category(lastItem) != category(it)) wait += AIMath.range(ctx.rng, 3, 9);
                lastItem = it;
                return true;
            }
        }
        return false;
    }

    private boolean buyUpgrade() {
        // Scareshow #19/#32: a threatened bed buys protection and a trap before sharpness, because losing the bed
        // ends the game however sharp the sword is.
        boolean bedThreatened = brain.bedAliveNow() && brain.enemyNearBed();
        List<String> ups = ShopPlanner.upgrades(brain.role(), game().type().teamSize() > 1, bedThreatened);
        while (upgradeIdx < ups.size()) {
            String id = ups.get(upgradeIdx);
            if (view.purchaseUpgrade(ctx.player, id)) {
                if (brain.teamMode()) ctx.chat.callout(calloutFor(id), 0.35);
                return true;
            }
            upgradeIdx++;
        }
        return false;
    }

    private static String calloutFor(String id) {
        switch (id) {
            case "sharpness": return "got sharp";
            case "protection": return "got prot";
            case "forge": return "forge upgraded";
            case "heal_pool": return "heal pool";
            default: return "bought trap";
        }
    }

    private net.h2020.api.game.Game game() { return view; }

    private static int category(BedwarsShopItem it) {
        switch (it) {
            case WOOL: case HARDENED_CLAY: case BLAST_PROOF_GLASS: case END_STONE: case LADDER: case WOOD: case OBSIDIAN: return 0;
            case STONE_SWORD: case IRON_SWORD: case DIAMOND_SWORD: case KNOCKBACK_STICK: return 1;
            case CHAINMAIL_ARMOR: case IRON_ARMOR: case DIAMOND_ARMOR: return 2;
            case SHEARS: case PICKAXE: case AXE: return 3;
            case ARROW: case BOW: case POWER_BOW: case PUNCH_BOW: return 4;
            case SPEED_POTION: case JUMP_POTION: case INVISIBILITY_POTION: return 5;
            default: return 6;
        }
    }

    private Entity findNpc(Location at) {
        Entity best = null;
        double bd = 9;
        for (Entity e : at.getWorld().getNearbyEntities(at, 3, 3, 3)) {
            if (!(e instanceof LivingEntity) || e instanceof Player) continue;
            double d = e.getLocation().distanceSquared(at);
            if (d < bd) { bd = d; best = e; }
        }
        return best;
    }

    @Override
    public boolean interruptible() { return phase == Phase.WALK; }
}
