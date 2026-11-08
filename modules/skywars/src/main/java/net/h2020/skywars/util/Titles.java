package net.h2020.skywars.util;

import net.minecraft.server.v1_8_R3.IChatBaseComponent;
import net.minecraft.server.v1_8_R3.Packet;
import net.minecraft.server.v1_8_R3.PacketPlayOutChat;
import net.minecraft.server.v1_8_R3.PacketPlayOutTitle;
import net.minecraft.server.v1_8_R3.PlayerConnection;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;

/** 1.8 titles / action bar via NMS packets (Bukkit 1.8.8 has no stable title API). */
public final class Titles {
    private Titles() {}

    public static void title(Player p, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        send(p, new PacketPlayOutTitle(fadeIn, stay, fadeOut));
        send(p, new PacketPlayOutTitle(PacketPlayOutTitle.EnumTitleAction.SUBTITLE, component(subtitle == null ? "" : subtitle)));
        send(p, new PacketPlayOutTitle(PacketPlayOutTitle.EnumTitleAction.TITLE, component(title == null ? "" : title)));
    }

    /** Hypixel default timings: 0.5s fade in, 2s stay, 0.5s fade out. */
    public static void title(Player p, String title, String subtitle) { title(p, title, subtitle, 10, 40, 10); }

    public static void clear(Player p) {
        send(p, new PacketPlayOutTitle(PacketPlayOutTitle.EnumTitleAction.RESET, null));
    }

    public static void actionBar(Player p, String text) {
        send(p, new PacketPlayOutChat(component(text), (byte) 2));
    }

    private static IChatBaseComponent component(String legacy) {
        return IChatBaseComponent.ChatSerializer.a("{\"text\":\"" + escape(legacy) + "\"}");
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\');
            b.append(c);
        }
        return b.toString();
    }

    private static void send(Player p, Packet<?> packet) {
        if (!(p instanceof CraftPlayer)) return;
        PlayerConnection con = ((CraftPlayer) p).getHandle().playerConnection;
        if (con != null) con.sendPacket(packet);
    }
}
