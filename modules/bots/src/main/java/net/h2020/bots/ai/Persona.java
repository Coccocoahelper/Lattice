package net.h2020.bots.ai;

import net.h2020.api.bot.BotProfile;
import net.h2020.api.bot.BotSkill;
import net.h2020.bots.ai.util.AIMath;

import java.util.Random;

/**
 * Derived, stable behaviour traits of one bot (seeded from its UUID so the same bot behaves the same way across
 * games). BotSkill gives the knobs; Persona turns them into concrete habits.
 */
public final class Persona {
    public final BotSkill skill;
    public final BotSkill.BridgeStyle bridgeStyle;
    /** The bridging technique this player is used to (the profile's style, with ninja/breezily players split further). */
    public final net.h2020.bots.ai.motor.Bridger.Tech bridgeTech;
    /** Pillaring habit: 0 = holds jump and places at the apex, 1 = hops one block at a time, 2 = holds shift too. */
    public final int pillarStyle;
    /** Uses a fishing rod in melee range (pull/interrupt) when carrying one. */
    public final boolean rodder;
    /** Sprint-jumps on long straights. */
    public final boolean sprintJumper;
    /** Uses block-hitting (right-click sword between hits). */
    public final boolean blockHitter;
    /** Probability of a W-tap / S-tap after landing a hit. */
    public final double wtapChance;
    /** Uses S-tap instead of W-tap. */
    public final boolean sTapper;
    public final double jumpCritChance;
    /** Mean ms between strafe direction switches in close combat. */
    public final double strafeSwitchMs;
    /** Strafes at all (beginners often just hold W). */
    public final boolean strafes;
    /** Plays in third person (F5): sees around and slightly behind himself, which bridgers rely on. */
    public final boolean thirdPerson;
    /** Likes the bow at range. */
    public final double bowLiking;
    /** How close to void edges they dare to walk (0 scared, 1 walks edges). */
    public final double edgeComfort;
    /** Goes AFK in lobbies. */
    public final boolean afkProne;
    public final double typoRate;
    /** Toxic chatter ("ez", "L"). */
    public final boolean toxic;
    /** Prefers punctuation-free, short messages; some type "gg" others "GG"? (all lowercase on purpose). */
    public final boolean saysGlhf;
    /** Mean ms between natural look-arounds while idle/walking. */
    public final double lookAroundMs;
    /** Health (hp, 0..20) below which they disengage. */
    public final double retreatHealth;
    /** Knows advanced strats (fireballs, TNT, pearls, bed-breaking tunnels). */
    public final boolean tryhard;
    /** Weaving amplitude while walking (blocks). */
    public final double weave;
    /** Placement cadence for bridging (ms between right clicks when ready). */
    public final double bridgeClickMs;
    public final long seed;

    public Persona(BotProfile profile) {
        BotSkill s = profile.skill;
        this.skill = s;
        this.seed = profile.uuid.getMostSignificantBits() ^ profile.uuid.getLeastSignificantBits();
        Random r = new Random(seed);
        double o = s.overall;
        this.bridgeStyle = s.bridgeStyle == null ? BotSkill.BridgeStyle.SHIFT : s.bridgeStyle;
        this.sprintJumper = r.nextDouble() < 0.25 + 0.45 * o;
        this.blockHitter = r.nextDouble() < 0.1 + 0.35 * o;
        this.wtapChance = AIMath.clamp(0.15 + 0.75 * o + r.nextGaussian() * 0.1, 0.0, 0.95);
        this.sTapper = r.nextDouble() < 0.3;
        this.jumpCritChance = AIMath.clamp(0.05 + 0.3 * r.nextDouble() * (1 - Math.abs(o - 0.5)), 0, 0.4);
        this.strafeSwitchMs = AIMath.clamp(900 - 500 * o + r.nextGaussian() * 120, 250, 1400);
        this.strafes = r.nextDouble() < 0.35 + 0.65 * o;
        this.thirdPerson = r.nextDouble() < 0.25 + 0.45 * o;
        this.bowLiking = AIMath.clamp(r.nextDouble() * (0.4 + o), 0, 1);
        this.edgeComfort = AIMath.clamp(0.25 + 0.6 * o + r.nextGaussian() * 0.12, 0.05, 1.0);
        this.afkProne = r.nextDouble() < 0.28;
        this.typoRate = AIMath.clamp(0.04 + 0.12 * r.nextDouble() * (1.2 - o), 0, 0.2);
        this.toxic = s.chattiness > 0.55 && r.nextDouble() < 0.55;
        this.saysGlhf = r.nextDouble() < 0.4;
        this.lookAroundMs = AIMath.clamp(5200 - 3600 * s.awareness + r.nextGaussian() * 600, 1100, 7000);
        this.retreatHealth = AIMath.clamp(4 + 6 * r.nextDouble() * (1 - s.aggression), 3, 10);
        this.tryhard = o > 0.72 && r.nextDouble() < 0.7;
        this.weave = AIMath.clamp(0.12 + r.nextDouble() * 0.25 * (1.1 - o), 0.05, 0.4);
        this.bridgeClickMs = AIMath.clamp(250 - 150 * o + r.nextGaussian() * 25, 60, 400);
        this.bridgeTech = techFor(bridgeStyle, r);
        double pr = r.nextDouble();
        this.pillarStyle = o < 0.3 ? (pr < 0.7 ? 1 : 2) : (pr < 0.65 ? 0 : (pr < 0.85 ? 2 : 1));
        this.rodder = r.nextDouble() < 0.12 + 0.4 * o;
    }

    private static net.h2020.bots.ai.motor.Bridger.Tech techFor(BotSkill.BridgeStyle s, Random r) {
        switch (s) {
            case NOOB: return net.h2020.bots.ai.motor.Bridger.Tech.NOOB;
            case SPEED: return net.h2020.bots.ai.motor.Bridger.Tech.SPEED;
            case NINJA: return r.nextDouble() < 0.6 ? net.h2020.bots.ai.motor.Bridger.Tech.NINJA : net.h2020.bots.ai.motor.Bridger.Tech.MOONWALK;
            case BREEZILY: return r.nextDouble() < 0.8 ? net.h2020.bots.ai.motor.Bridger.Tech.BREEZILY : net.h2020.bots.ai.motor.Bridger.Tech.MOONWALK;
            case SHIFT:
            default: return net.h2020.bots.ai.motor.Bridger.Tech.SHIFT;
        }
    }
}
