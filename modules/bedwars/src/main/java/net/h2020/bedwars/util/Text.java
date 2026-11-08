package net.h2020.bedwars.util;

import net.minecraft.server.v1_8_R3.ChatComponentText;
import net.minecraft.server.v1_8_R3.Packet;
import net.minecraft.server.v1_8_R3.PacketPlayOutChat;
import net.minecraft.server.v1_8_R3.PacketPlayOutTitle;
import org.bukkit.ChatColor;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;

/** Chat helpers: Hypixel-style centered chat lines, titles and action bars (raw NMS packets so timings are exact). */
public final class Text {
    private Text() {}

    /** The 2020 separator bar used around start/end chat blocks. */
    public static final String BAR = ChatColor.GREEN.toString() + ChatColor.BOLD
            + "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"
            + "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"
            + "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬"
            + "▬▬▬▬";

    private static final int CENTER_PX = 154;

    /** Pads a message with spaces so it renders centered in the default 320px-wide chat (default font metrics). */
    public static String center(String message) {
        if (message == null || message.isEmpty()) return "";
        int px = 0;
        boolean code = false, bold = false;
        for (char c : message.toCharArray()) {
            if (c == '§') { code = true; continue; }
            if (code) {
                code = false;
                char l = Character.toLowerCase(c);
                if (l == 'l') bold = true;
                else if (l == 'r' || (l >= '0' && l <= '9') || (l >= 'a' && l <= 'f')) bold = false;
                continue;
            }
            px += width(c) + (bold ? 2 : 1);
        }
        int half = px / 2;
        int toCompensate = CENTER_PX - half;
        int space = width(' ') + 1;
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < toCompensate; c += space) sb.append(' ');
        return sb.toString() + message;
    }

    /** Glyph widths of the vanilla default font (ascii.png), without the 1px spacing. */
    private static int width(char c) {
        switch (c) {
            case 'i': case '!': case ',': case '.': case ':': case ';': case '|': case '\'': return 1;
            case 'l': case '`': return 2;
            case 'I': case 't': case '[': case ']': case ' ': case '"': return 3;
            case 'f': case 'k': case '(': case ')': case '{': case '}': case '<': case '>': case '*': return 4;
            case '@': case '~': return 6;
            default: return c > 127 ? 7 : 5;
        }
    }

    public static void send(Player p, Packet<?> packet) {
        if (p == null || !p.isOnline()) return;
        try {
            ((CraftPlayer) p).getHandle().playerConnection.sendPacket(packet);
        } catch (Throwable ignored) {
            // connection already gone
        }
    }

    public static void title(Player p, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        send(p, new PacketPlayOutTitle(fadeIn, stay, fadeOut));
        send(p, new PacketPlayOutTitle(PacketPlayOutTitle.EnumTitleAction.SUBTITLE,
                new ChatComponentText(subtitle == null ? "" : subtitle)));
        send(p, new PacketPlayOutTitle(PacketPlayOutTitle.EnumTitleAction.TITLE,
                new ChatComponentText(title == null ? "" : title)));
    }

    public static void title(Player p, String title, String subtitle) { title(p, title, subtitle, 0, 30, 10); }

    public static void actionBar(Player p, String text) {
        send(p, new PacketPlayOutChat(new ChatComponentText(text), (byte) 2));
    }

    /** "5:59" style mm:ss. */
    public static String clock(int seconds) {
        if (seconds < 0) seconds = 0;
        int m = seconds / 60, s = seconds % 60;
        return m + ":" + (s < 10 ? "0" : "") + s;
    }

    public static String roman(int n) {
        switch (n) {
            case 1: return "I"; case 2: return "II"; case 3: return "III"; case 4: return "IV"; case 5: return "V";
            default: return String.valueOf(n);
        }
    }
}
