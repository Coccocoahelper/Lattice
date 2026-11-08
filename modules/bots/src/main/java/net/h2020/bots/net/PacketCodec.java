package net.h2020.bots.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.server.v1_8_R3.BlockPosition;
import net.minecraft.server.v1_8_R3.EnumDirection;
import net.minecraft.server.v1_8_R3.ItemStack;
import net.minecraft.server.v1_8_R3.Packet;
import net.minecraft.server.v1_8_R3.PacketDataSerializer;
import net.minecraft.server.v1_8_R3.PacketPlayInAbilities;
import net.minecraft.server.v1_8_R3.PacketPlayInArmAnimation;
import net.minecraft.server.v1_8_R3.PacketPlayInBlockDig;
import net.minecraft.server.v1_8_R3.PacketPlayInBlockPlace;
import net.minecraft.server.v1_8_R3.PacketPlayInChat;
import net.minecraft.server.v1_8_R3.PacketPlayInClientCommand;
import net.minecraft.server.v1_8_R3.PacketPlayInCloseWindow;
import net.minecraft.server.v1_8_R3.PacketPlayInCustomPayload;
import net.minecraft.server.v1_8_R3.PacketPlayInEntityAction;
import net.minecraft.server.v1_8_R3.PacketPlayInFlying;
import net.minecraft.server.v1_8_R3.PacketPlayInHeldItemSlot;
import net.minecraft.server.v1_8_R3.PacketPlayInKeepAlive;
import net.minecraft.server.v1_8_R3.PacketPlayInSettings;
import net.minecraft.server.v1_8_R3.PacketPlayInTransaction;
import net.minecraft.server.v1_8_R3.PacketPlayInUseEntity;
import net.minecraft.server.v1_8_R3.PacketPlayInWindowClick;

import java.nio.charset.StandardCharsets;

/**
 * Builds serverbound packets exactly as the server would decode them off the wire: every packet is written into a
 * byte buffer with the 1.8 protocol layout and then decoded by the packet's own reader. This reproduces wire
 * quantisation (block-place cursor in 1/16ths, chat length limits, item stacks copied through NBT) and avoids poking
 * private fields. Also decodes clientbound packets for the virtual client. Main thread only (shared buffers).
 */
public final class PacketCodec {
    private static final ByteBuf OUT_BUF = Unpooled.buffer(256);
    private static final PacketDataSerializer OUT = new PacketDataSerializer(OUT_BUF);
    private static final ByteBuf IN_BUF = Unpooled.buffer(1024);
    private static final PacketDataSerializer IN = new PacketDataSerializer(IN_BUF);

    private PacketCodec() {}

    private static PacketDataSerializer begin() {
        OUT_BUF.clear();
        return OUT;
    }

    private static <P extends Packet<?>> P finish(P packet) {
        try {
            packet.a(OUT);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decode " + packet.getClass().getSimpleName(), e);
        } finally {
            OUT_BUF.clear();
        }
        return packet;
    }

    // ------------------------------------------------------------------ C03 - C06
    public static PacketPlayInFlying flying(boolean onGround) {
        begin().writeByte(onGround ? 1 : 0);
        return finish(new PacketPlayInFlying());
    }

    public static PacketPlayInFlying position(double x, double y, double z, boolean onGround) {
        PacketDataSerializer s = begin();
        s.writeDouble(x); s.writeDouble(y); s.writeDouble(z); s.writeByte(onGround ? 1 : 0);
        return finish(new PacketPlayInFlying.PacketPlayInPosition());
    }

    public static PacketPlayInFlying look(float yaw, float pitch, boolean onGround) {
        PacketDataSerializer s = begin();
        s.writeFloat(yaw); s.writeFloat(pitch); s.writeByte(onGround ? 1 : 0);
        return finish(new PacketPlayInFlying.PacketPlayInLook());
    }

    public static PacketPlayInFlying positionLook(double x, double y, double z, float yaw, float pitch, boolean onGround) {
        PacketDataSerializer s = begin();
        s.writeDouble(x); s.writeDouble(y); s.writeDouble(z); s.writeFloat(yaw); s.writeFloat(pitch);
        s.writeByte(onGround ? 1 : 0);
        return finish(new PacketPlayInFlying.PacketPlayInPositionLook());
    }

    // ------------------------------------------------------------------ C0B entity action
    public static PacketPlayInEntityAction entityAction(int entityId, PacketPlayInEntityAction.EnumPlayerAction action, int aux) {
        PacketDataSerializer s = begin();
        s.b(entityId); s.a(action); s.b(aux);
        return finish(new PacketPlayInEntityAction());
    }

    // ------------------------------------------------------------------ C0A swing
    public static PacketPlayInArmAnimation swing() {
        begin();
        return finish(new PacketPlayInArmAnimation());
    }

    // ------------------------------------------------------------------ C02 use entity
    public static PacketPlayInUseEntity attack(int entityId) {
        PacketDataSerializer s = begin();
        s.b(entityId); s.a(PacketPlayInUseEntity.EnumEntityUseAction.ATTACK);
        return finish(new PacketPlayInUseEntity());
    }

    public static PacketPlayInUseEntity interact(int entityId) {
        PacketDataSerializer s = begin();
        s.b(entityId); s.a(PacketPlayInUseEntity.EnumEntityUseAction.INTERACT);
        return finish(new PacketPlayInUseEntity());
    }

    public static PacketPlayInUseEntity interactAt(int entityId, float hx, float hy, float hz) {
        PacketDataSerializer s = begin();
        s.b(entityId); s.a(PacketPlayInUseEntity.EnumEntityUseAction.INTERACT_AT);
        s.writeFloat(hx); s.writeFloat(hy); s.writeFloat(hz);
        return finish(new PacketPlayInUseEntity());
    }

    // ------------------------------------------------------------------ C07 dig
    public static PacketPlayInBlockDig dig(PacketPlayInBlockDig.EnumPlayerDigType type, BlockPosition pos, EnumDirection face) {
        PacketDataSerializer s = begin();
        s.a(type); s.a(pos); s.writeByte(face.a());
        return finish(new PacketPlayInBlockDig());
    }

    // ------------------------------------------------------------------ C08 place / use
    /** Block placement against pos/face; cursor coordinates are 0..1 within the block (quantised to 1/16 like the wire). */
    public static PacketPlayInBlockPlace place(BlockPosition pos, int face, ItemStack held, float cx, float cy, float cz) {
        PacketDataSerializer s = begin();
        s.a(pos); s.writeByte(face); s.a(held);
        s.writeByte((int) (cx * 16.0F)); s.writeByte((int) (cy * 16.0F)); s.writeByte((int) (cz * 16.0F));
        return finish(new PacketPlayInBlockPlace());
    }

    /** "Use item" form: position -1,-1,-1, face 255, cursor 0. */
    public static PacketPlayInBlockPlace useItem(ItemStack held) {
        return place(USE_ITEM_POS, 255, held, 0.0F, 0.0F, 0.0F);
    }
    private static final BlockPosition USE_ITEM_POS = new BlockPosition(-1, -1, -1);

    // ------------------------------------------------------------------ C09
    public static PacketPlayInHeldItemSlot heldItem(int slot) {
        begin().writeShort(slot);
        return finish(new PacketPlayInHeldItemSlot());
    }

    // ------------------------------------------------------------------ C0E / C0D / C0F
    public static PacketPlayInWindowClick windowClick(int windowId, int slot, int button, short action, int mode, ItemStack clicked) {
        PacketDataSerializer s = begin();
        s.writeByte(windowId); s.writeShort(slot); s.writeByte(button); s.writeShort(action); s.writeByte(mode);
        s.a(clicked);
        return finish(new PacketPlayInWindowClick());
    }

    public static PacketPlayInCloseWindow closeWindow(int windowId) {
        begin().writeByte(windowId);
        return finish(new PacketPlayInCloseWindow());
    }

    public static PacketPlayInTransaction transaction(int windowId, short action, boolean accepted) {
        PacketDataSerializer s = begin();
        s.writeByte(windowId); s.writeShort(action); s.writeByte(accepted ? 1 : 0);
        return finish(new PacketPlayInTransaction());
    }

    // ------------------------------------------------------------------ C01 / C16 / C00 / C15 / C17 / C13
    public static PacketPlayInChat chat(String message) {
        if (message.length() > 100) message = message.substring(0, 100);
        begin().a(message);
        return finish(new PacketPlayInChat());
    }

    public static PacketPlayInClientCommand clientCommand(PacketPlayInClientCommand.EnumClientCommand cmd) {
        begin().a(cmd);
        return finish(new PacketPlayInClientCommand());
    }

    public static PacketPlayInKeepAlive keepAlive(int id) {
        begin().b(id);
        return finish(new PacketPlayInKeepAlive());
    }

    /** C15: locale, view distance, chat visibility (0 full), chat colours, displayed skin parts bitmask. */
    public static PacketPlayInSettings settings(String locale, int viewDistance, int chatVisibility, boolean colors, int skinParts) {
        PacketDataSerializer s = begin();
        s.a(locale); s.writeByte(viewDistance); s.writeByte(chatVisibility); s.writeBoolean(colors); s.writeByte(skinParts);
        return finish(new PacketPlayInSettings());
    }

    public static PacketPlayInCustomPayload brand(String brand) {
        PacketDataSerializer s = begin();
        s.a("MC|Brand");
        // payload is itself a protocol string (varint length + UTF-8)
        byte[] b = brand.getBytes(StandardCharsets.UTF_8);
        s.b(b.length);
        s.writeBytes(b);
        return finish(new PacketPlayInCustomPayload());
    }

    public static PacketPlayInAbilities abilities(boolean invulnerable, boolean flying, boolean canFly, boolean creative,
                                                  float flySpeed, float walkSpeed) {
        int flags = 0;
        if (invulnerable) flags |= 1;
        if (flying) flags |= 2;
        if (canFly) flags |= 4;
        if (creative) flags |= 8;
        PacketDataSerializer s = begin();
        s.writeByte(flags); s.writeFloat(flySpeed); s.writeFloat(walkSpeed);
        return finish(new PacketPlayInAbilities());
    }

    // ------------------------------------------------------------------ clientbound decoding
    /**
     * Serialises a clientbound packet with its own writer and returns a serializer positioned at its first field, so
     * the virtual client reads exactly what a real client would receive. The returned buffer is reused: read it
     * immediately. Returns null if the packet could not be written.
     */
    public static PacketDataSerializer decode(Packet<?> packet) {
        IN_BUF.clear();
        try {
            packet.b(IN);
        } catch (Exception e) {
            IN_BUF.clear();
            return null;
        }
        return IN;
    }
}
