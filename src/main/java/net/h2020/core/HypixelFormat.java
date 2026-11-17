package net.h2020.core;

import net.h2020.api.core.Rank;
import org.bukkit.ChatColor;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Hypixel-style text helpers (date line, server ids, comma numbers, progress bars, star/level prefixes).
 * Other modules may not import core; they duplicate the few helpers they need.
 */
public final class HypixelFormat {
    private static volatile String date = "07/14/20";
    private static final ThreadLocal<DecimalFormat> COMMAS = new ThreadLocal<DecimalFormat>() {
        @Override protected DecimalFormat initialValue() { return new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US)); }
    };
    private static final ThreadLocal<DecimalFormat> ONE_DECIMAL = new ThreadLocal<DecimalFormat>() {
        @Override protected DecimalFormat initialValue() { return new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.US)); }
    };

    private HypixelFormat() {}

    /** The scoreboard date: the fake one (e.g. "07/14/20"), or the real current date when set to "today". */
    public static String date() { return resolveDate(date); }

    /** "today" (or blank) -> the real date as MM/dd/yy; anything else is shown as-is. */
    public static String resolveDate(String d) {
        if (d == null || d.trim().isEmpty() || d.trim().equalsIgnoreCase("today")) {
            return new java.text.SimpleDateFormat("MM/dd/yy").format(new java.util.Date());
        }
        return d.trim();
    }
    static void setDate(String d) { if (d != null && !d.isEmpty()) date = d; }

    /** Gray date + dark gray server id, as on every 2020 scoreboard: "§707/14/20  §8m45C". */
    public static String dateLine(String serverId) {
        return ChatColor.GRAY + date() + "  " + ChatColor.DARK_GRAY + serverId;
    }

    /** Random server letter A..H as used on Hypixel instance ids. */
    public static char randomLetter() { return (char) ('A' + ThreadLocalRandom.current().nextInt(8)); }

    /** Bed Wars instance id, e.g. "mini12A". */
    public static String bedwarsId(int number, char letter) { return "mini" + number + letter; }
    /** SkyWars instance id, e.g. "m45C". */
    public static String skywarsId(int number, char letter) { return "m" + number + letter; }
    /** Duels instance id, e.g. "duel12A". */
    public static String duelsId(int number, char letter) { return "duel" + number + letter; }
    /** Lobby server id, e.g. "L3A" (main), "L12B"... */
    public static String lobbyId(int number, char letter) { return "L" + number + letter; }

    public static String commas(long n) { return COMMAS.get().format(n); }

    /** 2,300 -> "2.3k", 5,000 -> "5k", 1,200,000 -> "1.2M". */
    public static String shortNumber(long n) {
        if (n < 1000) return Long.toString(n);
        if (n < 1000000) return ONE_DECIMAL.get().format(n / 1000.0) + "k";
        return ONE_DECIMAL.get().format(n / 1000000.0) + "M";
    }

    /**
     * Bracketed progress bar like the Bed Wars lobby: §8[§b■■■§7■■■■■■■§8].
     * @param fraction 0..1
     */
    public static String progressBar(double fraction, int length, char symbol, ChatColor filled, ChatColor empty) {
        fraction = Math.max(0, Math.min(1, fraction));
        int full = (int) Math.floor(fraction * length);
        StringBuilder sb = new StringBuilder();
        sb.append(ChatColor.DARK_GRAY).append('[').append(filled);
        for (int i = 0; i < full; i++) sb.append(symbol);
        sb.append(empty);
        for (int i = full; i < length; i++) sb.append(symbol);
        sb.append(ChatColor.DARK_GRAY).append(']');
        return sb.toString();
    }

    public static String progressBar(double fraction) { return progressBar(fraction, 10, '■', ChatColor.AQUA, ChatColor.GRAY); }

    /** Bed Wars prestige colour for a star level (2020 prestiges: Stone .. Amethyst, Rainbow at 1000). */
    public static ChatColor bedwarsStarColor(int star) {
        int p = star / 100;
        switch (p) {
            case 0: return ChatColor.GRAY;
            case 1: return ChatColor.WHITE;
            case 2: return ChatColor.GOLD;
            case 3: return ChatColor.AQUA;
            case 4: return ChatColor.DARK_GREEN;
            case 5: return ChatColor.DARK_AQUA;
            case 6: return ChatColor.DARK_RED;
            case 7: return ChatColor.LIGHT_PURPLE;
            case 8: return ChatColor.BLUE;
            case 9: return ChatColor.DARK_PURPLE;
            default: return ChatColor.GOLD;
        }
    }

    /** "§7[12✫]" style Bed Wars chat/scoreboard star. Rainbow for 1000+. */
    public static String bedwarsStar(int star) {
        if (star >= 1000) {
            String s = "[" + star + "✫]";
            ChatColor[] rb = {ChatColor.RED, ChatColor.GOLD, ChatColor.YELLOW, ChatColor.GREEN, ChatColor.AQUA, ChatColor.LIGHT_PURPLE, ChatColor.DARK_PURPLE};
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) sb.append(rb[i % rb.length]).append(s.charAt(i));
            return sb.toString();
        }
        return bedwarsStarColor(star) + "[" + star + "✫]";
    }

    /** SkyWars level colour (2020 prestige every 5 levels). */
    public static ChatColor skywarsLevelColor(int level) {
        if (level < 5) return ChatColor.GRAY;
        if (level < 10) return ChatColor.WHITE;
        if (level < 15) return ChatColor.GOLD;
        if (level < 20) return ChatColor.AQUA;
        if (level < 25) return ChatColor.DARK_GREEN;
        if (level < 30) return ChatColor.DARK_AQUA;
        if (level < 35) return ChatColor.DARK_RED;
        if (level < 40) return ChatColor.LIGHT_PURPLE;
        if (level < 45) return ChatColor.BLUE;
        if (level < 50) return ChatColor.DARK_PURPLE;
        return ChatColor.RED;
    }

    /** "§b12⋆" SkyWars level. */
    public static String skywarsLevel(int level) { return skywarsLevelColor(level) + "" + level + "⋆"; }

    /** Scoreboard rank text, e.g. "§bMVP§c+" ; NONE -> "§7Default". */
    public static String rankName(Rank rank, ChatColor plus) {
        switch (rank) {
            case NONE: return ChatColor.GRAY + "Default";
            case VIP: return ChatColor.GREEN + "VIP";
            case VIP_PLUS: return ChatColor.GREEN + "VIP" + ChatColor.GOLD + "+";
            case MVP: return ChatColor.AQUA + "MVP";
            case MVP_PLUS: return ChatColor.AQUA + "MVP" + plus + "+";
            case MVP_PLUS_PLUS: return ChatColor.GOLD + "MVP" + plus + "++";
            case YOUTUBE: return ChatColor.RED + "YOUTUBE";
            default: return rank.color + rank.name();
        }
    }

    /** Roman numerals 1..3999 (tiers, prestiges). */
    public static String roman(int n) {
        if (n <= 0) return Integer.toString(n);
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] s = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) while (n >= v[i]) { sb.append(s[i]); n -= v[i]; }
        return sb.toString();
    }

    /** Hypixel network experience needed to go from level `level` to level+1 (10,000 + 2,500 per level). */
    public static int networkExpForLevel(int level) { return 10000 + 2500 * Math.max(0, level - 1); }

    /** Bed Wars XP needed to go from `star` to star+1 (500/1000/2000/3500 for the first 4 of a prestige, then 5000). */
    public static int bedwarsExpForStar(int star) {
        switch (star % 100) {
            case 0: return 500;
            case 1: return 1000;
            case 2: return 2000;
            case 3: return 3500;
            default: return 5000;
        }
    }

    /** Strips colour codes and returns the visible length. */
    public static int visibleLength(String s) { return ChatColor.stripColor(s).length(); }
}
