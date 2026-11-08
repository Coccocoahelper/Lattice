package net.h2020.bedwars.world;

import net.h2020.bedwars.util.Text;
import net.minecraft.server.v1_8_R3.EntityInsentient;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntity;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityHeadRotation;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftEntity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Shopkeeper villager: NoAI, silent, invulnerable, with two hologram lines above it. Like on Hypixel, every player
 * sees the NPC turn its head towards them (per-viewer rotation packets).
 */
public final class ShopNpc {
    public enum Kind { ITEM_SHOP, UPGRADES }

    private static Field invulnerableField;

    private final Kind kind;
    private final Location at;
    private final Villager villager;
    private final List<Hologram> lines = new ArrayList<>();

    public ShopNpc(Kind kind, Location at, String topLine) {
        this.kind = kind;
        this.at = at.clone();
        villager = (Villager) at.getWorld().spawnEntity(at, EntityType.VILLAGER);
        villager.setProfession(kind == Kind.ITEM_SHOP ? Villager.Profession.FARMER : Villager.Profession.LIBRARIAN);
        villager.setAdult();
        villager.setRemoveWhenFarAway(false);
        villager.setCanPickupItems(false);
        try {
            EntityInsentient nms = (EntityInsentient) ((CraftEntity) villager).getHandle();
            nms.k(true);            // NoAI
            nms.b(true);            // silent
            if (invulnerableField == null) {
                invulnerableField = net.minecraft.server.v1_8_R3.Entity.class.getDeclaredField("invulnerable");
                invulnerableField.setAccessible(true);
            }
            invulnerableField.setBoolean(nms, true);
        } catch (Throwable ignored) {
            // damage is cancelled by the listener anyway
        }
        lines.add(new Hologram(at.clone().add(0, 2.55, 0), topLine));
        lines.add(new Hologram(at.clone().add(0, 2.25, 0), "§e§lRIGHT CLICK"));
    }

    public Kind kind() { return kind; }
    public Villager entity() { return villager; }
    public Location location() { return at.clone(); }

    /** Keep it in place (players can't push NoAI mobs in 1.8, but knockback from explosions can). */
    public void anchor() {
        if (!villager.isValid()) return;
        Location l = villager.getLocation();
        if (l.distanceSquared(at) > 0.01) villager.teleport(at);
    }

    /** Per-viewer head tracking within 10 blocks; others see the default facing. */
    public void lookAt(List<Player> viewers) {
        if (!villager.isValid()) return;
        net.minecraft.server.v1_8_R3.Entity nms = ((CraftEntity) villager).getHandle();
        Location eye = at.clone().add(0, 1.62, 0);
        for (Player p : viewers) {
            if (p.getWorld() != at.getWorld()) continue;
            Location target = p.getEyeLocation();
            double dist = target.distanceSquared(eye);
            float yaw, pitch;
            if (dist > 100) {
                yaw = at.getYaw();
                pitch = 0;
            } else {
                double dx = target.getX() - eye.getX(), dy = target.getY() - eye.getY(), dz = target.getZ() - eye.getZ();
                yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
                pitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
            }
            byte y = (byte) (int) (yaw * 256.0F / 360.0F), pt = (byte) (int) (pitch * 256.0F / 360.0F);
            Text.send(p, new PacketPlayOutEntityHeadRotation(nms, y));
            Text.send(p, new PacketPlayOutEntity.PacketPlayOutEntityLook(villager.getEntityId(), y, pt, true));
        }
    }

    public void remove() {
        villager.remove();
        for (Hologram h : lines) h.remove();
    }
}
