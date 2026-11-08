package net.h2020.api.core;

import org.bukkit.ChatColor;

/** Hypixel 2020 ranks. */
public enum Rank {
    NONE(ChatColor.GRAY, ""),
    VIP(ChatColor.GREEN, "[VIP]"),
    VIP_PLUS(ChatColor.GREEN, "[VIP+]"),
    MVP(ChatColor.AQUA, "[MVP]"),
    MVP_PLUS(ChatColor.AQUA, "[MVP+]"),
    MVP_PLUS_PLUS(ChatColor.GOLD, "[MVP++]"),
    YOUTUBE(ChatColor.RED, "[YOUTUBE]"),
    ADMIN(ChatColor.RED, "[ADMIN]");

    public final ChatColor color;
    public final String tag;

    Rank(ChatColor color, String tag) { this.color = color; this.tag = tag; }

    /**
     * Chat/tab prefix including trailing space, e.g. "§b[MVP§c+§b] ". For NONE it is just "§7".
     * plusColor only matters for MVP+/MVP++ (VIP+ plus is always gold).
     */
    public String prefix(ChatColor plusColor) {
        switch (this) {
            case NONE: return color.toString();
            case VIP_PLUS: return ChatColor.GREEN + "[VIP" + ChatColor.GOLD + "+" + ChatColor.GREEN + "] ";
            case MVP_PLUS: return ChatColor.AQUA + "[MVP" + plusColor + "+" + ChatColor.AQUA + "] ";
            case MVP_PLUS_PLUS: return ChatColor.GOLD + "[MVP" + plusColor + "++" + ChatColor.GOLD + "] ";
            case YOUTUBE: return ChatColor.RED + "[" + ChatColor.WHITE + "YOUTUBE" + ChatColor.RED + "] ";
            default: return color + tag + " ";
        }
    }
}
