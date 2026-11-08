package net.h2020.api.packet;

import net.minecraft.server.v1_8_R3.Packet;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Single choke point for client->server packets of EVERY player, real or bot.
 * - Real players: core injects a Netty handler that calls dispatchInbound/dispatchOutbound.
 * - Bots: the bot's virtual client calls dispatchInbound before handing the packet to its PlayerConnection,
 *   and its fake NetworkManager calls dispatchOutbound for packets the server sends it.
 * Called on the Netty thread for real players and the main thread for bots; listeners must be thread-safe.
 */
public final class PacketHub {
    public interface Listener {
        /** @return false to drop the packet (it never reaches the server). */
        boolean onInbound(Player player, Packet<?> packet);
        default void onOutbound(Player player, Packet<?> packet) {}
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private PacketHub() {}

    public static void register(Listener l) { LISTENERS.add(l); }
    public static void unregister(Listener l) { LISTENERS.remove(l); }

    public static boolean dispatchInbound(Player player, Packet<?> packet) {
        boolean pass = true;
        for (Listener l : LISTENERS) {
            try { if (!l.onInbound(player, packet)) pass = false; } catch (Throwable ignored) {}
        }
        return pass;
    }

    public static void dispatchOutbound(Player player, Packet<?> packet) {
        for (Listener l : LISTENERS) {
            try { l.onOutbound(player, packet); } catch (Throwable ignored) {}
        }
    }
}
