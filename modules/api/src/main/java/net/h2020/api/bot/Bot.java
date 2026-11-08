package net.h2020.api.bot;

import org.bukkit.entity.Player;

/** A server-side fake player driven by a virtual client. To the server (and Watchdog) it is an ordinary Player. */
public interface Bot {
    Player player();
    BotProfile profile();
    BotController controller();
    BotBrain brain();
    /** Replace the brain (null = idle: stand, look around occasionally). Old brain gets stop(). */
    void setBrain(BotBrain brain);
    boolean isRemoved();
    /** Disconnect as if the client quit (quit message, tab removal). */
    void remove();
}
