package net.h2020.api.npc;

import net.h2020.api.packet.Packets;
import net.minecraft.server.v1_8_R3.EntityArmorStand;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityDestroy;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityMetadata;
import net.minecraft.server.v1_8_R3.PacketPlayOutSpawnEntityLiving;
import net.minecraft.server.v1_8_R3.WorldServer;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Packet-only floating text: one invisible armor stand per line (top line first). Never exists server-side. */
public final class Hologram {
    /** Vertical spacing between lines. */
    public static final double SPACING = 0.28;
    /** Custom names render ~2.475 above an armor stand's feet. */
    private static final double NAME_OFFSET = 2.475;

    private final List<EntityArmorStand> stands = new ArrayList<>();
    private final List<String> lines = new ArrayList<>();
    private final Location bottom;

    /** @param bottom where the LOWEST line's text should appear */
    public Hologram(Location bottom, List<String> initial) {
        this.bottom = bottom.clone();
        WorldServer ws = ((CraftWorld) bottom.getWorld()).getHandle();
        int n = initial.size();
        for (int i = 0; i < n; i++) {
            EntityArmorStand as = new EntityArmorStand(ws);
            double y = bottom.getY() + (n - 1 - i) * SPACING - NAME_OFFSET;
            as.setLocation(bottom.getX(), y, bottom.getZ(), 0f, 0f);
            as.setInvisible(true);
            as.setGravity(false);
            as.setBasePlate(false);
            as.setCustomName(initial.get(i));
            as.setCustomNameVisible(true);
            stands.add(as);
            lines.add(initial.get(i));
        }
    }

    public Location bottom() { return bottom.clone(); }

    public List<Integer> entityIds() {
        List<Integer> ids = new ArrayList<>();
        for (EntityArmorStand as : stands) ids.add(as.getId());
        return ids;
    }

    public void show(Player p) {
        for (EntityArmorStand as : stands) Packets.send(p, new PacketPlayOutSpawnEntityLiving(as));
    }

    public void hide(Player p) {
        int[] ids = new int[stands.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = stands.get(i).getId();
        Packets.send(p, new PacketPlayOutEntityDestroy(ids));
    }

    /** Update a line's text for these viewers (only sends when it changed). */
    public void setLine(int index, String text, Collection<? extends Player> viewers) {
        if (index < 0 || index >= stands.size() || text.equals(lines.get(index))) return;
        lines.set(index, text);
        EntityArmorStand as = stands.get(index);
        as.setCustomName(text);
        PacketPlayOutEntityMetadata packet = new PacketPlayOutEntityMetadata(as.getId(), as.getDataWatcher(), true);
        for (Player p : viewers) Packets.send(p, packet);
    }
}
