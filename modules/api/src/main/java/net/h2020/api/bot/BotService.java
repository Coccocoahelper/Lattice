package net.h2020.api.bot;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Collection;

/** Owned by bots. */
public interface BotService {
    /** Random realistic profile (2020-style username, skin, rank distribution ~70% NONE, skill distribution). */
    BotProfile randomProfile();
    /** As randomProfile() but with a forced cheat profile. */
    BotProfile randomProfile(CheatProfile cheat);
    /** "Logs in" the bot: join message suppressed/handled like a real join, tab entry, skin, spawns at location. */
    Bot spawn(BotProfile profile, Location at);
    Collection<Bot> bots();
    boolean isBot(Player p);
    /** null if not a bot */
    Bot bot(Player p);

    /**
     * A brain that walks to the edge of what it is standing on and bridges in one direction until it runs out of
     * blocks, using the named technique ("auto" for the bot's own). This is what the practice drill puts on a bot so
     * a bridging run can be measured; null when this server has no bridging brain.
     */
    default BotBrain bridgeBrain(int dx, int dz, int maxBlocks, String technique) { return BrainFactory.none(); }

    /**
     * A brain that fills the given cells with blocks, as fast as its placement allows: what the bed-defence drill
     * hands a bot so its time is measured on the same structure a person builds.
     */
    default BotBrain bedDefenceBrain(int[][] cells, int bedX, int bedY, int bedZ) {
        return bedDefenceBrain(cells, bedX, bedY, bedZ, false);
    }

    /**
     * @param indoor the work is done from inside a standing defence - on top of the bed, say - where every cell is
     *               already in reach and walking to one is walking out of the thing being sealed
     */
    default BotBrain bedDefenceBrain(int[][] cells, int bedX, int bedY, int bedZ, boolean indoor) {
        return BrainFactory.none();
    }

    /**
     * A bot that repeats a block-in: it breaks the hole open itself, then seals it. Each hole is a cell list, so the
     * bot learns every place a person has done it rather than one.
     */
    default BotBrain blockInBrain(java.util.List<int[][]> holes) { return BrainFactory.none(); }

    /**
     * A bot that clutches: it stands on the platform, holds blocks, and lets the combat stack's clutch save it when
     * something knocks it off. This is the brain a block-clutch drill hands a bot so its run is the same skill a
     * person is practising. Null when this server has no clutch.
     */
    default BotBrain clutchBrain(org.bukkit.Location home) { return BrainFactory.none(); }

    /**
     * A bot that finds and fights the nearest other body: what a range drill needs, since a target on a plinth is an
     * enemy as far as the combat stack is concerned. Null when this server has no arena brain.
     */
    default BotBrain arenaBrain() { return BrainFactory.none(); }

    /** The arena brain with the bow forced: what a range drill hands its bot. */
    default BotBrain rangedBrain() { return BrainFactory.none(); }
}

