package net.h2020.api.game;

import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.DyeColor;

/** Bedwars team order as on Hypixel 8-team maps (first `teams` entries are used). Skywars teams reuse these too. */
public enum TeamColor {
    RED("Red", ChatColor.RED, DyeColor.RED, Color.fromRGB(0xB02E26)),
    BLUE("Blue", ChatColor.BLUE, DyeColor.BLUE, Color.fromRGB(0x3C44AA)),
    GREEN("Green", ChatColor.GREEN, DyeColor.LIME, Color.fromRGB(0x80C71F)),
    YELLOW("Yellow", ChatColor.YELLOW, DyeColor.YELLOW, Color.fromRGB(0xFED83D)),
    AQUA("Aqua", ChatColor.AQUA, DyeColor.LIGHT_BLUE, Color.fromRGB(0x3AB3DA)),
    WHITE("White", ChatColor.WHITE, DyeColor.WHITE, Color.fromRGB(0xF9FFFE)),
    PINK("Pink", ChatColor.LIGHT_PURPLE, DyeColor.PINK, Color.fromRGB(0xF38BAA)),
    GRAY("Gray", ChatColor.DARK_GRAY, DyeColor.GRAY, Color.fromRGB(0x474F52));

    public final String displayName;
    public final ChatColor chat;
    public final DyeColor dye;
    public final Color armor;

    TeamColor(String displayName, ChatColor chat, DyeColor dye, Color armor) {
        this.displayName = displayName; this.chat = chat; this.dye = dye; this.armor = armor;
    }

    /** Single letter shown in Hypixel scoreboard/tab, e.g. "R". */
    public String letter() { return displayName.substring(0, 1); }

    @SuppressWarnings("deprecation")
    public byte woolData() { return dye.getWoolData(); }
}
