package net.h2020.api.core;

import io.netty.channel.Channel;
import net.h2020.api.H2020;
import net.h2020.api.bot.BotService;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.PlayerConnection;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;

/** Identity helpers: is this a bot, and is it still on a real socket. */
public final class Players {
    private Players() {}

    public static boolean isBot(Player p) {
        if (p == null) return false;
        BotService bs = H2020.find(BotService.class);
        if (bs != null) {
            try { if (bs.isBot(p)) return true; } catch (Throwable ignored) { }
        }
        return realChannel(p) == null;
    }

    /** The socket behind a player, or null for a bot whose connection is virtual. */
    public static Channel realChannel(Player p) {
        if (!(p instanceof CraftPlayer)) return null;
        EntityPlayer ep = ((CraftPlayer) p).getHandle();
        PlayerConnection pc = ep.playerConnection;
        if (pc == null || pc.networkManager == null) return null;
        Channel ch = pc.networkManager.channel;
        if (ch == null || ch.pipeline() == null) return null;
        return ch;
    }
}
