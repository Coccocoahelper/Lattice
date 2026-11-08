package net.h2020.api.core;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

/** Owned by core. Per-player cosmetic network data. Bots get theirs set from BotProfile when they spawn. */
public interface ProfileService {
    Rank rank(Player p);
    ChatColor plusColor(Player p);
    void setRank(Player p, Rank rank, ChatColor plusColor);
    int networkLevel(Player p);
    void setNetworkLevel(Player p, int level);
    /** Rank prefix + name, e.g. "§b[MVP§c+§b] Notch". */
    String displayName(Player p);
}
