package net.h2020.skywars.util;

import org.bukkit.ChatColor;

/** Hypixel-style centered chat blocks (client chat is 320px wide at default scale). */
public final class Chat {
    private Chat() {}

    /** The green bold bar that frames Hypixel game start/end blocks. */
    public static final String BAR = ChatColor.GREEN + "" + ChatColor.BOLD
            + "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"
            + "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"
            + "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"
            + "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬";

    private static final int CENTER_PX = 154;

    public static String center(String msg) {
        if (msg == null || msg.isEmpty()) return "";
        int px = 0;
        boolean code = false, bold = false;
        for (int i = 0; i < msg.length(); i++) {
            char c = msg.charAt(i);
            if (c == '§') { code = true; continue; }
            if (code) {
                code = false;
                char l = Character.toLowerCase(c);
                if (l == 'l') bold = true;
                else if ((l >= '0' && l <= '9') || (l >= 'a' && l <= 'f') || l == 'r') bold = false;
                continue;
            }
            px += width(c) + (bold && c != ' ' ? 1 : 0) + 1;
        }
        int half = px / 2;
        int toCompensate = CENTER_PX - half;
        int space = width(' ') + 1;
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < toCompensate; c += space) sb.append(' ');
        return sb.toString() + msg;
    }

    /** Approximate default font glyph widths. */
    private static int width(char c) {
        switch (c) {
            case 'i': case '!': case ',': case '.': case ':': case ';': case '|': case '\'': return 1;
            case 'l': case '`': return 2;
            case 'I': case 't': case '[': case ']': case ' ': return 3;
            case 'f': case 'k': case '(': case ')': case '{': case '}': case '<': case '>': case '*': case '"': return 4;
            case '@': case '~': return 6;
            case '▬': return 4;
            default: return 5;
        }
    }

    /** m:ss */
    public static String clock(int seconds) {
        if (seconds < 0) seconds = 0;
        int m = seconds / 60, s = seconds % 60;
        return m + ":" + (s < 10 ? "0" : "") + s;
    }
}
