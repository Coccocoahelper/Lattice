package net.h2020.api.npc;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.h2020.api.packet.Packets;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.MinecraftServer;
import net.minecraft.server.v1_8_R3.PacketPlayOutAnimation;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityDestroy;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityEquipment;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityHeadRotation;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityMetadata;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityTeleport;
import net.minecraft.server.v1_8_R3.PacketPlayOutNamedEntitySpawn;
import net.minecraft.server.v1_8_R3.PacketPlayOutPlayerInfo;
import net.minecraft.server.v1_8_R3.PlayerInteractManager;
import net.minecraft.server.v1_8_R3.WorldServer;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_8_R3.CraftServer;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Packet-only player NPC (no AI, never added to the world). Shown with the 1.8 sequence: tab ADD (skin), spawn,
 * head rotation, then tab REMOVE a moment later so it never appears in the tab list.
 */
public final class PacketNpc {
    private final EntityPlayer entity;
    private final String profileName;
    private final Location location;

    public PacketNpc(String profileName, Location loc, String skinValue, String skinSignature) {
        this.profileName = profileName.length() > 16 ? profileName.substring(0, 16) : profileName;
        this.location = loc.clone();
        MinecraftServer server = ((CraftServer) org.bukkit.Bukkit.getServer()).getServer();
        WorldServer ws = ((CraftWorld) loc.getWorld()).getHandle();
        UUID id = new UUID(UUID.randomUUID().getMostSignificantBits() & ~0xF000L | 0x2000L, UUID.randomUUID().getLeastSignificantBits());
        GameProfile gp = new GameProfile(id, this.profileName);
        if (skinValue != null && !skinValue.isEmpty()) gp.getProperties().put("textures", new Property("textures", skinValue, skinSignature));
        entity = new EntityPlayer(server, ws, gp, new PlayerInteractManager(ws));
        entity.setLocation(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        entity.getDataWatcher().watch(10, (byte) 0x7F); // all skin layers
    }

    public int entityId() { return entity.getId(); }
    public String profileName() { return profileName; }
    public Location location() { return location.clone(); }

    public void show(Player p) {
        Packets.send(p, new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.ADD_PLAYER, entity));
        Packets.send(p, new PacketPlayOutNamedEntitySpawn(entity));
        Packets.send(p, new PacketPlayOutEntityHeadRotation(entity, (byte) (location.getYaw() * 256f / 360f)));
    }

    /** One equipment slot (0 held, 1 boots, 2 leggings, 3 chestplate, 4 helmet) with a packed item id. */
    public void setEquipment(Player p, int slot, int packed) {
        net.minecraft.server.v1_8_R3.ItemStack stack = null;
        if (packed >= 1_000_000) {
            // dyed leather: the colour is in the meta, not the durability
            int[] ids = {0, 301, 300, 299, 298};                     // slot 1 boots ... slot 4 helmet
            int id = slot >= 1 && slot <= 4 ? ids[slot] : 298;
            org.bukkit.inventory.ItemStack b = new org.bukkit.inventory.ItemStack(id, 1);
            org.bukkit.inventory.meta.ItemMeta meta = b.getItemMeta();
            if (meta instanceof org.bukkit.inventory.meta.LeatherArmorMeta) {
                int rgb = packed - 1_000_000;
                ((org.bukkit.inventory.meta.LeatherArmorMeta) meta).setColor(org.bukkit.Color.fromRGB(rgb));
                b.setItemMeta(meta);
            }
            stack = org.bukkit.craftbukkit.v1_8_R3.inventory.CraftItemStack.asNMSCopy(b);
        } else if (packed > 0) {
            int id = packed / 100, data = packed % 100;
            org.bukkit.inventory.ItemStack b = new org.bukkit.inventory.ItemStack(id, 1, (short) data);
            stack = org.bukkit.craftbukkit.v1_8_R3.inventory.CraftItemStack.asNMSCopy(b);
        }
        Packets.send(p, new PacketPlayOutEntityEquipment(entity.getId(), slot, stack));
    }

    /** The floating name above the puppet (team and health in a replay). */
    public void setLabel(Player p, String label) {
        entity.setCustomName(label);
        entity.setCustomNameVisible(label != null && !label.isEmpty());
        Packets.send(p, new PacketPlayOutEntityMetadata(entity.getId(), entity.getDataWatcher(), true));
    }

    /** Invisibility is a potion effect packet for a fake player, not a metadata flag. */
    public void setInvisible(Player p, boolean invisible) {
        if (invisible) {
            Packets.send(p, new net.minecraft.server.v1_8_R3.PacketPlayOutEntityEffect(entity.getId(),
                    new net.minecraft.server.v1_8_R3.MobEffect(
                            net.minecraft.server.v1_8_R3.MobEffectList.INVISIBILITY.id, 100000, 0)));
        } else {
            // the remove-effect packet takes the numeric effect id in 1.8
            Packets.send(p, new net.minecraft.server.v1_8_R3.PacketPlayOutRemoveEntityEffect(entity.getId(),
                    new net.minecraft.server.v1_8_R3.MobEffect(
                            net.minecraft.server.v1_8_R3.MobEffectList.INVISIBILITY.id, 0, 0)));
        }
    }

    /** 0 swing, 1 hurt, 3 eat: the animation packets a replay needs to look alive. */
    public void animate(Player p, int type) {
        Packets.send(p, new PacketPlayOutAnimation(entity, type));
    }

    /** Crouch or stand: the sneak flag on the entity's metadata. */
    public void setSneaking(Player p, boolean sneaking) {
        entity.setSneaking(sneaking);
        Packets.send(p, new PacketPlayOutEntityMetadata(entity.getId(), entity.getDataWatcher(), true));
    }

    /** Move the puppet: a teleport plus the head rotation, what a replay needs ten times a second. */
    public void moveTo(Player p, double x, double y, double z, float yaw, float pitch) {
        entity.setLocation(x, y, z, yaw, pitch);
        Packets.send(p, new PacketPlayOutEntityTeleport(entity));
        Packets.send(p, new PacketPlayOutEntityHeadRotation(entity, (byte) (yaw * 256f / 360f)));
    }

    /** Vanish from a client (a player who died in the recording). */
    public void destroy(Player p) {
        Packets.send(p, new PacketPlayOutEntityDestroy(new int[]{entity.getId()}));
    }

    /** Second half of show(): drop the tab entry once the client has the skin. */
    public void removeFromTab(Player p) {
        Packets.send(p, new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.EnumPlayerInfoAction.REMOVE_PLAYER, entity));
    }

    public void hide(Player p) {
        Packets.send(p, new PacketPlayOutEntityDestroy(entity.getId()));
        removeFromTab(p);
    }
}
