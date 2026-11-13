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
}

