package net.h2020.bots.ai.combat;

import net.h2020.api.stats.CombatStats;
import net.h2020.api.bot.AimIntent;
import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.inv.Inv;
import net.h2020.bots.ai.inv.Items;
import net.h2020.bots.ai.items.ItemUse;
import net.h2020.bots.ai.motor.Motor;
import net.h2020.bots.ai.nav.NavOptions;
import net.h2020.bots.ai.perception.Sighting;
import net.h2020.bots.ai.util.AIMath;
import net.h2020.bots.ai.world.Raycast;
import net.h2020.bots.ai.world.Shape;
import net.h2020.bots.ai.world.WorldView;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * 1.8 PvP: target tracking, sprint approach, A/D strafing with human switch timing, W-tap/S-tap sprint resets after
 * landed hits, occasional jump crits, block-hitting, keeping the combo distance (~2.8-3.0), bow with draw time and
 * leading/arrow drop, snowballs/eggs and fireballs to knock bridgers into the void, deliberate void-side
 * positioning, golden apples and retreating at low health, pearls. Skill comes from the persona; the controller's
 * human aim model does the actual mouse work.
 * <p>
 * The 2020 behaviour set ({@link CombatStats#v2}, default on) adds, scaled by skill ({@link Style}): circle strafing
 * that keeps the void out of your back, jump-resets on incoming hits, combo-locking a target in hitstun, breaking
 * out of a combo (strafe switch / rod reset), hit-and-run against better-geared players, block / water / pearl
 * clutches when knocked off ({@link Clutch}), towering up and walling or cutting bridges when fleeing
 * ({@link BlockTactics}), potions / gapples / lava / flint &amp; steel / TNT ({@link ItemUse}), ballistic projectile
 * aim with lead and per-throw error ({@link Ballistics}), punch-bow and snowball spam at people on edges, and
 * pearls to escape, to cross a gap to someone unreachable, and to get back from the void.
 */
public final class Combat {
    private final BotContext ctx;
    public final Style style;
    private final Clutch clutch;
    private final MovementTricks tricks;
    private final LadderAssault ladders;
    private final BlockTactics tactics;
    private long lastBlockEquip = -100;
    private final FireballGuard fireballs;
    private final BedRaid bedRaid;
    private final net.h2020.bots.ai.combat.BaitTrap baitTrap;
    public final ItemUse items;
    private Player target;
    private long engagedAt;

    /**
     * The bed this bot may go for while it fights, supplied by the Bed Wars brain (null elsewhere). Read live: the bed
     * can be destroyed or the target team can change between decisions.
     */
    public interface BedInfo {
        Location foot();
        boolean alive();
    }
    public BedInfo bedInfo;

    // options from the brain
    public boolean allowRod = true;
    public boolean allowBow = true, allowThrowables = true, allowFireball = false, allowPearl = false, allowGapple = true;
    public boolean allowChase = true;
    /** Our bed is gone (set by the Bed Wars brain): trade kills and beds, and use the clutch kit. */
    public boolean clutchMode;
    public NavOptions chaseOptions = chase(NavOptions.walk());

    /** Chasing must not path around the very player we chase. */
    public static NavOptions chase(NavOptions o) { o.avoidEnemies = false; return o; }
    /** Do not chase further than this from where the fight started (stay near the bed, the island...). */
    public double leash = 40;
    private Location anchor;

    // melee state
    private int strafeDir = 1;
    private long nextStrafeSwitch;
    private int wtapTicks;
    /** Arbiter window: when the ML mechanic was chosen and the hit counters at that moment. */
    private long arbAt = -1L, arbLanded0, arbTaken0;
    private long lastSafetyTry = -10000;
    private long lastWeaponSwap = -10000;
    private long lastFakeSwing = -10000;
    private long lastClutchTick = -10000;
    private double fightDistSum;
    private int fightDistN;
    // damage actually traded, not just hit counts: a crit and a tap are not the same hit, and fall damage is a real cost
    private double dmgDealt, dmgTaken;
    private double prevTargetHp = -1;
    private double prevMyHp = -1;
    private int dmgTargetId = Integer.MIN_VALUE;
    private double duelDmgD0, duelDmgT0;
    private double arbDmgD0, arbDmgT0;
    private long hitSelectTicks = -1;
    private long lastGenRetreat = -10000;
    /** The strafe decision the arbiter made for this stance window. */
    private boolean arbStrafe;
    private long arbStrafeUntil = -1L;
    private boolean sTapNow;
    private int blockHitTicks;
    private int critCooldown;
    private double cpsNow;
    private long lastHitLanded = -1000, lastHurt = -1000;
    private long lastTurnInto = -1000;
    private long lastSprintCancel = -1000;
    private long comboUntil = -1000;
    /** Hits landed while strafing each way: the side that keeps landing is the side worth holding. */
    private int hitsLeft, hitsRight;
    /** The burst/W-tap/strafe combo timing. */
    private final net.h2020.bots.ai.combat.ZestTap zest = new net.h2020.bots.ai.combat.ZestTap();
    private Player lastHitVictim;
    private int comboCount;
    private int hitsTaken;           // hits taken in a row without landing one
    private boolean breakOut;        // switch strafe direction now (being comboed)
    private int runTicks;            // hit-and-run: backing out
    private double gearDiff;         // target gear - our gear
    private long gearTick = -100;
    // learned fight judgement (FightModel): features of this fight captured at first reach, and the live prediction
    private double[] fightFeatures;
    private double fightPred = 0.5;
    private double pWin = 0.5;
    private long pWinTick = -100;
    // aim profile for this fight (AimPolicy) and the hit-rate bookkeeping it learns from
    private int aimArm = 1;
    private int hitsLanded, hitsAtStart, swingsAtStart;
    private boolean fightScored;
    private long flowTick = -100;
    // duel stance (DuelPolicy): one stance is held for a window of ~10 ticks and scored when the window ends
    private int duelStance = -1;
    private long duelUntil = -100;
    private double[] duelX;
    private int duelHits0, duelTaken0;
    private boolean edgeBehind;
    /** Solid blocks right behind us (the side we would be knocked to): nowhere to back off. */
    private boolean wallBehind;
    /** Hit-select: every now and then the first swing is theirs on purpose, and ours lands into their recovery. */
    /**
     * The footwork half of zest tapping: W is released for the W-tap (so sprint resets with the hit) and the bot
     * strafes for the strafe window, flipping side on every reset. Never over void: the safety check comes first.
     */
    private void applyZest(Location me) {
        if (!net.h2020.bots.ai.combat.ZestTap.enabled() || !engaged()) return;
        // grounded combo: the jump key is off for the whole engagement, whatever the stance or a knockback reset wanted
        ctx.motor.jump(false);
        CombatStats.inc("zest.grounded");
        if (zest.wtapping(ctx.tick)) {
            // the W-tap is a forward release, not a full stop: keep the body sliding sideways so the bot is not a
            // statue for three ticks every hit
            ctx.motor.move(0, zest.strafeDir());
            CombatStats.inc("zest.wtap.active");
            return;
        }
        if (zest.strafing(ctx.tick)) {
            double rad = Math.toRadians(ctx.c.yaw());
            double lx = Math.cos(rad), lz = -Math.sin(rad);
            int dir = zest.strafeDir();
            if (safe(me, lx * dir, lz * dir)) {
                ctx.motor.move(0, dir);
                CombatStats.inc("zest.strafe.active");
            }
        }
    }

    private void fire(double cps) {
        // zest tapping: the click only exists inside the burst window around the immunity reset
        if (net.h2020.bots.ai.combat.ZestTap.enabled() && engaged() && !zest.inBurst(ctx.tick)) {
            // point blank is not a timing question: when they are inside the hitbox the swing always goes out,
            // otherwise a knocked-back victim leaves reach and the chain dies on the window
            double dist = target == null ? 99 : ctx.pos().distance(target.getLocation());
            if (dist > 2.2) {
                ctx.motor.holdLeft(0);
                CombatStats.inc("zest.hold");
                return;
            }
            CombatStats.inc("zest.pointblank");
        }
        if (hitSelectTicks >= 0) {
            if (ctx.tick < hitSelectTicks) { ctx.motor.holdLeft(0); return; }
            hitSelectTicks = -1;
            CombatStats.inc("trick.hitselect");
        }
        ctx.motor.holdLeft(cps);
    }

    /** Low and losing with that wall at our back: plant and fight instead of retreating. */
    private boolean corneredFight;
    /** Under somebody's drop / on an exposed line: a fight not worth taking standing still. */
    private boolean badPos;

    // knockback measurement (stats)
    private Location kbFrom;
    private double kbUx, kbUz;
    private long kbTick = -1;
    private boolean kbJumped;

    // ranged / items state
    private int eatTicks;
    private long lastEat = -10000;
    private int drawTicks, drawTarget;
    private int rangedCooldown;
    private int throwsLeft;
    private boolean usingBowThisFight;
    private boolean pearlUsed;
    private int fireballCooldown;
    private int rodTicks, rodCooldown;
    private int rodWant;             // v2: rod selected, cast once it's in the hand
    private String rodStat;

    private boolean retreating;
    // progress toward the target: a fight that never gets into reach (target across the void, behind a wall, no path)
    // is given up instead of standing there forever
    private double bestDist;
    private long bestDistTick;
    private final java.util.Map<java.util.UUID, Long> ignoreUntil = new java.util.HashMap<>();
    private int losCache = Raycast.CLEAR;
    private long losTick = -100;

    public Combat(BotContext ctx) {
        this.ctx = ctx;
        this.style = new Style(ctx.persona);
        this.clutch = new Clutch(ctx, style);
        this.tricks = new MovementTricks(ctx);
        this.ladders = new LadderAssault(ctx);
        this.tactics = new BlockTactics(ctx, style);
        this.fireballs = new FireballGuard(ctx, style);
        this.baitTrap = new net.h2020.bots.ai.combat.BaitTrap(ctx);
        this.bedRaid = new BedRaid(ctx, style);
        this.items = new ItemUse(ctx, style);
    }

    public Player target() { return target; }
    public boolean engaged() { return target != null; }
    public long lastHurtTick() { return lastHurt; }
    /** Last tick the void clutch actually tried to save us: a fall after this is a failed save, not a missed one. */
    public long lastClutchTick() { return lastClutchTick; }
    public boolean retreating() { return retreating; }
    public boolean eating() { return eatTicks > 0 || items.eating(); }
    /** Took a melee/projectile hit within the last `ticks` ticks. */
    public boolean recentlyHurt(int ticks) { return ctx.tick - lastHurt <= ticks; }

    public void engage(Player t) {
        if (t == null || t == target) return;
        target = t;
        engagedAt = ctx.tick;
        anchor = ctx.pos();
        comboCount = 0;
        hitsTaken = 0;
        strafeDir = (hitsLeft + hitsRight > 8) ? (hitsLeft >= hitsRight ? 1 : -1) : (ctx.rng.nextBoolean() ? 1 : -1);
        zest.reset();
        runTicks = 0;
        gearTick = -100;
        bestDist = Double.MAX_VALUE;
        bestDistTick = ctx.tick;
        throwsLeft = AIMath.range(ctx.rng, 2, 6);
        usingBowThisFight = ctx.rng.nextDouble() < ctx.persona.bowLiking;
        pearlUsed = false;
        retreating = false;
        nextStrafeSwitch = ctx.tick + AIMath.msToTicks(ctx.rng, ctx.persona.strafeSwitchMs);
        cpsNow = sampleCps();
        fightFeatures = null;
        pWin = 0.5;
        pWinTick = -100;
        flowTick = -100;
        fightScored = false;
        aimArm = net.h2020.bots.ai.ml.AimPolicy.choose(ctx);
        ctx.c.setAimPolicy(net.h2020.bots.ai.ml.AimPolicy.fracBias(aimArm), net.h2020.bots.ai.ml.AimPolicy.leadScale(aimArm));
        duelStance = -1;
        duelUntil = -100;
        duelX = null;
        hitsAtStart = hitsLanded;
        swingsAtStart = ctx.c.swings();
        fightDistSum = 0;
        fightDistN = 0;
        items.onFightStart();
    }

    /** Recently given up on (unreachable): brains should not re-engage unless it attacks us. */
    public boolean ignoring(Player p) {
        if (p == null) return false;
        Long until = ignoreUntil.get(p.getUniqueId());
        return until != null && ctx.tick < until;
    }

    private void giveUp() {
        if (target != null) ignoreUntil.put(target.getUniqueId(), ctx.tick + 400);
        disengage();
        if (ctx.nav.active() || ctx.nav.failed()) ctx.nav.stop();
    }

    public void disengage() {
        net.h2020.bots.ai.combat.ZestTap.grounded = false;
        if (target != null) {
            int hits = hitsLanded - hitsAtStart;
            int swings = ctx.c.swings() - swingsAtStart;
            net.h2020.bots.ai.ml.AimPolicy.record(aimArm, hits, swings, avgFightDist());
            net.h2020.bots.ai.ml.PlacementPolicy.discard(ctx.player.getUniqueId());
            fightScored = true;
            ctx.c.setAimPolicy(0, 1.0);
        }
        target = null;
        retreating = false;
        drawTicks = 0;
        if (eatTicks > 0) eatTicks = 0;
        if (tactics.busy()) tactics.cancel();
        if (bedRaid.active()) bedRaid.cancel();
    }

    /** Players start clicking a bit before 3-block reach; better players waste fewer swings. */
    private double clickRange() {
        // 1.8 lets a client start a swing out to about four blocks; past that it is a swing at air, and a missed
        // swing is half a second of nothing. Weaker hands reach a touch further, better ones click at the edge.
        return 4.0 + 0.2 * (1.0 - ctx.persona.skill.overall);
    }

    private double sampleCps() {
        return AIMath.gauss(ctx.rng, ctx.persona.skill.cps, 0.9, 3.5, 16);
    }

    private double[] arbFeatures(double d) {
        int hpDiff = (int) Math.round(ctx.health() - (target == null ? 20 : target.getHealth()));
        return net.h2020.bots.ai.ml.CombatArbiter.features(ctx, d, pWin, hpDiff, (int) gearDiff, edgeBehind,
                corneredFight, comboCount);
    }

    /** Health deltas since the last tick: what actually came off each bar (crits, fall damage, void ticks included). */
    private void trackDamage() {
        net.h2020.api.stats.CombatStats.inc("ml.dist.sample");
        if (target != null && !target.isDead()) {
            if (target.getEntityId() != dmgTargetId) {
                dmgTargetId = target.getEntityId();
                prevTargetHp = target.getHealth();
            }
            double now = target.getHealth();
            if (prevTargetHp >= 0 && now < prevTargetHp) dmgDealt += prevTargetHp - now;
            prevTargetHp = now;
        }
        double me = ctx.health();
        if (prevMyHp >= 0 && me < prevMyHp) dmgTaken += prevMyHp - me;
        prevMyHp = me;
    }

    private void markArbiterWindow() {
        arbAt = ctx.tick;
        arbLanded0 = hitsLanded;
        arbTaken0 = hitsTaken;
        arbDmgD0 = dmgDealt;
        arbDmgT0 = dmgTaken;
    }

    /** The original hand-written stance choice: what this code did before the duel policy existed. */
    private int handWrittenStance(double d) {
        if (d < 3.2 && ctx.persona.blockHitter && ctx.rng.nextDouble() < 0.5)
            return net.h2020.bots.ai.ml.DuelPolicy.BLOCK_HIT;
        if (ctx.c.onGround() && ctx.rng.nextDouble() < ctx.persona.jumpCritChance * 0.4)
            return net.h2020.bots.ai.ml.DuelPolicy.JUMP_CRIT;
        if (ctx.persona.strafes)
            return strafeDir > 0 ? net.h2020.bots.ai.ml.DuelPolicy.CIRCLE_L : net.h2020.bots.ai.ml.DuelPolicy.CIRCLE_R;
        return net.h2020.bots.ai.ml.DuelPolicy.PRESS;
    }

    /** A rod or snowball of ours connected: sprint in and press in, that is the combo start. */
    public void onRangedHit(Player victim) {
        if (victim == null || victim != target) return;
        comboUntil = ctx.tick + 16;
        CombatStats.inc("combo.starter");
    }

    public void onHitLanded(Player victim) {
        lastHitLanded = ctx.tick;
        zest.onHit(ctx.tick);
        lastHitVictim = victim;
        hitsTaken = 0;
        hitsLanded++;
        if (victim != target) return;
        comboCount++;
        if (strafeDir > 0) hitsLeft++; else hitsRight++;
        // W-tap: the persona chance is the base, the arbiter takes over once it has seen enough exchanges
        boolean doTap = ctx.rng.nextDouble() < ctx.persona.wtapChance;
        Boolean mlTap = net.h2020.bots.ai.ml.CombatArbiter.decide(net.h2020.bots.ai.ml.CombatArbiter.WTAP, ctx,
                arbFeatures(target == null ? 3.0 : ctx.pos().distance(target.getLocation())), doTap);
        if (mlTap != null) { markArbiterWindow(); doTap = mlTap; }
        if (doTap) {
            int delay = ctx.rng.nextDouble() < 0.7 ? 0 : 1;
            wtapTicks = AIMath.range(ctx.rng, 2, 4) + delay;
            sTapNow = ctx.persona.sTapper && ctx.rng.nextDouble() < 0.7;
            // (v2) no backwards tap with the void behind us
            if (CombatStats.v2 && edgeBehind) sTapNow = false;
            CombatStats.inc(sTapNow ? "stap" : "wtap");
        }
        if (corneredFight) {
            // wall at our back: nothing can knock us out of the corner, so hold the block-hit much longer
            blockHitTicks = AIMath.range(ctx.rng, 6, 12);
            CombatStats.inc("blockhit.corner");
        } else {
            boolean baseBlock = ctx.persona.blockHitter && ctx.rng.nextDouble() < 0.55;
            Boolean mlBlock = net.h2020.bots.ai.ml.CombatArbiter.decide(net.h2020.bots.ai.ml.CombatArbiter.BLOCKHIT, ctx,
                    arbFeatures(target == null ? 3.0 : ctx.pos().distance(target.getLocation())), baseBlock);
            if (mlBlock != null) baseBlock = mlBlock;
            if (baseBlock) blockHitTicks = AIMath.range(ctx.rng, 2, 4);
        }
        // hit and run against someone with better gear: the base rule, overridden by the arbiter when it is confident
        boolean baseRun = CombatStats.v2 && style.hitAndRun && gearDiff > 3 && ctx.rng.nextDouble() < 0.55;
        Boolean mlRun = net.h2020.bots.ai.ml.CombatArbiter.decide(net.h2020.bots.ai.ml.CombatArbiter.HITRUN, ctx,
                arbFeatures(3.0), baseRun);
        if (mlRun != null) { markArbiterWindow(); baseRun = mlRun; }
        if (baseRun) {
            runTicks = AIMath.range(ctx.rng, 5, 11);
            CombatStats.inc("hitrun");
        }
        if (CombatStats.v2 && comboCount == 3) CombatStats.inc("combo3");
        // every hit opens a scoring window: the arbiter is learning even while the hand-written rule decides, so the
        // window has to be closed on the next hits regardless of whether the model acted
        markArbiterWindow();
        // a hit inside a placement's window is what a stomp / wall was for
        net.h2020.bots.ai.ml.PlacementPolicy.onLanded(ctx.player.getUniqueId(), ctx.tick, victim);
    }

    public void onHurt(Player attacker) {
        lastHurt = ctx.tick;
        // a hit closes the current section: the stance that was running when it landed is graded right there, instead
        // of sharing credit with whatever happens in the rest of the ten-tick window
        if (duelStance >= 0 && duelUntil > ctx.tick + 2) duelUntil = ctx.tick + 2;
        comboCount = 0;
        hitsTaken++;
        if (hitsTaken >= 2) breakOut = true;
        clutch.onKnock();
        // knockback measurement (melee hits only)
        if (attacker != null && attacker.getWorld() == ctx.player.getWorld()) {
            Location me = ctx.pos(), al = attacker.getLocation();
            double dx = me.getX() - al.getX(), dz = me.getZ() - al.getZ();
            double l = Math.sqrt(dx * dx + dz * dz);
            if (l < 4.5 && l > 1e-3 && kbTick < 0) {
                kbFrom = me.clone(); kbUx = dx / l; kbUz = dz / l; kbTick = ctx.tick; kbJumped = false;
            }
        }
        if (target == null && attacker != null) engage(attacker);
        else if (attacker != null && attacker != target && ctx.pos().distance(attacker.getLocation()) < 3.5
                && target != null && ctx.pos().distance(target.getLocation()) > 5) engage(attacker);
        // taking a hit inside a placement's window means the block handed the opponent a free hit
        net.h2020.bots.ai.ml.PlacementPolicy.onTaken(ctx.player.getUniqueId(), ctx.tick);
    }

    /** A projectile of ours hit someone (stats). */
    public void onProjectileHit(String what) { CombatStats.inc("hit." + what); }

    /** A Watchdog flag on this bot (stats). */
    public void onFlag(String check) {
        CombatStats.inc("flag.all");
        CombatStats.inc("flag.c." + check);
        if (engaged() || ctx.tick - Math.max(lastHurt, lastHitLanded) < 60) { CombatStats.inc("flag.fight"); CombatStats.inc("flag.f." + check); }
    }

    /** The bot died (stats + clutch bookkeeping + one losing sample for the fight model). */
    public void onDeath(boolean voided) {
        if (fightFeatures != null) {
            net.h2020.bots.ai.ml.FightModel.learn(fightFeatures, false);
            net.h2020.bots.ai.ml.FightModel.observe(fightPred, false);
            fightFeatures = null;
        }
        scoreFight(false);
        clutch.onDeath(voided);
        kbTick = -1;
    }

    /**
     * The game told us the kill was a void kill. Only pay it when the victim was our fight target and our last hit was
     * recent - otherwise the fall belonged to someone else's knockback.
     */
    public void noteVoidKill(Player victim) {
        if (victim == null) return;
        if (victim != target && victim != lastHitVictim) return;      // not our knockback
        // the fall itself takes time: from a high bridge it can be three seconds before the death lands
        if (ctx.tick - lastHitLanded > 100) return;
        net.h2020.bots.ai.ml.DuelPolicy.voidCredit();
    }

    /** We killed somebody: when it is the one we were fighting, the models get a winning sample. */
    public void onKill(Player victim) {
        baitTrap.noteKill(victim);
        if (victim != null && victim == target) {
            if (fightFeatures != null) {
                net.h2020.bots.ai.ml.FightModel.learn(fightFeatures, true);
                net.h2020.bots.ai.ml.FightModel.observe(fightPred, true);
                fightFeatures = null;
            }
            scoreFight(true);
        }
    }

    /** Hand the fight's outcome to the two policy models: the placement choices and the aim profile. */
    /** Mean distance the fight was fought at, for the aim policy's per-range statistics. */
    /** Evidence weight for the exchange: a fight the model expected to lose is worth more when it is won. */
    /** (my armour + weapon) - (theirs), in the units the fight model and the duel policy both use. */
    public double gearDiffVs(Player foe) {
        if (foe == null) return 0;
        double mine = net.h2020.bots.ai.inv.Inv.totalArmor(ctx.player)
                + net.h2020.bots.ai.inv.Inv.bestMeleeValue(ctx.player);
        double theirs = net.h2020.bots.ai.inv.Inv.totalArmor(foe) + net.h2020.bots.ai.inv.Inv.bestMeleeValue(foe);
        return mine - theirs;
    }

    private double opponentWeight(double pWin) {
        double w = 1.0 + (0.5 - pWin);            // pWin 0.2 -> 1.3, pWin 0.8 -> 0.7
        return Math.max(0.6, Math.min(1.4, w));
    }

    private double avgFightDist() {
        return fightDistN > 0 ? fightDistSum / fightDistN : 4.0;
    }

    private void scoreFight(boolean win) {
        if (fightScored) return;
        fightScored = true;
        int hits = hitsLanded - hitsAtStart;
        int swings = ctx.c.swings() - swingsAtStart;
        net.h2020.bots.ai.ml.AimPolicy.record(aimArm, hits, swings, avgFightDist());
        net.h2020.bots.ai.ml.SelfStats.onFight(ctx.player, win);
        net.h2020.bots.ai.ml.SelfStats.onHits(ctx.player, hits, swings);
        net.h2020.bots.ai.ml.PlacementPolicy.finish(ctx.player.getUniqueId(), win);
        net.h2020.bots.ai.ml.DuelPolicy.terminal(win, false, gearDiffVs(target));
        net.h2020.bots.ai.ml.DuelPolicy.onFightEnd();
        ctx.c.setAimPolicy(0, 1.0);
    }

    /** How often fights like the current one end with them dying (0..1; 0.5 until the model has learned). */
    public double winProbability() { return pWin; }

    /**
     * Where we fight the target. A visible player: their delayed real position. An invisible one: our memory, i.e.
     * the extrapolated last known point, unless a particle trace just refreshed it (Sighting.estimate).
     */
    private Location targetPos() {
        Sighting s = ctx.perception.get(target);
        if (s != null && s.invisible && !s.visible) {
            Location e = s.estimate(ctx.tick);
            if (e != null) return e;
        }
        return ctx.posOf(target);
    }

    /**
     * Aim at the target. Two things decide where the crosshair goes: the aim profile (AimPolicy: high/low on the
     * hitbox, how much lead) and the fight-flow model (where they will be in a few ticks). While the flow model has
     * seen enough fights to be trusted, the point it predicts - scaled by the arm's lead - is the aim point; the
     * human aim model still moves the mouse there with its own reaction and error. An invisible target is aimed at
     * from memory only.
     */
    private void aimTarget(int prio, AimIntent intent) {
        Sighting s = ctx.perception.get(target);
        if (s != null && s.invisible && !s.visible) {
            Location e = s.estimate(ctx.tick);
            if (e != null) { ctx.motor.aimPoint(prio, e.clone().add(0, 1.15, 0), intent, 0.7); return; }
        }
        if (net.h2020.bots.ai.ml.FightFlow.samples() >= 40 && net.h2020.bots.ai.ml.FightFlow.error() < 1.2) {
            Location tl = ctx.posOf(target);
            double[] delta = net.h2020.bots.ai.ml.FightFlow.predict(ctx, target, ctx.pos().distance(tl), 4);
            double lead = net.h2020.bots.ai.ml.AimPolicy.leadScale(aimArm);
            ctx.motor.aimPoint(prio, tl.clone().add(delta[0] * lead, 1.15, delta[1] * lead), intent, 0.45);
            return;
        }
        ctx.motor.aimEntity(prio, target, intent);
    }

    private boolean valid(Player t) {
        return t != null && t.isOnline() && !t.isDead() && t.getWorld() == ctx.player.getWorld()
                && t.getGameMode() != GameMode.SPECTATOR && ctx.perception.filter().relevant(t);
    }

    // ------------------------------------------------------------------------------------------------ emergency

    /**
     * Runs every tick before the brain thinks (BaseBrain). Clutches when going over an edge and the calm heal after
     * a fight take over the inputs; returns true then and the brain skips its think() for this tick.
     */
    public boolean emergencyTick() {
        boolean inGame = ctx.bot.brain() instanceof net.h2020.bots.ai.brain.GameBrain;
        if (!inGame) return false;
        CombatStats.tick(engaged());
        // a fireball coming at us owns the tick, fight or not: punch it back, or step out of its line
        if (fireballs.tick()) { if (tactics.busy()) tactics.cancel(); return true; }
        if (kbTick >= 0 && ctx.tick - kbTick >= 10) {
            Location me = ctx.pos();
            if (kbFrom != null && me.getWorld() == kbFrom.getWorld()) {
                double push = (me.getX() - kbFrom.getX()) * kbUx + (me.getZ() - kbFrom.getZ()) * kbUz;
                CombatStats.inc("kb.n");
                CombatStats.add("kb.sum", push);
                if (kbJumped) { CombatStats.inc("kb.jr.n"); CombatStats.add("kb.jr.sum", push); }
            }
            kbTick = -1;
        }
        if (ctx.c.tookKnockback() && edgeBehind && tricks.ladderPlonk()) { if (tactics.busy()) tactics.cancel(); return true; }
        if (clutch.tick()) { lastClutchTick = ctx.tick;
            if (tactics.busy()) tactics.cancel();
            return true;
        }
        if (CombatStats.v2 && !engaged()) return items.calmTick();
        return false;
    }

    // ------------------------------------------------------------------------------------------------ tick

    public void tick() {
        // an arbiter decision is scored on the exchange it produced, twelve ticks later
        if (arbAt >= 0 && ctx.tick - arbAt > 12) {
            net.h2020.bots.ai.ml.CombatArbiter.resolve(ctx, (int) (hitsLanded - arbLanded0), (int) (hitsTaken - arbTaken0),
                    dmgDealt - arbDmgD0, dmgTaken - arbDmgT0, gearDiffVs(target));
            arbAt = -1;
        }
        if (!valid(target)) { disengage(); return; }
        Location me = ctx.pos();
        // the target as our client sees them: half a round trip old, not where the server has them now (and for an
        // invisible target, only our memory of them - see targetPos())
        Location tl = targetPos();
        double d = me.distance(tl);
        Sighting s = ctx.perception.get(target);
        if (d > 60 || (s != null && !s.visible && s.unseenSeconds(ctx.tick) > 6)) { disengage(); return; }
        if (anchor != null && anchor.getWorld() == tl.getWorld() && anchor.distance(tl) > leash && d > 8) { disengage(); return; }
        if (critCooldown > 0) critCooldown--;
        if (rangedCooldown > 0) rangedCooldown--;
        if (fireballCooldown > 0) fireballCooldown--;
        if (rodCooldown > 0) rodCooldown--;
        if (ctx.tick % 30 == 0) cpsNow = sampleCps();
        if (d < bestDist - 0.5) { bestDist = d; bestDistTick = ctx.tick; }
        long noHitsFor = ctx.tick - Math.max(Math.max(lastHitLanded, lastHurt), engagedAt);
        // closing in, or trading hits, is progress; standing in "reach" swinging at a wall/pane is not
        long stalledFor = Math.min(ctx.tick - bestDistTick, noHitsFor);
        if (CombatStats.v2) { v2Tick(me, tl, d, stalledFor, noHitsFor); return; }
        // no progress for 10 s and no hits either way: unreachable, find something else to do
        if (stalledFor > 200 && noHitsFor > 200) { giveUp(); return; }

        boolean los = los(tl);

        // eating a golden apple
        if (eatTicks > 0) {
            eatTicks--;
            if (Inv.holding(ctx.player, Inv.GAPPLE)) ctx.motor.holdRight();
            backOff(tl, d);
            aimTarget(Motor.P_COMBAT, AimIntent.COMBAT);
            if (eatTicks == 0) Inv.equip(ctx.player, ctx.c, Inv.SWORD, 0);
            return;
        }
        double hp = ctx.health();
        if (allowGapple && hp <= 10 && ctx.tick - lastEat > 70 && Inv.has(ctx.player, Inv.GAPPLE) && (d > 4.5 || hp <= 6)) {
            if (Inv.equip(ctx.player, ctx.c, Inv.GAPPLE, 7)) {
                eatTicks = 34 + AIMath.range(ctx.rng, 0, 6);
                lastEat = ctx.tick;
                CombatStats.inc("gapple");
            }
            backOff(tl, d);
            return;
        }
        // retreat when losing - or when the learned model says this fight is usually lost
        boolean doomed = pWin < 0.28 && hp <= 12;
        retreating = !net.h2020.bots.ai.BotAI.sparMode()
                && ((hp <= ctx.persona.retreatHealth && target.getHealth() > hp + 3 && d < 12) || doomed);
        // bait a trap: give ground onto narrow footing, then cut their way back. Owns movement while it runs.
        if (!doomed && !retreating && d < 6.0 && baitTrap.tick(target, d)) {
            aimTarget(Motor.P_COMBAT, AimIntent.COMBAT);
            if (d < 3.2) fire(cpsNow);
            return;
        }
        // position check: somebody above us within jump-fall-hit range, or our back to the void with nothing to put
        // behind it - step out of that first, and only fight from there when there is nowhere to step
        if (ctx.tick % 5 == 0) badPos = PositionSense.badPosition(ctx, me, target, pWin);
        if (badPos && !corneredFight && d > 2.0 && ctx.tick - lastHurt > 5) {
            // reverse the stance before declining: step to ground where the void is behind them and they stay in
            // reach. If there is nowhere better, the old back-off runs; fighting in place is the last resort.
            if (ctx.tick - repositionAt > 20) {
                repositionAt = ctx.tick;
                repositionFix = PositionSense.correctSpot(ctx, me, target);
            }
            Location fix = repositionFix;
            if (fix != null && PositionSense.exposedHazard(ctx, me, target)) {
                double fx = fix.getX() - me.getX(), fz = fix.getZ() - me.getZ();
                if (Math.hypot(fx, fz) > 0.6) {
                    aimTarget(Motor.P_COMBAT, AimIntent.COMBAT);
                    ctx.motor.moveToward(fx, fz);
                    if (Math.hypot(fx, fz) > 2.5) ctx.motor.sprint(true);
                    if (fix.getY() > me.getY() + 0.4 && ctx.c.onGround()) ctx.motor.jump(true);
                    if (d < 3.2) fire(cpsNow);
                    CombatStats.inc("pos.correct");
                    if (!PositionSense.badPosition(ctx, me, target, pWin)) repositionFix = null;
                    return;
                }
            }
            Location away = PositionSense.escapeSpot(ctx, me, target);
            if (away != null && !PositionSense.onExposedLine(ctx, me)) {
                aimTarget(Motor.P_COMBAT, AimIntent.COMBAT);
                ctx.motor.moveToward(away.getX() - me.getX(), away.getZ() - me.getZ());
                ctx.motor.sprint(true);
                if (d < 3.2) fire(cpsNow);
                CombatStats.inc("pos.backoff");
                return;
            }
        }
        if (retreating && ctx.persona.skill.aggression < 0.85) {
            aimTarget(Motor.P_COMBAT, AimIntent.COMBAT);
            if (allowPearl && !pearlUsed && hp <= 4 && Inv.has(ctx.player, Inv.PEARL) && ctx.rng.nextDouble() < 0.05) {
                escapePearl(tl);
                return;
            }
            backOff(tl, d);
            if (d < 3.2) fire(cpsNow);
            return;
        }

        // ranged options
        if (los && ranged(d, tl)) return;
        drawTicks = 0;

        // chase when far / unreachable directly
        // (also when walking straight at them got us no closer for a while: a gap, a wall or the void edge is in between)
        if (d > 6.5 || Math.abs(tl.getY() - me.getY()) > 2.5 || (stalledFor > 50 && (d > clickRange() || noHitsFor > 60))) {
            if (allowChase) {
                ctx.nav.follow(tl, 1.8, chaseOptions);
                ctx.nav.tick();
                if (ctx.nav.failed()) { giveUp(); return; }
            }
            // bridging/pillaring toward them: eyes stay on the blocks (the bridger glances up on its own)
            if (los && d < 14 && (!ctx.nav.doingAction() || d < 4.5)) aimTarget(Motor.P_COMBAT, AimIntent.COMBAT);
            // (mid-bridge the hand stays on the blocks: swapping to the sword every tick made the bridger right-click
            // with a sword and never place)
            if (!ctx.nav.doingAction()) {
                if (d < clickRange()) fire(cpsNow);
                Inv.equip(ctx.player, ctx.c, Inv.SWORD, 0);
            }
            return;
        }
        if (ctx.nav.active()) ctx.nav.stop();
        melee(tl, d);
    }

    // ------------------------------------------------------------------------------------------------ v2

    private void v2Tick(Location me, Location tl, double d, long stalledFor, long noHitsFor) {
        // the fight tick owns the placement outcome window: one poll per tick, no second scheduler
        net.h2020.bots.ai.ml.PlacementPolicy.poll(ctx.player.getUniqueId(), ctx.tick, target);
        if (stalledFor > 200 && noHitsFor > 200) {
            // unreachable: a pearl across the gap first, if we have one
            if (tryEngagePearl(tl, d)) return;
            giveUp();
            return;
        }
        // grounded only while actually in the combo: within reach and on solid ground. A knockback at a ledge still
        // gets its jump reset, because that one is survival, not rhythm.
        net.h2020.bots.ai.combat.ZestTap.grounded = net.h2020.bots.ai.combat.ZestTap.enabled() && d < 4.0
                && ctx.world().voidSides((int) Math.floor(me.getX()), (int) Math.floor(me.getY() + 0.01),
                (int) Math.floor(me.getZ())) < 2;
        boolean los = los(tl);
        double hp = ctx.health();
        // bait: give ground onto narrow footing and cut their way back (v2 path owns the fight tick)
        if (!retreating && hp >= 12 && d < 6.0 && baitTrap.tick(target, d)) {
            aimTarget(Motor.P_COMBAT, AimIntent.COMBAT);
            if (d < 3.2) fire(cpsNow);
            return;
        }
        if (ctx.tick - gearTick > 40) {
            gearTick = ctx.tick;
            gearDiff = gear(target) - gear(ctx.player);
        }
        if (ctx.tick % 3 == 0) edgeBehind = edgeBehind(me, tl);

        // blocks: towering / walls / cutting in progress
        if (tactics.busy()) {
            if (tactics.tick()) return;
        }
        // items: potions, gapples, lava, fire, TNT
        items.allowGapple = allowGapple;
        items.allowTnt = ctx.bot.brain() instanceof net.h2020.bots.ai.brain.BedwarsBrain;
        boolean meleeBusy = d < 3.2 && ctx.tick - Math.max(lastHitLanded, lastHurt) < 15;
        if (items.fightTick(target, d, lastHurt, meleeBusy)) {
            net.h2020.api.stats.CombatStats.inc("pre.consider.items");
            if (items.eating()) {
                if (d < 6) backOff(tl, d);
                aimTarget(Motor.P_COMBAT, AimIntent.COMBAT);
            }
            return;
        }
        // the model's read on this fight: a hopeless one is not worth standing in
        if (ctx.tick - pWinTick >= 10) {
            double[] x = net.h2020.bots.ai.ml.FightModel.features(ctx, target, d);
            net.h2020.bots.ai.ml.FightModel.setCombo(x, comboCount, hitsTaken);
            if (fightFeatures == null && d < 4.5) {
                fightFeatures = x.clone();
                fightPred = net.h2020.bots.ai.ml.FightModel.predict(x);
            }
            pWin = net.h2020.bots.ai.ml.FightModel.pWin(x);
            pWinTick = ctx.tick;
        }
        boolean losing = hp <= ctx.persona.retreatHealth + 2 && target.getHealth() > hp + 3;
        // the bed is right there and the defender is busy: put the sword away and break it now
        double enemyNear = nearestEnemyDist(d);
        if (bedRaid.active()) {
            if (bedRaid.tick(ctx.tick - lastHurt, enemyNear)) {
                net.h2020.api.stats.CombatStats.inc("pre.consider.bedraid");
                return;
            }
        } else if (CombatTuning.bedRaid && bedInfo != null && bedInfo.alive() && bedInfo.foot() != null
                && bedRaid.consider(bedInfo.foot(), ctx.tick - lastHurt, enemyNear)) {
            if (bedRaid.tick(ctx.tick - lastHurt, enemyNear)) return;
        }
        // the fight-flow model learns where the fight is going while it is going on
        if (ctx.tick - flowTick >= 5) {
            net.h2020.bots.ai.ml.FightFlow.observe(ctx, target, d);
            flowTick = ctx.tick;
        }
        // Block play needs blocks in the HAND, and a bot fighting with its sword has them in slot 9 or in the bag.
        // Without this the placement branch never even ran: the median bot placed 7 blocks a game while the player it
        // was fighting placed 281.
        if (CombatTuning.blockPlay && d > 3.0 && ctx.tick - lastBlockEquip > 20
                && Inv.findHotbar(ctx.player, Inv.BLOCK) < 0 && Inv.has(ctx.player, Inv.BLOCK)) {
            if (Inv.equip(ctx.player, ctx.c, Inv.BLOCK, 1)) {
                lastBlockEquip = ctx.tick;
                CombatStats.inc("combat.blocks.equip");
            }
        }
        net.h2020.api.stats.CombatStats.inc("pre.consider.reach");
        tactics.cps = cpsNow;
        tactics.winProb = pWin;
        if (tactics.consider(target, d, losing || (retreating && hp <= 8))) return;

        // on top of a tower we just built: hold it (bow / throws / hit them if they pillar up next to us)
        if (tactics.holdingHigh()) {
            ctx.motor.sneak(true); // (sneaking: never steps off the tower)
            aimTarget(Motor.P_COMBAT, AimIntent.COMBAT);
            if (los && ranged(d, tl)) return;
            if (d < clickRange()) { selectWeapon(); fire(cpsNow); }
            return;
        }

        // retreat when losing
        retreating = hp <= ctx.persona.retreatHealth && target.getHealth() > hp + 3 && d < 12;
        // cornered (a wall at our back, no room to back off): do not run into it, plant and fight from the corner
        if (ctx.tick % 3 == 0) wallBehind = wallBehind(me, tl);
        corneredFight = retreating && style.cornerFight && wallBehind;
        if (corneredFight) {
            retreating = false;
            CombatStats.inc("corner.stand");
        }
        if (retreating && ctx.persona.skill.aggression < 0.85) {
            aimTarget(Motor.P_COMBAT, AimIntent.COMBAT);
            if (allowPearl && !pearlUsed && hp <= 5 && d < 6 && Inv.findHotbar(ctx.player, Inv.PEARL) >= 0
                    && ctx.tick % 10 == 0 && ctx.rng.nextDouble() < 0.15 + 0.35 * ctx.persona.skill.overall) {
                pearlEscapeTicks = 30;
            }
            if (pearlEscapeTicks > 0) {
                pearlEscapeTicks--;
                if (escapePearl(tl)) return;
            }
            backOff(tl, d);
            if (d < 3.2) { selectWeapon(); fire(cpsNow); }
            return;
        }

        // ranged options
        if (los && ranged(d, tl)) return;
        drawTicks = 0;

        // chase when far / unreachable directly
        if (d > 6.5 || Math.abs(tl.getY() - me.getY()) > 2.5 || (stalledFor > 50 && (d > clickRange() || noHitsFor > 60))) {
            if (allowChase) {
                ctx.nav.follow(tl, 1.8, chaseOptions);
                ctx.nav.tick();
                if (ctx.nav.failed()) {
                    if (tryEngagePearl(tl, d)) return;
                    giveUp();
                    return;
                }
            }
            if (los && d < 14 && (!ctx.nav.doingAction() || d < 4.5)) aimTarget(Motor.P_COMBAT, AimIntent.COMBAT);
            if (!ctx.nav.doingAction()) {
                if (d < clickRange()) fire(cpsNow);
                selectWeapon();
            }
            return;
        }
        if (ctx.nav.active()) ctx.nav.stop();
        meleeV2(tl, d);
    }

    private int pearlEscapeTicks;
    private Vector nearVoid;
    private long nearTick = -100;
    /** Cached staging spot for reversing a bad stance; recomputed every second, used for the ticks in between. */
    private Location repositionFix;
    private long repositionAt = -100;

    /** Gear score: armor points + weapon value. */
    private static double gear(Player p) { return Inv.totalArmor(p) + Inv.bestMeleeValue(p); }

    private void selectWeapon() {
        int slot = Inv.bestMeleeHotbar(ctx.player);
        if (slot >= 0) {
            if (ctx.c.heldSlot() != slot) {
                ctx.c.selectHotbar(slot);
                ItemStack held = ctx.player.getItemInHand();
                if (!Items.isSword(held) && !Items.isAxe(held)) CombatStats.inc("weaponswap");
            }
        } else if (Inv.has(ctx.player, Inv.SWORD)) {
            // the sword is in the bag, not in the hotbar: get it into the hand before trading blows. A bot that stands
            // at 1.7 blocks holding wool takes free hits - that is exactly how Toufla lost to Diamond_2007.
            if (ctx.tick - lastWeaponSwap > 4) {
                lastWeaponSwap = ctx.tick;
                Inv.equip(ctx.player, ctx.c, Inv.SWORD, 0);
                CombatStats.inc("weapon.frombag");
            }
        } else Inv.equip(ctx.player, ctx.c, Inv.SWORD, 0);
    }

    /** Void within ~3 blocks in the direction we'd be knocked (away from the target). */
    private boolean edgeBehind(Location me, Location tl) {
        double dx = me.getX() - tl.getX(), dz = me.getZ() - tl.getZ();
        double l = Math.sqrt(dx * dx + dz * dz);
        if (l < 1e-3) return false;
        dx /= l; dz /= l;
        WorldView wv = ctx.world();
        int y = floor(me.getY() + 0.01);
        for (double r = 1.0; r <= 3.01; r += 1.0) {
            for (double side = -0.5; side <= 0.51; side += 0.5) {
                int x = floor(me.getX() + dx * r - dz * side), z = floor(me.getZ() + dz * r + dx * side);
                if (wv.passable(x, y, z) && wv.passable(x, y - 1, z) && wv.voidBelow(x, y, z, 30)) return true;
            }
        }
        return false;
    }

    /**
     * The clutch tool: a knockback stick at its full reach on somebody standing next to the void. It does almost no
     * damage, so it is only ever right when the knockback itself is the kill - they are on a bridge or an edge. With
     * our bed gone (clutch mode) it stays in hand for those hits; otherwise it is only for the free void kill.
     */
    private boolean useKnockbackStick(Location tl, double d) {
        if (d < 2.3 || d > 3.25) return false;
        Vector edge = voidDirection(tl);
        if (!clutchMode && edge == null) return false;
        int slot = Inv.findHotbar(ctx.player, Inv.KNOCKBACK_STICK);
        if (slot < 0) return false;
        if (ctx.c.heldSlot() != slot) ctx.c.selectHotbar(slot);
        aimTarget(Motor.P_COMBAT, AimIntent.COMBAT);
        fire(cpsNow);
        CombatStats.inc("kbstick");
        return true;
    }

    /** Solid blocks right behind us within 2 blocks (the side a hit would push us to): no room to retreat. */
    private boolean wallBehind(Location me, Location tl) {
        double dx = me.getX() - tl.getX(), dz = me.getZ() - tl.getZ();
        double l = Math.sqrt(dx * dx + dz * dz);
        if (l < 1e-3) return false;
        dx /= l; dz /= l;
        WorldView wv = ctx.world();
        int y = floor(me.getY() + 0.01);
        int solid = 0;
        for (double r = 0.9; r <= 1.75; r += 0.85) {
            for (double side = -1.0; side <= 1.01; side += 1.0) {
                int x = floor(me.getX() + dx * r - dz * side), z = floor(me.getZ() + dz * r + dx * side);
                if (!wv.passable(x, y, z) || !wv.passable(x, y + 1, z)) solid++;
            }
        }
        return solid >= 2;
    }

    /** Distance to the closest known enemy other than the fight target (99 if there is none). */
    private double nearestEnemyDist(double targetDist) {
        net.h2020.bots.ai.perception.Sighting s = ctx.perception.nearestKnownEnemy(40);
        double d = s == null ? 99 : s.estimate(ctx.tick).distance(ctx.pos());
        return Math.min(d, targetDist);
    }

    /** Average horizontal direction from a spot toward adjacent void within 3 blocks (null if none). */
    private Vector voidAround(Location at) {
        WorldView wv = ctx.world();
        int x = floor(at.getX()), y = floor(at.getY() + 0.01), z = floor(at.getZ());
        double vx = 0, vz = 0;
        int n = 0;
        for (int r = 1; r <= 3; r++) {
            int[][] dirs = {{r, 0}, {-r, 0}, {0, r}, {0, -r}, {r, r}, {-r, r}, {r, -r}, {-r, -r}};
            for (int[] dd : dirs) {
                if (wv.passable(x + dd[0], y, z + dd[1]) && wv.voidBelow(x + dd[0], y, z + dd[1], 30)) {
                    vx += dd[0] / (double) (r * r); vz += dd[1] / (double) (r * r); n++;
                }
            }
        }
        if (n == 0) return null;
        double l = Math.sqrt(vx * vx + vz * vz);
        if (l < 1e-3) return null;
        return new Vector(vx / l, 0, vz / l);
    }

    private void meleeV2(Location tl, double d) {
        Location me = ctx.pos();
        WorldView wv = ctx.world();
        if (!useKnockbackStick(tl, d)) selectWeapon();
        aimTarget(Motor.P_COMBAT, AimIntent.COMBAT);

        double dx = tl.getX() - me.getX(), dz = tl.getZ() - me.getZ();
        double len = Math.max(1e-3, Math.sqrt(dx * dx + dz * dz));
        double ux = dx / len, uz = dz / len;
        boolean ground = ctx.c.onGround();

        // jump reset: jump on the tick the hit's velocity arrives (sprinting into them: the jump boost eats KB)
        boolean jumpedNow = false;
        // with the void at our back a jump-reset is not style, it is survival: it halves the knockback
        boolean mustReset = edgeBehind && ctx.tick - lastHurt <= 3;
        if (ctx.c.tookKnockback() && ctx.tick - lastHurt <= 2 && ground && d < 4.2
                && (mustReset || ctx.rng.nextDouble() < style.jumpReset)
                && wv.passable(floor(me.getX()), floor(me.getY()) + 2, floor(me.getZ()))) {
            ctx.motor.jump(true);
            jumpedNow = true;
            if (kbTick >= 0) kbJumped = true;
            CombatStats.inc("jumpreset");
        }

        fightDistSum += d;
        fightDistN++;
        trackDamage();
        // fake swing: arm out at just-out-of-reach range baits a hit that misses, and the whiff is free damage.
        // (FabledSnacker: "hold your sword until the moment you place the block" - the same idea in reverse.)
        // somebody towered above us or camping the lip: a ladder is the cheap way up (and cancels knockback on the way)
        if (ladders.tick(target, d)) return;

        // they are above us on a pillar or a staircase: a fireball jump buys the height instead of walking the long way
        if (tl.getY() - ctx.pos().getY() > 2.2 && d < 7.0 && tricks.fireballJump(tl, true)) return;

        if (d > 3.3 && d < 4.6 && ctx.tick - lastFakeSwing > 22 && ctx.rng.nextDouble() < 0.12 + 0.16 * ctx.persona.skill.overall) {
            ctx.c.swing();
            lastFakeSwing = ctx.tick;
            CombatStats.inc("trick.fake");
        }
        // hit-select: occasionally let the first exchange be theirs, then hit into their recovery
        if (d < 3.0 && hitSelectTicks < 0 && ctx.rng.nextDouble() < 0.18) hitSelectTicks = ctx.tick + AIMath.range(ctx.rng, 2, 5);

        boolean comboing = comboCount >= 2 && ctx.tick - lastHitLanded < 14;
        boolean beingComboed = hitsTaken >= 2 && ctx.tick - lastHurt < 16;
        if (comboing && style.comboLock) CombatStats.add("combolock.ticks", 1.0 / 20);

        // the duel stance: chosen every ~10 ticks, held, and scored on the exchange it produced
        if (ctx.tick >= duelUntil) {
            if (duelStance >= 0 && duelX != null) {
                int wLanded = hitsLanded - duelHits0, wTaken = hitsTaken - duelTaken0;
                // a section is graded on its own: a good exchange in a fight that is going badly still counts
                double wDmgDealt = dmgDealt - duelDmgD0, wDmgTaken = dmgTaken - duelDmgT0;
                // hit back or it is unanswered: a window where health only moved one way is the pattern to punish,
                // whether the hit counter missed it (blocked hits, fall damage) or not
                int unanswered = (wTaken > 0 && wLanded == 0) ? wTaken
                        : (wDmgTaken > 0.5 && wDmgDealt < wDmgTaken * 0.5 ? 1 : 0);
                net.h2020.bots.ai.ml.DuelPolicy.record(duelX, duelStance, wLanded, wTaken,
                        wDmgDealt, wDmgTaken, opponentWeight(pWin), gearDiffVs(target), comboCount, unanswered);
                net.h2020.bots.ai.ml.CombatArbiter.resolveWindow(ctx, net.h2020.bots.ai.ml.CombatArbiter.STRAFE,
                        wLanded, wTaken, comboCount, unanswered, opponentWeight(pWin));
            }
            duelX = net.h2020.bots.ai.ml.DuelPolicy.features(ctx, target, d, pWin, comboCount, hitsTaken, edgeBehind);
            duelStance = net.h2020.bots.ai.ml.DuelPolicy.choose(duelX);
            // zest tapping is a grounded rhythm: jumping lifts the feet off the burst timing and breaks the chain
            if (net.h2020.bots.ai.combat.ZestTap.enabled() && engaged()
                    && duelStance == net.h2020.bots.ai.ml.DuelPolicy.JUMP_CRIT) {
                duelStance = net.h2020.bots.ai.ml.DuelPolicy.PRESS;
                CombatStats.inc("zest.nojump.stance");
            }
            // strafing is a mechanic like any other: shadow-learned from the hand-written habit, then allowed to
            // override it once the value model has seen enough windows
            boolean wantStrafe = ctx.persona.strafes && !ctx.nav.bridging() && d < 4.2;
            Boolean mlStrafe = net.h2020.bots.ai.ml.CombatArbiter.decide(net.h2020.bots.ai.ml.CombatArbiter.STRAFE, ctx,
                    arbFeatures(d), wantStrafe);
            arbStrafe = mlStrafe != null ? mlStrafe : wantStrafe;
            arbStrafeUntil = ctx.tick + AIMath.range(ctx.rng, 8, 14);
            double dconf = net.h2020.bots.ai.ml.DuelPolicy.confidence();
            if (dconf < 1.0 && ctx.rng.nextDouble() > dconf) {
                duelStance = handWrittenStance(d);
                CombatStats.inc("stance.base");
            }
            duelHits0 = hitsLanded;
            duelTaken0 = hitsTaken;
            duelDmgD0 = dmgDealt;
            duelDmgT0 = dmgTaken;
            duelUntil = ctx.tick + AIMath.range(ctx.rng, 8, 14);
        }

        // standing with the void at our back: put a block down before the trade, not after the knockback
        if (edgeBehind && ground && ctx.tick - lastSafetyTry > 60) {
            lastSafetyTry = ctx.tick;
            if (tactics.safetyBlock(me, tl)) CombatStats.inc("safety.try");
        }

        // under the head hitter we just placed: keep jumping, that is what makes it worth the block
        if (ground && tactics.wantsJump()) ctx.motor.jump(true);

        // void knock: approach from the side opposite to the void next to the target
        Vector voidDir = voidDirection(tl);
        double mx = ux, mz = uz;
        if (voidDir != null && d > 2.2) {
            double wantX = tl.getX() - voidDir.getX() * 2.6, wantZ = tl.getZ() - voidDir.getZ() * 2.6;
            double wx = wantX - me.getX(), wz = wantZ - me.getZ();
            double wl = Math.sqrt(wx * wx + wz * wz);
            if (wl > 0.8) { mx = wx / wl; mz = wz / wl; }
        }

        boolean onBridge = wv.voidSides(floor(me.getX()), floor(me.getY() + 0.01), floor(me.getZ())) >= 2;
        boolean strafe = !onBridge && d < 4.2 && (ctx.tick < arbStrafeUntil ? arbStrafe : ctx.persona.strafes);
        // strafe direction: timed switches (irregular for good players), a switch when comboed, and never toward the void
        if (ctx.tick >= nextStrafeSwitch) {
            if (ctx.rng.nextDouble() < 0.8) { strafeDir = -strafeDir; CombatStats.inc("strafeswitch"); }
            double ms = ctx.persona.strafeSwitchMs * (0.6 + 0.8 * ctx.rng.nextDouble());
            // sweats throw in a quick double switch now and then
            if (ctx.rng.nextDouble() < 0.25 * style.strafeSkill) ms = 120 + ctx.rng.nextDouble() * 150;
            nextStrafeSwitch = ctx.tick + AIMath.msToTicks(ctx.rng, ms);
        }
        if (breakOut) {
            breakOut = false;
            if (ctx.rng.nextDouble() < 0.3 + 0.6 * style.strafeSkill) {
                strafeDir = -strafeDir;
                nextStrafeSwitch = ctx.tick + AIMath.msToTicks(ctx.rng, ctx.persona.strafeSwitchMs);
                CombatStats.inc("comboescape");
            }
        }
        if (ctx.tick - nearTick >= 5) { nearTick = ctx.tick; nearVoid = style.edgeSense > 0.2 ? voidAround(me) : null; }
        Vector near = nearVoid;
        if (near != null && ctx.rng.nextDouble() < style.edgeSense) {
            // tangent that moves us away from the void side (so the knockback direction rotates off the edge)
            double t1x = -uz, t1z = ux; // strafeDir = +1
            double dot = t1x * near.getX() + t1z * near.getZ();
            int want = dot > 0 ? -1 : 1;
            if (want != strafeDir && Math.abs(dot) > 0.2) { strafeDir = want; CombatStats.inc("edgestrafe"); }
            if (edgeBehind) strafe = !onBridge; // circle out of it even on islands where we'd otherwise hold W
        }
        if (comboing && style.comboLock) strafe = strafe && d > 3.2 && ctx.rng.nextDouble() < 0.3; // straight in, keep them stuck

        double fwd = 1;
        if (d < 1.7) fwd = 0;
        if (d < 1.0) fwd = edgeBehind ? 0 : -1;
        if (wtapTicks > 0) { wtapTicks--; fwd = sTapNow && !edgeBehind ? -1 : 0; }
        if (runTicks > 0) {
            runTicks--;
            fwd = edgeBehind ? 0 : -1;
            strafe = !onBridge;
        }
        if (jumpedNow) fwd = 1; // the jump reset only works sprinting into them

        // the duel stance overrides the footwork and the click intent (never the safety clamps below)
        boolean stanceNoClick = false, stanceBlockHit = false;
        switch (duelStance) {
            case net.h2020.bots.ai.ml.DuelPolicy.PRESS: fwd = 1; break;
            case net.h2020.bots.ai.ml.DuelPolicy.CIRCLE_L: { strafe = true; strafeDir = 1; break; }
            case net.h2020.bots.ai.ml.DuelPolicy.CIRCLE_R: { strafe = true; strafeDir = -1; break; }
            case net.h2020.bots.ai.ml.DuelPolicy.SPACE: fwd = edgeBehind ? 0 : -1; strafe = false; break;
            case net.h2020.bots.ai.ml.DuelPolicy.BLOCK_HIT: { fwd = d > 2.3 ? 1 : 0; stanceBlockHit = true; break; }
            case net.h2020.bots.ai.ml.DuelPolicy.RESET: { fwd = 0; stanceNoClick = true; break; }
            case net.h2020.bots.ai.ml.DuelPolicy.JUMP_CRIT: { fwd = 1; if (ground) ctx.motor.jump(true); break; }
            default: break;
        }
        // Classic 1.8 reach control: the swing reaches about three blocks, so a good player lives at the edge of it -
        // in for the hit, out of theirs. Only when the stance is not dictating footwork, and only for hands that can
        // hold a distance at all.
        if (duelStance == net.h2020.bots.ai.ml.DuelPolicy.PRESS && ctx.persona.skill.overall > 0.35) {
            if (comboing) { fwd = 1; CombatStats.inc("pvp.reach.combo"); }
            else if (d < 3.30) { fwd = -1; CombatStats.inc("pvp.reach.back"); }
            else if (d > 4.05) { fwd = 1; CombatStats.inc("pvp.reach.close"); }
            else { fwd = 0.35f; CombatStats.inc("pvp.reach.hold"); }
        }
        // turning into their swing: a hit that lands while we face it pushes us less far off line than one we never
        // saw. The strafe flips the tick we take damage, which also breaks their aim pattern.
        if (ctx.tick - lastHurt <= 1 && ctx.tick > lastTurnInto + 6) {
            strafeDir = -strafeDir;
            lastTurnInto = ctx.tick;
            CombatStats.inc("pvp.turninto");
        }
        // S-tap: after our own hit lands, one step back takes us out of their reach for the tick their swing comes
        // out. Classic anti-trade, and the reason a losing exchange can be reset without running away.
        if (ctx.tick - lastHitLanded <= 2 && d < 3.2 && ctx.health() <= 14 && !edgeBehind) {
            fwd = -1;
            CombatStats.inc("pvp.stap");
        }
        // bunny-hop in: past reach, a sprint jump closes four blocks in the time a walk closes two, and lands on the
        // way down with the crit bonus
        if (ground && d > 4.5 && d < 8.0 && ctx.persona.skill.overall > 0.5 && !net.h2020.bots.ai.combat.ZestTap.grounded
                && ctx.tick % 14 == 0) {
            ctx.motor.jump(true);
            ctx.motor.sprint(true);
            CombatStats.inc("pvp.bunny");
        }

        double sx = 0, sz = 0;
        if (strafe) { sx = -uz * strafeDir; sz = ux * strafeDir; }
        double wx = mx * fwd + sx * 0.9, wz = mz * fwd + sz * 0.9;
        if (!safe(me, wx, wz)) {
            double ax = mx * fwd - sx * 0.9, az = mz * fwd - sz * 0.9;
            if (strafe && safe(me, ax, az)) { strafeDir = -strafeDir; wx = ax; wz = az; }
            else if (safe(me, mx * fwd, mz * fwd)) { wx = mx * fwd; wz = mz * fwd; }
            else if (safe(me, mx, mz)) { wx = mx; wz = mz; }
            else { wx = 0; wz = 0; ctx.motor.sneak(onBridge || edgeBehind); }
            CombatStats.inc("edgestop");
        }
        applyZest(me);
        if (Math.abs(wx) + Math.abs(wz) < 1e-3 && d > 3.0) {
            // nothing to do and not in reach: chase instead of standing in front of them (the duel replay had the bot
            // motionless for half of the fight, ten blocks from the player it was supposed to be fighting)
            wx = ux; wz = uz;
            CombatStats.inc("combat.noIdleInFight");
        }
        if (Math.abs(wx) + Math.abs(wz) > 1e-3) ctx.motor.moveToward(wx, wz);
        if (ctx.tick < comboUntil) { fwd = 1; ctx.motor.sprint(true); CombatStats.inc("combo.press"); }
        else ctx.motor.sprint(fwd > 0 && wtapTicks == 0);
        // sprint-cancel defence: a sprinting victim takes more knockback, so let go of W on the tick their swing is
        // most likely to land. Cheap, and it is the reason good players seem to fly less far than bad ones.
        if (d < 2.8 && target != null && target.isSprinting() && ctx.tick - lastHurt > 4
                && ctx.tick - lastSprintCancel > 10) {
            ctx.motor.sprint(false);
            lastSprintCancel = ctx.tick;
            CombatStats.inc("pvp.sprintcancel");
        }

        // jump crits / sprint-jump in (not with the void behind: airborne = more knockback)
        if (ground && critCooldown == 0 && !jumpedNow) {
            boolean crit = d < 3.1 && ctx.tick - lastHurt > 10 && !edgeBehind && ctx.rng.nextDouble() < ctx.persona.jumpCritChance * 0.25;
            // finisher: one hit from dead is worth jumping for, every time
            if (target != null && target.getHealth() <= 8 && d < 3.4 && !edgeBehind && ctx.tick - lastHurt > 6) {
                crit = true;
                CombatStats.inc("pvp.crit.finish");
            }
            // jump-crit: shadow-learned like the rest of the mechanics (the persona habit is the base until the value
            // model has seen enough exchanges)
            if (d < 3.6) {
                Boolean mlCrit = net.h2020.bots.ai.ml.CombatArbiter.decide(net.h2020.bots.ai.ml.CombatArbiter.JUMPCRIT,
                        ctx, arbFeatures(d), crit);
                if (mlCrit != null) crit = mlCrit && !edgeBehind && ctx.tick - lastHurt > 10;
            }
            boolean closeIn = d > 4.5 && ctx.persona.sprintJumper && ctx.rng.nextDouble() < 0.15;
            if (!crit && !closeIn && ctx.persona.skill.overall < 0.35 && ctx.rng.nextDouble() < 0.06) crit = true;
            if ((crit || closeIn) && wv.passable(floor(me.getX()), floor(me.getY()) + 2, floor(me.getZ()))) {
                ctx.motor.jump(true);
                critCooldown = AIMath.range(ctx.rng, 10, 24);
            }
        }

        if (blockHitTicks > 0 || stanceBlockHit) {
            if (blockHitTicks > 0) blockHitTicks--;
            if (Inv.holding(ctx.player, Inv.SWORD)) ctx.motor.holdRight();
        } else if (!stanceNoClick && d < clickRange()) {
            fire(cpsNow);
        }

        // being comboed: rod them to reset it
        if (beingComboed && style.rodReset && allowRod && rodCooldown == 0 && d > 2.0 && d < 4.5 && Inv.findHotbar(ctx.player, Inv.ROD) >= 0
                && ctx.rng.nextDouble() < 0.3) {
            castRod(tl, d, "rod.reset");
        }
        // rod opener: outside melee reach a rod cast interrupts the approach and drags them into our first swing,
        // which is the classic way a 1.8 player starts a fight they did not want to sprint into
        if (!beingComboed && style.rodReset && allowRod && rodCooldown == 0 && d >= 4.5 && d <= 9.0
                && Inv.findHotbar(ctx.player, Inv.ROD) >= 0 && ctx.rng.nextDouble() < 0.45) {
            castRod(tl, d, "rod.open");
        }
    }

    private void melee(Location tl, double d) {
        Location me = ctx.pos();
        WorldView wv = ctx.world();
        if (Inv.bestMeleeHotbar(ctx.player) >= 0) {
            int slot = Inv.bestMeleeHotbar(ctx.player);
            if (ctx.c.heldSlot() != slot) ctx.c.selectHotbar(slot);
        } else Inv.equip(ctx.player, ctx.c, Inv.SWORD, 0);
        aimTarget(Motor.P_COMBAT, AimIntent.COMBAT);

        double dx = tl.getX() - me.getX(), dz = tl.getZ() - me.getZ();
        double len = Math.max(1e-3, Math.sqrt(dx * dx + dz * dz));
        double ux = dx / len, uz = dz / len;

        // void knock: approach from the side opposite to the void next to the target
        Vector voidDir = voidDirection(tl);
        double mx = ux, mz = uz;
        if (voidDir != null && d > 2.2) {
            double wantX = tl.getX() - voidDir.getX() * 2.6, wantZ = tl.getZ() - voidDir.getZ() * 2.6;
            double wx = wantX - me.getX(), wz = wantZ - me.getZ();
            double wl = Math.sqrt(wx * wx + wz * wz);
            if (wl > 0.8) { mx = wx / wl; mz = wz / wl; }
        }

        boolean onBridge = wv.voidSides(floor(me.getX()), floor(me.getY() + 0.01), floor(me.getZ())) >= 2;
        boolean strafe = ctx.persona.strafes && !onBridge && d < 3.6;
        if (ctx.tick >= nextStrafeSwitch) {
            if (ctx.rng.nextDouble() < 0.8) { strafeDir = -strafeDir; CombatStats.inc("strafeswitch"); }
            nextStrafeSwitch = ctx.tick + AIMath.msToTicks(ctx.rng, ctx.persona.strafeSwitchMs);
        }
        double fwd = 1;
        // 1.8 players keep holding W through a combo (sprint is reset by the W/S-tap below); they only ease off when
        // they're basically inside the opponent
        if (d < 1.7) fwd = 0;
        if (d < 1.0) fwd = -1;
        if (wtapTicks > 0) { wtapTicks--; fwd = sTapNow ? -1 : 0; }

        // world move direction: forward toward target plus strafe component
        double sx = 0, sz = 0;
        if (strafe) { sx = -uz * strafeDir; sz = ux * strafeDir; }
        double wx = mx * fwd + sx * 0.9, wz = mz * fwd + sz * 0.9;
        if (!safe(me, wx, wz)) {
            // try the other strafe direction, then straight, then nothing
            double ax = mx * fwd - sx * 0.9, az = mz * fwd - sz * 0.9;
            if (strafe && safe(me, ax, az)) { strafeDir = -strafeDir; wx = ax; wz = az; }
            else if (safe(me, mx * fwd, mz * fwd)) { wx = mx * fwd; wz = mz * fwd; }
            else { wx = 0; wz = 0; ctx.motor.sneak(onBridge); }
        }
        if (Math.abs(wx) + Math.abs(wz) < 1e-3 && d > 3.0) {
            wx = ux; wz = uz;
            net.h2020.api.stats.CombatStats.inc("combat.noIdleInFight");
        }
        if (Math.abs(wx) + Math.abs(wz) > 1e-3) ctx.motor.moveToward(wx, wz);
        ctx.motor.sprint(fwd > 0 && wtapTicks == 0);

        // jump crits / sprint-jump in
        if (ctx.c.onGround() && critCooldown == 0) {
            boolean crit = d < 3.1 && ctx.tick - lastHurt > 10 && ctx.rng.nextDouble() < ctx.persona.jumpCritChance * 0.25;
            boolean closeIn = d > 4.5 && ctx.persona.sprintJumper && ctx.rng.nextDouble() < 0.15;
            // beginners jump around a lot in fights (it doesn't help them)
            if (!crit && !closeIn && ctx.persona.skill.overall < 0.35 && ctx.rng.nextDouble() < 0.06) crit = true;
            if ((crit || closeIn) && wv.passable(floor(me.getX()), floor(me.getY()) + 2, floor(me.getZ()))) {
                ctx.motor.jump(true);
                critCooldown = AIMath.range(ctx.rng, 10, 24);
            }
        }

        // clicking / block-hitting
        if (blockHitTicks > 0) {
            blockHitTicks--;
            if (Inv.holding(ctx.player, Inv.SWORD)) ctx.motor.holdRight();
        } else if (d < clickRange()) {
            fire(cpsNow);
        }
    }

    /** Is moving in world direction (x,z) safe from walking off into the void (v2: or into lava / fire)? */
    private boolean safe(Location me, double x, double z) {
        double len = Math.sqrt(x * x + z * z);
        if (len < 1e-3) return true;
        WorldView wv = ctx.world();
        double ahead = 0.9;
        int cx = floor(me.getX() + x / len * ahead), cz = floor(me.getZ() + z / len * ahead), cy = floor(me.getY() + 0.01);
        if (CombatStats.v2) {
            byte f = wv.shape(cx, cy, cz), b = wv.shape(cx, cy - 1, cz);
            if (f == Shape.LAVA || b == Shape.LAVA || wv.type(cx, cy, cz) == Material.FIRE) return false;
            // moving fast (sprinting, knocked): look a bit further ahead too
            Vector v = ctx.c.velocity();
            double sp = Math.sqrt(v.getX() * v.getX() + v.getZ() * v.getZ());
            if (sp > 0.2) {
                int fx = floor(me.getX() + x / len * 1.7), fz = floor(me.getZ() + z / len * 1.7);
                if ((fx != cx || fz != cz) && !wv.standable(fx, cy, fz) && !wv.standable(fx, cy - 1, fz) && wv.passable(fx, cy, fz)
                        && wv.voidBelow(fx, cy, fz, 30) && !(wv.standable(cx, cy, cz) || wv.standable(cx, cy - 1, cz))) return false;
            }
        }
        if (wv.standable(cx, cy, cz) || wv.standable(cx, cy - 1, cz) || wv.standable(cx, cy + 1, cz)) return true;
        if (!wv.passable(cx, cy, cz)) return true;
        return wv.landing(cx, cy - 1, cz, 4) != Integer.MIN_VALUE;
    }

    /** Average horizontal direction from the target toward adjacent void (null if none within 2 blocks). */
    private Vector voidDirection(Location tl) {
        WorldView wv = ctx.world();
        int x = floor(tl.getX()), y = floor(tl.getY() + 0.01), z = floor(tl.getZ());
        double vx = 0, vz = 0;
        int n = 0;
        for (int r = 1; r <= 2; r++) {
            int[][] dirs = {{r, 0}, {-r, 0}, {0, r}, {0, -r}};
            for (int[] dd : dirs) {
                if (wv.passable(x + dd[0], y, z + dd[1]) && wv.voidBelow(x + dd[0], y, z + dd[1], 20)) {
                    vx += dd[0] / (double) r; vz += dd[1] / (double) r; n++;
                }
            }
        }
        if (n == 0) return null;
        double l = Math.sqrt(vx * vx + vz * vz);
        if (l < 1e-3) return null;
        return new Vector(vx / l, 0, vz / l);
    }

    private Location nearestPoint(int type, Location at, double max) {
        Location best = null;
        double bd = max;
        for (Location l : net.h2020.api.game.GeneratorRegistry.points(at.getWorld(), type)) {
            double dist = l.distance(at);
            if (dist < bd) { bd = dist; best = l; }
        }
        return best;
    }

    private void backOff(Location tl, double d) {
        Location me = ctx.pos();
        // the generator room trick: walls eat their knockback, so a losing fight retreats into one when it is close
        if (target != null && ctx.health() + 4 < target.getHealth() && ctx.tick - lastGenRetreat > 40) {
            Location gen = nearestPoint(net.h2020.api.game.GeneratorRegistry.TEAM_GEN, me, 9.0);
            if (gen != null) {
                double gx = gen.getX() - me.getX(), gz = gen.getZ() - me.getZ();
                ctx.motor.moveToward(gx, gz);
                ctx.motor.sprint(true);
                lastGenRetreat = ctx.tick;
                CombatStats.inc("trick.genroom");
                return;
            }
        }
        double dx = me.getX() - tl.getX(), dz = me.getZ() - tl.getZ();
        double len = Math.max(1e-3, Math.sqrt(dx * dx + dz * dz));
        double wx = dx / len, wz = dz / len;
        if (!safe(me, wx, wz)) {
            // slide sideways instead
            double ax = -wz, az = wx;
            if (safe(me, ax, az)) { wx = ax; wz = az; }
            else if (safe(me, -ax, -az)) { wx = -ax; wz = -az; }
            else { return; }   // boxed in: stand still rather than creep-sneak
        }
        ctx.motor.moveToward(wx, wz);
        // running away facing forward is faster: turn around when far enough
        if (d > 5 && !eating()) {
            ctx.motor.aimRotation(Motor.P_COMBAT + 1, AIMath.yawOf(wx, wz), 10, AimIntent.NAVIGATE, 8);
            ctx.motor.sprint(true);
        }
    }

    // ------------------------------------------------------------------------------------------------ ranged

    /** v2: select the rod now, cast on a following tick once it is in the hand (no same-tick swap-and-click). */
    private void castRod(Location tl, double d, String stat) {
        int slot = Inv.findHotbar(ctx.player, Inv.ROD);
        if (slot < 0) return;
        if (ctx.c.heldSlot() != slot) ctx.c.selectHotbar(slot);
        rodWant = AIMath.range(ctx.rng, 1, 3);
        rodStat = stat;
    }

    private boolean rodStep(Location tl, double d) {
        rodWant--;
        int slot = Inv.findHotbar(ctx.player, Inv.ROD);
        if (slot < 0) { rodWant = 0; return false; }
        if (ctx.c.heldSlot() != slot) { ctx.c.selectHotbar(slot); return true; }
        Location aim = Ballistics.aim(ctx.eye(), tl.clone().add(0, 1.0, 0), vel(), Ballistics.HOOK, style.leadSkill,
                style.projErrDeg * 1.3, shot());
        ctx.motor.aimPoint(Motor.P_COMBAT, aim != null ? aim : lead(tl, d, 1.4, 0.04), AimIntent.COMBAT, 0.3);
        if (rodWant <= 0) {
            ctx.c.rightClick();
            rodWant = 0;
            rodTicks = AIMath.range(ctx.rng, 5, 10);
            CombatStats.inc(rodStat);
        }
        return true;
    }

    private Vector vel() {
        Sighting s = ctx.perception.get(target);
        return s != null ? s.lastVel : new Vector();
    }

    private boolean ranged(double d, Location tl) {
        boolean v2 = CombatStats.v2;
        if (v2 && rodWant > 0 && rodStep(tl, d)) return true;
        // fishing rod: cast as they come into range (pulls them in / breaks their sprint), then swap back to the sword
        // (1.8 drops the hook when the rod leaves the hand). Only from the hotbar: no inventory clicks mid-fight.
        if (rodTicks > 0) {
            rodTicks--;
            aimTarget(Motor.P_COMBAT, AimIntent.COMBAT);
            Location me = ctx.pos();
            double ax = tl.getX() - me.getX(), az = tl.getZ() - me.getZ();
            if (d > 2.6 && safe(me, ax, az)) { ctx.motor.moveToward(ax, az); ctx.motor.sprint(true); }
            if (rodTicks == 0) {
                if (v2) selectWeapon(); else Inv.equip(ctx.player, ctx.c, Inv.SWORD, 0);
                rodCooldown = AIMath.range(ctx.rng, 25, 80);
            }
            return true;
        }
        if (allowRod && ctx.persona.rodder && rodCooldown == 0 && d > 3.4 && d < 7.5 && Inv.findHotbar(ctx.player, Inv.ROD) >= 0
                && ctx.rng.nextDouble() < 0.08) {
            if (v2) { castRod(tl, d, "rod"); return true; }
            if (Inv.equip(ctx.player, ctx.c, Inv.ROD, 1) && Inv.holding(ctx.player, Inv.ROD)) {
                ctx.motor.aimPoint(Motor.P_COMBAT, lead(tl, d, 1.4, 0.04), AimIntent.COMBAT, 0.3);
                ctx.c.rightClick();
                rodTicks = AIMath.range(ctx.rng, 5, 10);
                CombatStats.inc("rod");
            } else rodCooldown = 4; // selected this tick: cast next time it comes up
            return true;
        }
        Vector edge = voidDirection(tl);
        // fireball at bridgers / people on edges (Bed Wars)
        if (allowFireball && fireballCooldown == 0 && d > 6 && d < 26 && edge != null
                && Inv.has(ctx.player, Inv.FIREBALL) && ctx.rng.nextDouble() < 0.08) {
            if (v2 ? Inv.findHotbar(ctx.player, Inv.FIREBALL) >= 0 && Inv.equip(ctx.player, ctx.c, Inv.FIREBALL, 6)
                    : Inv.equip(ctx.player, ctx.c, Inv.FIREBALL, 6)) {
                Location at = tl.clone().add(0, 0.4, 0);
                if (v2) {
                    // straight flight at the server's constant fireball speed: lead them a bit, aim at the feet
                    double t = d / Ballistics.FIREBALL[0];
                    Vector v = vel();
                    at = tl.clone().add(v.getX() * t * style.leadSkill, 0.2, v.getZ() * t * style.leadSkill);
                    double e = Math.toRadians(style.projErrDeg) * d;
                    at.add(ctx.rng.nextGaussian() * e, ctx.rng.nextGaussian() * e * 0.5, ctx.rng.nextGaussian() * e);
                }
                ctx.motor.aimPoint(Motor.P_COMBAT, at, AimIntent.COMBAT, 0.3);
                if (ctx.c.aimSettled()) { ctx.c.rightClick(); fireballCooldown = 50; CombatStats.inc("fireball"); }
            }
            return true;
        }
        // snowballs / eggs for void knockback
        boolean throwHot = Inv.findHotbar(ctx.player, Inv.THROWABLE) >= 0;
        if (allowThrowables && (v2 ? throwHot && throwsLeft > 0 : throwsLeft > 0 && Inv.has(ctx.player, Inv.THROWABLE))
                && d > 3.5 && d < 22 && edge != null) {
            if (Inv.equip(ctx.player, ctx.c, Inv.THROWABLE, 5)) {
                Location aim = v2 ? Ballistics.aim(ctx.eye(), tl.clone().add(0, 1.0, 0), vel(), Ballistics.THROWABLE, style.leadSkill, style.projErrDeg, shot()) : null;
                ctx.motor.aimPoint(Motor.P_COMBAT, aim != null ? aim : lead(tl, d, 1.5, 0.03), AimIntent.COMBAT, 0.25);
                if (rangedCooldown == 0 && ctx.c.aimSettled()) {
                    ctx.c.rightClick();
                    throwsLeft--;
                    rangedCooldown = v2 ? AIMath.range(ctx.rng, style.throwGap, style.throwGap + 4) : AIMath.range(ctx.rng, 4, 9);
                    CombatStats.inc("throw");
                }
            }
            standOrStrafe();
            return true;
        }
        // engage pearl (tryhards)
        if (allowPearl && !pearlUsed && ctx.persona.tryhard && d > 18 && d < 32 && Inv.has(ctx.player, Inv.PEARL)
                && ctx.rng.nextDouble() < 0.01) {
            if (v2) { if (tryEngagePearl(tl, d)) return true; }
            else {
                if (Inv.equip(ctx.player, ctx.c, Inv.PEARL, 8)) {
                    Location land = tl.clone().add(ctx.pos().toVector().subtract(tl.toVector()).normalize().multiply(2.5));
                    ctx.motor.aimPoint(Motor.P_COMBAT, lead(land, d, 1.5, 0.03), AimIntent.COMBAT, 0.25);
                    if (ctx.c.aimSettled()) { ctx.c.rightClick(); pearlUsed = true; CombatStats.inc("pearl.engage"); }
                }
                return true;
            }
        }
        // bow
        boolean hasBow = v2 ? Inv.findHotbar(ctx.player, Inv.BOW) >= 0 && Inv.has(ctx.player, Inv.ARROW)
                : Inv.has(ctx.player, Inv.BOW) && Inv.has(ctx.player, Inv.ARROW);
        boolean punch = false;
        boolean wantBow = usingBowThisFight;
        if (v2 && hasBow && allowBow) {
            int bs = Inv.findHotbar(ctx.player, Inv.BOW);
            ItemStack bow = bs >= 0 ? ctx.player.getInventory().getItem(bs) : null;
            punch = bow != null && bow.getEnchantmentLevel(Enchantment.ARROW_KNOCKBACK) > 0;
            // knock bridgers / edge-standers off, or kite someone with better gear
            if (edge != null && (punch || ctx.persona.skill.overall > 0.4)) wantBow = true;
            if (style.kiter && gearDiff > 4) wantBow = true;
        }
        double minBow = v2 && (punch || (style.kiter && gearDiff > 4)) ? 7 : 9;
        boolean bowRange = d > minBow && d < 45;
        if (allowBow && wantBow && (bowRange || drawTicks > 0) && hasBow) {
            if (d < 6) { drawTicks = 0; return false; } // they closed in: switch to sword
            if (!Inv.equip(ctx.player, ctx.c, Inv.BOW, 2)) return true;
            if (drawTicks == 0) {
                if (rangedCooldown > 0) { standOrStrafe(); return true; }
                drawTarget = AIMath.range(ctx.rng, 15, 24);
                if (v2 && d > 25) drawTarget = AIMath.range(ctx.rng, 20, 26); // full draw for long shots
            }
            Location aim = null;
            if (v2) aim = Ballistics.aim(ctx.eye(), tl.clone().add(0, 1.0, 0), vel(), Ballistics.arrow(Math.max(drawTicks + 1, drawTarget)),
                    style.leadSkill, style.projErrDeg * 0.5, shot());
            ctx.motor.aimPoint(Motor.P_COMBAT, aim != null ? aim : lead(tl, d, 2.9, 0.05), AimIntent.COMBAT, 0.2);
            drawTicks++;
            if (drawTicks < drawTarget || !ctx.c.aimSettled()) {
                ctx.motor.holdRight();
                if (drawTicks > 60) { drawTicks = 0; rangedCooldown = 10; }
            } else {
                // release (not holding right this tick)
                drawTicks = 0;
                rangedCooldown = AIMath.range(ctx.rng, 6, 14);
                CombatStats.inc(punch ? "bow.punch" : "bow");
            }
            standOrStrafe();
            return true;
        }
        return false;
    }

    private void standOrStrafe() {
        if (!ctx.persona.strafes) return;
        if (ctx.tick >= nextStrafeSwitch) {
            strafeDir = -strafeDir;
            nextStrafeSwitch = ctx.tick + AIMath.msToTicks(ctx.rng, ctx.persona.strafeSwitchMs * 1.5);
        }
        float yaw = ctx.c.yaw();
        double lx = Math.cos(Math.toRadians(yaw)) * strafeDir, lz = Math.sin(Math.toRadians(yaw)) * strafeDir;
        if (safe(ctx.pos(), lx, lz)) ctx.motor.move(0, strafeDir);
    }

    /** Aim point leading the target for a projectile with speed v (blocks/tick) and gravity g (blocks/tick^2). */
    private Location lead(Location tl, double d, double v, double g) {
        Sighting s = ctx.perception.get(target);
        Vector vel = s != null ? s.lastVel : new Vector();
        double t = d / (v * 0.96);
        double skill = 0.55 + 0.45 * ctx.persona.skill.aim;
        double drop = 0.5 * g * t * t * 1.1;
        double err = (1 - ctx.persona.skill.aim) * d * 0.035;
        return tl.clone().add(vel.getX() * t * skill + ctx.rng.nextGaussian() * err,
                1.1 + drop + ctx.rng.nextGaussian() * err * 0.6,
                vel.getZ() * t * skill + ctx.rng.nextGaussian() * err);
    }

    /**
     * Pearl to the far side of a gap when the target can't be reached on foot (v2). Lands ~2.5 blocks short of them on
     * ground they stand on. @return true while doing it.
     */
    private boolean tryEngagePearl(Location tl, double d) {
        if (!allowPearl || pearlUsed || d < 8 || d > 34 || Inv.findHotbar(ctx.player, Inv.PEARL) < 0) return false;
        if (ctx.health() < 10 || !los(tl)) return false;
        WorldView wv = ctx.world();
        Vector back = ctx.pos().toVector().subtract(tl.toVector()).setY(0);
        if (back.lengthSquared() < 1e-3) return false;
        back.normalize();
        for (double off = 2.5; off >= 0.5; off -= 1.0) {
            int x = floor(tl.getX() + back.getX() * off), z = floor(tl.getZ() + back.getZ() * off);
            int land = wv.landing(x, floor(tl.getY()) + 2, z, 5);
            if (land == Integer.MIN_VALUE) continue;
            if (!Inv.equip(ctx.player, ctx.c, Inv.PEARL, 8)) return true;
            Location spot = new Location(tl.getWorld(), x + 0.5, land + 0.1, z + 0.5);
            Location aim = Ballistics.aim(ctx.eye(), spot, new Vector(), Ballistics.PEARL, 1, style.projErrDeg, shot());
            if (aim == null) return false;
            ctx.motor.aimPoint(Motor.P_COMBAT, aim, AimIntent.COMBAT, 0.5);
            if (ctx.c.aimSettled() || Ballistics.aimErr(ctx, aim) < 2.0) {
                ctx.c.rightClick();
                pearlUsed = true;
                CombatStats.inc("pearl.engage");
                bestDistTick = ctx.tick; // give the fight another go from over there
            }
            return true;
        }
        return false;
    }

    /** @return true while aiming/throwing (v1 always returns false after acting, as before). */
    private boolean escapePearl(Location tl) {
        Location me = ctx.pos();
        Vector away = me.toVector().subtract(tl.toVector()).setY(0);
        if (away.lengthSquared() < 1e-3) return false;
        away.normalize();
        // find a safe landing ~12 blocks away
        WorldView wv = ctx.world();
        boolean v2 = CombatStats.v2;
        for (int dist = 14; dist >= 6; dist -= 2) {
            for (int turn = 0; turn < (v2 ? 3 : 1); turn++) {
                // (v2: also try a bit to the left/right of straight away)
                double ang = turn == 0 ? 0 : (turn == 1 ? 0.6 : -0.6);
                double ax = away.getX() * Math.cos(ang) - away.getZ() * Math.sin(ang), az = away.getX() * Math.sin(ang) + away.getZ() * Math.cos(ang);
                int x = floor(me.getX() + ax * dist), z = floor(me.getZ() + az * dist);
                int land = wv.landing(x, floor(me.getY()) + 3, z, 8);
                if (land == Integer.MIN_VALUE) continue;
                if (Inv.equip(ctx.player, ctx.c, Inv.PEARL, 8)) {
                    Location tgt = new Location(me.getWorld(), x + 0.5, land, z + 0.5);
                    Location aim = v2 ? Ballistics.aim(ctx.eye(), tgt, new Vector(), Ballistics.PEARL, 1, style.projErrDeg, shot()) : null;
                    Location ap = aim != null ? aim : lead(tgt, dist, 1.5, 0.03);
                    ctx.motor.aimPoint(Motor.P_COMBAT + 2, ap, AimIntent.FLICK, v2 ? 0.6 : 0.3);
                    if (ctx.c.aimSettled() || (v2 && Ballistics.aimErr(ctx, ap) < 3.0)) {
                        ctx.c.rightClick();
                        pearlUsed = true;
                        pearlEscapeTicks = 0;
                        CombatStats.inc("pearl.escape");
                    }
                }
                return v2;
            }
        }
        return false;
    }

    private boolean los(Location tl) {
        if (ctx.tick - losTick >= 5) {
            int r = Raycast.lineOfSight(ctx.player.getWorld(), ctx.eye(), tl.clone().add(0, 1.4, 0));
            if (r != Raycast.UNKNOWN) losCache = r;
            losTick = ctx.tick;
        }
        return losCache == Raycast.CLEAR;
    }

    public boolean holdingMaterial(Material m) {
        return ctx.player.getItemInHand() != null && ctx.player.getItemInHand().getType() == m;
    }

    /** Error sample that stays the same for ~1.2 s (one throw / one draw), so the crosshair has a fixed place to go. */
    private final long shotSalt = new java.util.Random().nextLong();
    private java.util.Random shot() { return new java.util.Random(shotSalt ^ (ctx.tick / 25) * 0x9E3779B97F4A7C15L); }

    private static int floor(double d) { int i = (int) d; return d < i ? i - 1 : i; }
}
