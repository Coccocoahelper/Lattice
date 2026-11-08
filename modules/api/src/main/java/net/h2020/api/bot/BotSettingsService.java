package net.h2020.api.bot;

import org.bukkit.entity.Player;

/** Owned by the bots module: lets other menus open the bot settings screen without depending on it. */
public interface BotSettingsService {
    /** Open /bot settings for this player. */
    void open(Player p);
}
