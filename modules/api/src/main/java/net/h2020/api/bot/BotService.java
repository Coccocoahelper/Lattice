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
}
