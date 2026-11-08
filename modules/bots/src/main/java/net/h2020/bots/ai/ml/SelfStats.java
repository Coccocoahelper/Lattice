package net.h2020.bots.ai.ml;

import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What a bot knows about <i>itself</i>, the variables a player brings into a fight besides gear: how confident he is
 * after the last few fights, how tilted a losing streak has left him, how fast he gets blocks down at a bed (his own
 * block-in speed), and how well his clicks have been landing.
 * <p>
 * Session-scoped and per bot, because it is a property of that bot's persona and its run - not of the server.
 */
public final class SelfStats {
    private static final class Rec {
        int fights, wins;
        int lossStreak, bestLossStreak;
        double confidence = 0.5;          // EMA of fight results
        double hitRate = 0.5;             // EMA of hits / swings in fights
        double blockRate = 0.0;           // blocks placed per second while working a bed, EMA
        long lastBlockTick;
        int blocksSinceMark;
        long markTick;
    }

    private static final Map<UUID, Rec> RECS = new HashMap<UUID, Rec>();

    private SelfStats() {}

    private static Rec rec(Player p) {
        Rec r = RECS.get(p.getUniqueId());
        if (r == null) { r = new Rec(); RECS.put(p.getUniqueId(), r); }
        return r;
    }

    /** A fight ended. */
    public static void onFight(Player p, boolean win) {
        Rec r = rec(p);
        r.fights++;
        if (win) {
            r.wins++;
            r.lossStreak = 0;
        } else {
            r.lossStreak++;
            r.bestLossStreak = Math.max(r.bestLossStreak, r.lossStreak);
        }
        r.confidence = 0.7 * r.confidence + 0.3 * (win ? 1 : 0);
    }

    public static void onHits(Player p, int hits, int swings) {
        if (swings < 3) return;
        Rec r = rec(p);
        double rate = Math.max(0, Math.min(1, hits / (double) swings));
        r.hitRate = 0.8 * r.hitRate + 0.2 * rate;
    }

    /** A block was placed while working a bed defence / breaking one (the block-in speed). */
    public static void onBedBlock(Player p, long tick) {
        Rec r = rec(p);
        if (r.markTick == 0) { r.markTick = tick; r.blocksSinceMark = 0; return; }
        r.blocksSinceMark++;
        long dt = tick - r.markTick;
        if (dt >= 60) {                                   // a three-second window
            double perSecond = r.blocksSinceMark * 20.0 / dt;
            r.blockRate = r.blockRate == 0 ? perSecond : r.blockRate * 0.7 + perSecond * 0.3;
            r.blocksSinceMark = 0;
            r.markTick = tick;
        }
    }

    /** 0..1: recent form (wins out of the last fights, smoothed). */
    public static double confidence(Player p) { return rec(p).confidence; }

    /** 0..1: how deep the current losing streak is (5 losses = 1.0). */
    public static double tilt(Player p) { return Math.min(1.0, rec(p).lossStreak / 5.0); }

    /** Blocks per second the bot gets down at a bed, normalised to 0..1 (5 blocks/s = 1.0). */
    public static double blockInSpeed(Player p) { return Math.min(1.0, rec(p).blockRate / 5.0); }

    public static double hitRate(Player p) { return rec(p).hitRate; }

    public static int fights(Player p) { return rec(p).fights; }

    public static int wins(Player p) { return rec(p).wins; }

    public static void forget(Player p) { RECS.remove(p.getUniqueId()); }

    public static void clear() { RECS.clear(); }
}
