package net.h2020.bots.ai.combat;

import net.h2020.bots.ai.Persona;
import net.h2020.bots.ai.util.AIMath;

import java.util.Random;

/**
 * A bot's 2020 PvP habits beyond the basic Persona traits, derived from the same seed (stable across games) and
 * scaled by skill: who jump-resets, who combo-locks, who clutches and how fast, who knows the water bucket / pearl
 * saves, who drinks potions at the start of a fight, who plays lava and flint &amp; steel, who kites better-geared
 * players. Most values are probabilities per opportunity.
 */
public final class Style {
    /** Chance to time a jump right as a melee hit lands (on the ground, sprinting at them): less knockback. */
    public final double jumpReset;
    /** Keeps pressure in a combo: straight in, sprint resets, no strafe switching while the target is in hitstun. */
    public final boolean comboLock;
    /** Backs out after landing hits on a stronger opponent, comes back in when they over-commit. */
    public final boolean hitAndRun;
    /** Keeps distance against better-geared enemies (bow/rod/projectiles first). */
    public final boolean kiter;
    /** Circle strafing quality 0..1 (switch timing irregularity, double-switches). */
    public final double strafeSkill;
    /** Edge awareness 0..1: keeps the void out of their back in a fight. */
    public final double edgeSense;
    /** Block-clutch ability 0..1 (0 = never tries). */
    public final double clutch;
    /** Extra reaction ticks before a clutch starts (on top of the mouse's reaction delay). */
    public final int clutchDelay;
    public final boolean waterClutch;
    public final boolean pearlSave;
    /** Drinks speed/strength at the start of a fight, splashes heals when low. */
    public final boolean potions;
    public final boolean lava;
    public final boolean fire;
    /** Pillars up / walls themselves in when low and chased. */
    public final boolean blockIn;
    /** Walls off a 1-wide bridge behind them when fleeing. */
    public final boolean bridgeBlock;
    /** Rods to break an opponent's combo. */
    public final boolean rodReset;
    /** Throws projectile spam at people on edges (per-throw gap in ticks). */
    public final int throwGap;
    /** Angular aim error with projectiles (degrees, 1 sigma). */
    public final double projErrDeg;
    /** How well they lead a moving target with projectiles (0 = aim where they are, 1 = perfect). */
    public final double leadSkill;
    /** Block stomping: places a block into the step an approaching opponent is taking (a second one on top if they
     *  jump) to stop their momentum and take the free hit. Probability per opportunity. */
    public final double stomp;
    /** Builds a 2-block pillar between the two of us and strafes around it: their client is still looking at where we
     *  were, so the first hit is free. Probability per opportunity. */
    public final double duelPillar;
    /** Breaks the middle of the line between us on a bridge and pushes through the hole while they jump it. */
    public final double middleExit;
    /** Places a head hitter (a block over their own head, from a side support) on bridges: more jump momentum, less
     *  knockback taken. Probability per opportunity. */
    public final double headHitter;
    /** Punches incoming fireballs back (0 = only ever steps out of their line). */
    public final double fireballDeflect;
    /** With a wall at their back and nowhere to run: plants and fights instead of backing off. */
    public final boolean cornerFight;
    /** Goes for a bed that is in reach during a fight instead of only when the brain says so. */
    public final boolean bedRaider;

    public Style(Persona p) {
        Random r = new Random(p.seed * 0x9E3779B97F4A7C15L + 77);
        double o = p.skill.overall;
        jumpReset = r.nextDouble() < 0.12 + 0.7 * o ? AIMath.clamp(0.25 + 0.6 * o + r.nextGaussian() * 0.1, 0.1, 0.85)
                : AIMath.clamp(0.04 * o, 0, 0.05);
        comboLock = r.nextDouble() < 0.25 + 0.65 * o;
        hitAndRun = r.nextDouble() < 0.1 + 0.4 * o;
        kiter = o > 0.35 && r.nextDouble() < 0.3 + 0.5 * o;
        strafeSkill = AIMath.clamp(o + r.nextGaussian() * 0.15, 0, 1);
        edgeSense = AIMath.clamp(0.15 + 0.85 * o + r.nextGaussian() * 0.12, 0, 1);
        double c = AIMath.clamp(1.15 * o - 0.12 + r.nextGaussian() * 0.15, 0, 1);
        clutch = c < 0.12 ? 0 : c;
        clutchDelay = (int) Math.round(AIMath.clamp(3.5 - 3.5 * o + r.nextGaussian(), 0, 6));
        waterClutch = r.nextDouble() < 0.1 + 0.75 * o;
        pearlSave = r.nextDouble() < 0.15 + 0.75 * o;
        potions = r.nextDouble() < 0.25 + 0.65 * o;
        lava = r.nextDouble() < 0.15 + 0.55 * o;
        fire = r.nextDouble() < 0.1 + 0.4 * o;
        blockIn = r.nextDouble() < 0.08 + 0.5 * o;
        bridgeBlock = r.nextDouble() < 0.05 + 0.55 * o;
        rodReset = p.rodder && r.nextDouble() < 0.3 + 0.6 * o;
        throwGap = (int) Math.round(AIMath.clamp(9 - 6 * o + r.nextGaussian(), 3, 12));
        projErrDeg = AIMath.clamp(2.6 - 2.1 * p.skill.aim + Math.abs(r.nextGaussian()) * 0.4, 0.35, 3.5);
        leadSkill = AIMath.clamp(0.25 + 0.7 * p.skill.aim + r.nextGaussian() * 0.1, 0.1, 1.0);
        // blocks in a fight: beginners do not think of them, decent players stomp and stab from behind a pillar
        stomp = AIMath.clamp(0.05 + 0.85 * o + r.nextGaussian() * 0.1, 0, 1);
        duelPillar = AIMath.clamp(0.9 * o - 0.15 + r.nextGaussian() * 0.12, 0, 1);
        middleExit = AIMath.clamp(0.8 * o - 0.1 + r.nextGaussian() * 0.12, 0, 1);
        headHitter = AIMath.clamp(0.75 * o - 0.1 + r.nextGaussian() * 0.15, 0, 1);
        fireballDeflect = AIMath.clamp(0.9 * o - 0.2 + r.nextGaussian() * 0.12, 0, 1);
        cornerFight = r.nextDouble() < 0.3 + 0.6 * o;
        bedRaider = r.nextDouble() < 0.15 + 0.7 * o;
    }
}
