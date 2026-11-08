package net.h2020.bots.net;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import net.minecraft.server.v1_8_R3.EnumProtocol;
import net.minecraft.server.v1_8_R3.EnumProtocolDirection;
import net.minecraft.server.v1_8_R3.IChatBaseComponent;
import net.minecraft.server.v1_8_R3.NetworkManager;
import net.minecraft.server.v1_8_R3.Packet;

import javax.crypto.SecretKey;
import java.net.SocketAddress;

/**
 * The bot's "socket". A real NetworkManager would encode packets onto a Netty channel; this one hands every packet the
 * server sends to the bot's virtual client instead (which applies simulated latency and reacts like a 1.8.9 client).
 *
 * The {@link #channel} field holds a harmless {@link BotChannel} (an EmbeddedChannel with the usual vanilla handler
 * names) so plugins that poke at {@code networkManager.channel} (packet injectors, address lookups) never NPE.
 * Nothing is ever written to or read from that channel by the bot itself.
 */
public final class FakeNetworkManager extends NetworkManager {
    /** Receives every packet the server sends to this bot. May be called from any thread (async chat, etc.). */
    public interface Sink { void onServerPacket(Packet<?> packet); }

    private final Sink sink;
    private volatile boolean open = true;
    private volatile IChatBaseComponent closeReason;

    public FakeNetworkManager(Sink sink, SocketAddress address) {
        super(EnumProtocolDirection.SERVERBOUND);
        this.sink = sink;
        this.l = address;
        this.preparing = false;
        this.channel = BotChannel.create(this);
    }

    /** Called by BotChannel when something writes a packet straight onto the channel. */
    void captureFromChannel(Object msg) {
        if (msg instanceof Packet) capture((Packet<?>) msg);
    }

    private void capture(Packet<?> packet) {
        if (open && packet != null) sink.onServerPacket(packet);
    }

    public boolean isOpen() { return open; }
    public IChatBaseComponent closeReason() { return closeReason; }

    // ---- outbound: PlayerConnection.sendPacket -> handle(packet) ----
    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void handle(Packet packet) { capture(packet); }

    @Override
    @SafeVarargs
    public final void a(Packet packet, GenericFutureListener<? extends Future<? super Void>> listener,
                        GenericFutureListener<? extends Future<? super Void>>... listeners) {
        capture(packet);
    }

    // ---- lifecycle ----
    @Override
    public void close(IChatBaseComponent reason) {
        if (!open) return;
        open = false;
        closeReason = reason;
        preparing = false;
        try { if (channel != null) channel.close(); } catch (Throwable ignored) {}
    }

    /** isConnected */
    @Override public boolean g() { return open; }
    /** channel == null ("is local / no channel"); we always have our embedded channel. */
    @Override public boolean h() { return false; }
    /** isLocalChannel */
    @Override public boolean c() { return false; }
    /** disableAutoRead */
    @Override public void k() {}
    /** checkDisconnected - the PlayerConnection already handled the disconnect itself. */
    @Override public void l() {}
    /** network tick: the bot module drives PlayerConnection.c() itself. */
    @Override public void a() {}
    @Override public void a(EnumProtocol protocol) {}
    /** setCompressionTreshold */
    @Override public void a(int threshold) {}
    /** enableEncryption */
    @Override public void a(SecretKey key) {}

    @Override public SocketAddress getSocketAddress() { return l; }
    @Override public SocketAddress getRawAddress() { return l; }
}
