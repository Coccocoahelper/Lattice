package net.h2020.api.core;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.List;

/** Owned by core. Flicker-free per-player sidebar + name tags. Lines top-to-bottom, max 15, each up to 32 visible chars. */
public interface ScoreboardService {
    void setSidebar(Player viewer, String title, List<String> lines);
    void clearSidebar(Player viewer);
    /** Name tag above head / in tab for `target` as seen by everyone. Prefix/suffix max 16 chars each. */
    void setNametag(Player target, String prefix, String suffix, ChatColor nameColor);
    void resetNametag(Player target);
    void setTabHeaderFooter(Player viewer, String header, String footer);
}
