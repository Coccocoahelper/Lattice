package net.h2020.api.packet;

import net.minecraft.server.v1_8_R3.Packet;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import net.minecraft.server.v1_8_R3.PlayerConnection;
import org.bukkit.entity.Player;

/** Small packet helper shared by the API plugin (NPCs, holograms) and anything else that needs a raw send. */
public final class Packets {
    private Packets() {}

    public static void send(Player p, Packet<?> packet) {
        if (!(p instanceof CraftPlayer)) return;
        PlayerConnection pc = ((CraftPlayer) p).getHandle().playerConnection;
        if (pc != null) pc.sendPacket(packet);
    }
}
