package net.h2020.bots.ai.items;

import net.h2020.api.bot.AimIntent;
import net.h2020.bots.ai.BotContext;
import net.h2020.api.stats.CombatStats;
import net.h2020.bots.ai.combat.Style;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.inv.Items;
import net.h2020.bots.ai.motor.BlockPlacer;
import net.h2020.bots.ai.motor.Motor;
import net.h2020.bots.ai.util.AIMath;
import net.h2020.bots.ai.world.Shape;
import net.h2020.bots.ai.world.WorldView;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.Potion;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

/**
 * Consumables and utility items in and around fights, the way a 2020 SkyWars / Bed Wars player uses them: speed /
 * strength potions at the start of a fight while the opponent is still coming, golden apples and regen when low and
 * not being hit (or in a panic when very low), a splash heal mid-fight, fire resistance when burning, lava and
 * flint &amp; steel at an opponent's feet in close range, TNT on a bridge someone is crossing (Bed Wars), and the
 * best weapon back in the hand afterwards. Fight items only come from the hotbar (no inventory clicks while moving
 * or hitting); a calm heal after a fight may pull a golden apple from the inventory while standing still.
 * Every action is a few ticks of real inputs: select the slot, move the crosshair, right-click / hold right.
 */
public final class ItemUse {
    private final BotContext ctx;
    private final Style style;

    /** Bed Wars: TNT allowed in fights. */
    public boolean allowTnt;
    public boolean allowLava = true, allowFire = true, allowPotions = true, allowGapple = true;

    // current action
    private int act;                 // 0 none, 1 hold-use (eat/drink), 2 splash self, 3 place (lava/fire/tnt), 4 lava pickup
    private int actTicks, actLimit;
    private Inv.Pred actItem;
    private String actName;
    private int tx, ty, tz;          // place target cell
    private Block support;
    private BlockFace face;
    private Location aimPt;
    private int clicked;

    private long lastEat = -10000, lastLava = -10000, lastFire = -10000, lastTnt = -10000, lastPot = -10000;
    private int lavaX, lavaY, lavaZ;
    private long lavaAt = -1;
    private long fightStart = -1;
    private boolean buffedThisFight;

    public ItemUse(BotContext ctx, Style style) { this.ctx = ctx; this.style = style; }

    public boolean busy() { return act != 0; }
    public boolean eating() { return act == 1; }

    public void cancel() {
        if (act != 0) backToWeapon();
        act = 0;
    }

    public void onFightStart() { fightStart = ctx.tick; buffedThisFight = false; }

    // ------------------------------------------------------------------------------------------- predicates

    @SuppressWarnings("deprecation")
    private static PotionType potionType(ItemStack i) {
        if (!Items.isPotion(i) || i.getDurability() == 0) return null;
        try { return Potion.fromItemStack(i).getType(); } catch (Throwable t) { return null; }
    }

    private static Inv.Pred potion(final PotionType type, final boolean splash) {
        return new Inv.Pred() {
            public boolean test(ItemStack i) { return potionType(i) == type && Items.isSplash(i) == splash; }
        };
    }

    private static final Inv.Pred HEAL_SPLASH = potion(PotionType.INSTANT_HEAL, true);
    private static final Inv.Pred HEAL_DRINK = potion(PotionType.INSTANT_HEAL, false);
    private static final Inv.Pred REGEN = potion(PotionType.REGEN, false);
    private static final Inv.Pred REGEN_SPLASH = potion(PotionType.REGEN, true);
    private static final Inv.Pred SPEED = potion(PotionType.SPEED, false);
    private static final Inv.Pred STRENGTH = potion(PotionType.STRENGTH, false);
    private static final Inv.Pred FIRE_RES = potion(PotionType.FIRE_RESISTANCE, false);
    private static final Inv.Pred JUMP_POT = potion(PotionType.JUMP, false);
    private static final Inv.Pred INVIS = potion(PotionType.INVISIBILITY, false);
    private static final Inv.Pred LAVA = Inv.type(Material.LAVA_BUCKET);
    private static final Inv.Pred BUCKET = Inv.type(Material.BUCKET);
    private static final Inv.Pred FLINT = Inv.type(Material.FLINT_AND_STEEL);

    private boolean hot(Inv.Pred p) { return Inv.findHotbar(ctx.player, p) >= 0; }

    // ------------------------------------------------------------------------------------------- fight

    /**
     * In a fight. @return true when an item action drives the inputs this tick (the caller should then only add
     * movement that does not fight it).
     */
    public boolean fightTick(Player target, double d, long lastHurt, boolean meleeBusy) {
        if (act != 0) return run(target);
        double hp = ctx.health();
        long sinceHurt = ctx.tick - lastHurt;
        double o = ctx.persona.skill.overall;
        Player me = ctx.player;

        // splash heal: the fastest heal there is, used mid-fight
        if (allowPotions && hp <= 5 + 4 * o && hot(HEAL_SPLASH) && ctx.tick - lastPot > 20) return startSplashSelf(HEAL_SPLASH, "pot.heal");
        if (allowPotions && hp <= 4 + 3 * o && hot(HEAL_DRINK) && ctx.tick - lastPot > 20) return startHold(HEAL_DRINK, 34, "pot.heal");
        // golden apple: low and not being hit right now (beginners panic-eat in melee)
        boolean regenOn = me.hasPotionEffect(PotionEffectType.REGENERATION);
        if (allowGapple && hot(Inv.GAPPLE) && ctx.tick - lastEat > 60 && (!regenOn || hp <= 5)) {
            boolean calmEnough = sinceHurt > 20 + (int) (20 * o) || d > 5;
            boolean panic = hp <= 5 || (o < 0.35 && hp <= 8 && ctx.rng.nextDouble() < 0.02);
            if ((hp <= 10 + 2 * o && calmEnough) || panic) { lastEat = ctx.tick; return startHold(Inv.GAPPLE, 34 + AIMath.range(ctx.rng, 0, 6), "gapple"); }
        }
        if (allowPotions && style.potions && hp <= 12 && sinceHurt > 40 && d > 6 && !regenOn && hot(REGEN) && ctx.tick - lastPot > 40)
            return startHold(REGEN, 34, "pot.regen");
        if (allowPotions && style.potions && hp <= 12 && sinceHurt > 20 && !regenOn && hot(REGEN_SPLASH) && ctx.tick - lastPot > 40)
            return startSplashSelf(REGEN_SPLASH, "pot.regen");
        // burning: fire resistance once there is a moment
        if (allowPotions && me.getFireTicks() > 20 && !me.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE) && d > 4.5 && hot(FIRE_RES))
            return startHold(FIRE_RES, 34, "pot.fireres");
        // fight start buffs while they are still coming
        if (allowPotions && style.potions && !buffedThisFight && fightStart >= 0 && ctx.tick - fightStart < 160 && d > 9 && d < 32 && sinceHurt > 40) {
            if (!me.hasPotionEffect(PotionEffectType.INCREASE_DAMAGE) && hot(STRENGTH)) { buffedThisFight = true; return startHold(STRENGTH, 34, "pot.strength"); }
            if (!me.hasPotionEffect(PotionEffectType.SPEED) && hot(SPEED)) { buffedThisFight = true; return startHold(SPEED, 34, "pot.speed"); }
            if (!me.hasPotionEffect(PotionEffectType.JUMP) && hot(JUMP_POT)) { buffedThisFight = true; return startHold(JUMP_POT, 34, "pot.jump"); }
        }
        if (meleeBusy && d < 2.6) return false; // in their face trading hits: just fight
        // lava at their feet
        if (allowLava && style.lava && ctx.tick - lastLava > 240 && d > 1.9 && d < 4.0 && hot(LAVA) && feetTargetable(target)
                && ctx.rng.nextDouble() < 0.15) {
            Location tl = target.getLocation();
            if (startPlace(LAVA, floor(tl.getX()), floor(tl.getY() + 0.01), floor(tl.getZ()), false, "lava")) {
                lastLava = ctx.tick;
                return true;
            }
        }
        // flint & steel
        if (allowFire && style.fire && ctx.tick - lastFire > 160 && d > 1.9 && d < 4.0 && target.getFireTicks() <= 0 && hot(FLINT)
                && feetTargetable(target) && ctx.rng.nextDouble() < 0.12) {
            Location tl = target.getLocation();
            if (startPlace(FLINT, floor(tl.getX()), floor(tl.getY() + 0.01), floor(tl.getZ()), false, "fire")) {
                lastFire = ctx.tick;
                return true;
            }
        }
        // TNT on the bridge they're crossing toward us (Bed Wars)
        if (allowTnt && ctx.persona.tryhard && ctx.tick - lastTnt > 300 && d > 2.6 && d < 5.5 && hot(Inv.TNT) && ctx.rng.nextDouble() < 0.1) {
            Location tl = target.getLocation();
            WorldView wv = ctx.world();
            int x = floor(tl.getX()), y = floor(tl.getY() + 0.01), z = floor(tl.getZ());
            if (wv.voidSides(x, y, z) >= 2) {
                // the bridge cell between them and us
                Location me2 = ctx.pos();
                double dx = me2.getX() - tl.getX(), dz = me2.getZ() - tl.getZ();
                int cx = Math.abs(dx) > Math.abs(dz) ? x + (int) Math.signum(dx) : x, cz = Math.abs(dx) > Math.abs(dz) ? z : z + (int) Math.signum(dz);
                if (Shape.passable(wv.shape(cx, y, cz)) && Shape.floor(wv.shape(cx, y - 1, cz)) && startPlace(Inv.TNT, cx, y, cz, true, "tnt")) {
                    lastTnt = ctx.tick;
                    return true;
                }
            }
        }
        // pick our lava back up once the fight moved on
        if (lavaAt > 0 && ctx.tick - lavaAt > 60 && ctx.tick - lavaAt < 600 && hot(BUCKET) && style.lava && o > 0.5 && d > 3.5) {
            Location me2 = ctx.eye();
            double dd = Math.sqrt((lavaX + 0.5 - me2.getX()) * (lavaX + 0.5 - me2.getX()) + (lavaY + 0.5 - me2.getY()) * (lavaY + 0.5 - me2.getY())
                    + (lavaZ + 0.5 - me2.getZ()) * (lavaZ + 0.5 - me2.getZ()));
            Material m = ctx.player.getWorld().getBlockAt(lavaX, lavaY, lavaZ).getType();
            if (m != Material.STATIONARY_LAVA && m != Material.LAVA) lavaAt = -1;
            else if (dd < 4.2) { act = 4; actTicks = 0; actLimit = 20; actName = "lava.pickup"; clicked = 0; return run(target); }
        }
        return false;
    }

    /** Out of fights: heal up after a fight when nobody is around. @return true when it drives the inputs. */
    public boolean calmTick() {
        if (act == 3) { finish(); return false; } // the fight is over: no lava / fire for nobody
        if (act != 0) return run(null);
        // invisibility for a run at a bed or through mid: drink it when somebody is around but nobody is close
        if (allowPotions && ctx.tick - lastPot > 160 && ctx.tick % 10 == 0 && hot(INVIS)
                && !ctx.player.hasPotionEffect(PotionEffectType.INVISIBILITY)
                && ctx.c.onGround() && !ctx.nav.doingAction()
                && ctx.perception.enemiesWithin(30) > 0 && ctx.perception.enemiesWithin(8) == 0) {
            lastPot = ctx.tick;
            return startHold(INVIS, 34, "pot.invis");
        }
        if (!allowGapple) return false;
        double hp = ctx.health();
        if (hp > 12 || ctx.tick - lastEat < 60 || !ctx.c.onGround() || ctx.nav.doingAction()) return false;
        if (ctx.perception.enemiesWithin(16) > 0) return false;
        if (ctx.c.containerOpen() && !Inv.ownInventoryOpen(ctx.player, ctx.c)) return false;
        if (ctx.player.hasPotionEffect(PotionEffectType.REGENERATION)) return false;
        if (ctx.tick % 10 != 0) return false;
        // hotbar only (the organizer puts gapples there; pulling one out of the inventory here clicked too fast)
        Inv.Pred what = hot(REGEN) ? REGEN : hot(Inv.GAPPLE) ? Inv.GAPPLE : null;
        if (what == null || Inv.ownInventoryOpen(ctx.player, ctx.c)) return false;
        lastEat = ctx.tick;
        return startHold(what, 34 + AIMath.range(ctx.rng, 0, 5), what == REGEN ? "pot.regen" : "gapple.calm");
    }

    private boolean feetTargetable(Player t) {
        Location tl = t.getLocation();
        WorldView wv = ctx.world();
        int x = floor(tl.getX()), y = floor(tl.getY() + 0.01), z = floor(tl.getZ());
        if (wv.shape(x, y, z) != Shape.OPEN || !Shape.floor(wv.shape(x, y - 1, z))) return false;
        // never next to our own feet (lava flows, fire spreads)
        Location me = ctx.pos();
        return Math.abs(floor(me.getX()) - x) + Math.abs(floor(me.getZ()) - z) >= 2 && Math.abs(floor(me.getY()) - y) <= 1;
    }

    // ------------------------------------------------------------------------------------------- actions

    private boolean startHold(Inv.Pred item, int ticks, String name) {
        int s = Inv.findHotbar(ctx.player, item);
        if (s < 0) return false;
        act = 1; actItem = item; actTicks = 0; actLimit = ticks; actName = name;
        if (ctx.c.heldSlot() != s) ctx.c.selectHotbar(s);
        CombatStats.inc(name);
        if (name.startsWith("pot")) lastPot = ctx.tick;
        return true;
    }

    private boolean startSplashSelf(Inv.Pred item, String name) {
        int s = Inv.findHotbar(ctx.player, item);
        if (s < 0) return false;
        act = 2; actItem = item; actTicks = 0; actLimit = 12; actName = name; clicked = 0;
        if (ctx.c.heldSlot() != s) ctx.c.selectHotbar(s);
        CombatStats.inc(name);
        lastPot = ctx.tick;
        return true;
    }

    private boolean startPlace(Inv.Pred item, int x, int y, int z, boolean needFree, String name) {
        Object[] s = BlockPlacer.findSupport(ctx.world(), x, y, z, ctx.eye(), 4.3, true);
        if (s == null) return false;
        if (needFree && !BlockPlacer.cellFree(new Location(ctx.player.getWorld(), x, y, z))) return false;
        int slot = Inv.findHotbar(ctx.player, item);
        if (slot < 0) return false;
        act = 3; actItem = item; actTicks = 0; actLimit = 16; actName = name; clicked = 0;
        tx = x; ty = y; tz = z;
        support = (Block) s[0]; face = (BlockFace) s[1]; aimPt = (Location) s[2];
        if (ctx.c.heldSlot() != slot) ctx.c.selectHotbar(slot);
        return true;
    }

    private boolean run(Player target) {
        actTicks++;
        switch (act) {
            case 1: { // eat / drink: hold right until the server finishes it
                if (!Inv.holding(ctx.player, actItem)) {
                    if (actTicks > 3 || Inv.findHotbar(ctx.player, actItem) < 0) return finish();
                    int s = Inv.findHotbar(ctx.player, actItem);
                    if (ctx.c.heldSlot() != s) ctx.c.selectHotbar(s);
                    return true;
                }
                ctx.motor.holdRight();
                if (actTicks >= actLimit) return finish();
                return true;
            }
            case 2: { // splash on ourselves: look down, throw
                ctx.motor.aimRotation(Motor.P_COMBAT + 3, ctx.c.yaw(), 86f + (float) ctx.rng.nextDouble() * 3, AimIntent.FLICK, 4.0);
                if (Inv.holding(ctx.player, actItem) && ctx.c.pitch() > 72 && clicked == 0) { ctx.c.rightClick(); clicked = 1; actLimit = actTicks + 2; }
                if (actTicks >= actLimit) return finish();
                return true;
            }
            case 3: { // place lava / fire / TNT into a cell
                if (!Inv.holding(ctx.player, actItem)) {
                    if (actTicks > 3) return finish();
                    return true;
                }
                if (target != null && actTicks % 4 == 0 && (actItem == LAVA || actItem == FLINT)) {
                    // they moved: follow their feet
                    Location tl = target.getLocation();
                    int x = floor(tl.getX()), y = floor(tl.getY() + 0.01), z = floor(tl.getZ());
                    if (x != tx || y != ty || z != tz) {
                        Object[] s = feetTargetable(target) ? BlockPlacer.findSupport(ctx.world(), x, y, z, ctx.eye(), 4.3, true) : null;
                        if (s == null) return finish();
                        tx = x; ty = y; tz = z; support = (Block) s[0]; face = (BlockFace) s[1]; aimPt = (Location) s[2];
                    }
                }
                ctx.motor.aimPoint(Motor.P_COMBAT + 3, aimPt, AimIntent.FLICK, 0.2);
                Block tb = ctx.c.targetedBlock();
                if (clicked == 0 && tb != null && tb.equals(support) && ctx.c.targetedFace() == face) {
                    ctx.c.rightClick();
                    clicked = 1;
                    CombatStats.inc(actName);
                    if (actItem == LAVA) { lavaX = tx; lavaY = ty; lavaZ = tz; lavaAt = ctx.tick; }
                    actLimit = actTicks + 2;
                }
                if (actTicks >= actLimit) return finish();
                return true;
            }
            case 4: { // lava pick-up with the empty bucket
                int s = Inv.findHotbar(ctx.player, BUCKET);
                if (s < 0) { lavaAt = -1; return finish(); }
                if (ctx.c.heldSlot() != s) { ctx.c.selectHotbar(s); return true; }
                ctx.motor.aimPoint(Motor.P_COMBAT + 3, new Location(ctx.player.getWorld(), lavaX + 0.5, lavaY + 0.3, lavaZ + 0.5), AimIntent.FLICK, 0.2);
                if (ctx.c.aimSettled() && clicked == 0) {
                    ctx.c.rightClick();
                    clicked = 1;
                    CombatStats.inc(actName);
                    lavaAt = -1;
                    actLimit = actTicks + 2;
                }
                if (actTicks >= actLimit) return finish();
                return true;
            }
            default:
                act = 0;
                return false;
        }
    }

    private boolean finish() {
        act = 0;
        clicked = 0;
        backToWeapon();
        return false;
    }

    /** The best weapon back in the hand. */
    public void backToWeapon() {
        int s = Inv.bestMeleeHotbar(ctx.player);
        if (s >= 0 && ctx.c.heldSlot() != s) ctx.c.selectHotbar(s);
    }

    private static int floor(double d) { int i = (int) d; return d < i ? i - 1 : i; }
}
