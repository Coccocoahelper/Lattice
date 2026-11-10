package net.h2020.core.packet;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.h2020.core.Core;
import net.h2020.core.CoreUtil;
import net.minecraft.server.v1_8_R3.PacketPlayInKeepAlive;
import net.minecraft.server.v1_8_R3.PacketPlayOutKeepAlive;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Netty injection for real clients: a duplex handler placed right before "packet_handler" that holds every
 * serverbound and clientbound packet for half the configured latency each way, so the round trip matches the
 * setting. Bots have their own latency model and are never touched here (CoreUtil.realChannel returns null).
 */
public final class PingSimulator implements Listener {
    public static final String HANDLER = "h2020_ping";
    private static final int MAX_MS = 1000;

    private final Core core;
    private final Logger log;
    private final Map<UUID, Integer> delays = new ConcurrentHashMap<UUID, Integer>();

    public PingSimulator(Core core, Logger log) {
        this.core = core;
        this.log = log;
    }

    /** Set the delay, save it on the profile, and reinstall the channel handler immediately. */
    public void set(Player p, int ms) {
        int value = clamp(ms);
        set(p.getUniqueId(), value);
        try { core.profiles.data(p).simulatedPing = value; } catch (Throwable ignored) {}
        apply(p);
    }

    /** The per-player setting, clamped to 0..1000. */
    public void set(UUID id, int ms) { delays.put(id, clamp(ms)); }

    public int delay(UUID id) {
        Integer ms = delays.get(id);
        return ms == null ? 0 : ms;
    }

    public void clear(UUID id) { delays.remove(id); }

    /** True when the real channel currently has our handler installed. */
    public boolean installed(Player p) {
        Channel ch = CoreUtil.realChannel(p);
        if (ch == null) return false;
        try { return ch.pipeline().get(HANDLER) != null; } catch (Throwable ignored) { return false; }
    }

    public void apply(final Player p) {
        final Channel ch = CoreUtil.realChannel(p);
        if (ch == null) return; // bot or already disconnected
        final int ms = delay(p.getUniqueId());
        ch.eventLoop().execute(new Runnable() {
            @Override public void run() {
                try {
                    if (ch.pipeline().get(HANDLER) != null) ch.pipeline().remove(HANDLER);
                    if (ms <= 0) return;
                    if (ch.pipeline().get("packet_handler") == null) return;
                    ch.pipeline().addBefore("packet_handler", HANDLER, new Handler(ms));
                } catch (Throwable t) {
                    log.warning("Ping simulation failed for " + p.getName() + ": " + t);
                }
            }
        });
    }

    public void remove(Player p) {
        final Channel ch = CoreUtil.realChannel(p);
        if (ch == null) return;
        ch.eventLoop().execute(new Runnable() {
            @Override public void run() {
                try { if (ch.pipeline().get(HANDLER) != null) ch.pipeline().remove(HANDLER); } catch (Throwable ignored) {}
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        try { set(p.getUniqueId(), core.profiles.data(p).simulatedPing); } catch (Throwable ignored) {}
        apply(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) { remove(e.getPlayer()); }

    static int clamp(int ms) { return Math.max(0, Math.min(MAX_MS, ms)); }

    /**
     * One handler per installation; its delay is fixed at the current setting. Half of it is applied on each
     * direction, so a 200 ms setting adds ~100 ms inbound and ~100 ms outbound. Scheduling runs on the channel's
     * own event loop, which keeps packet order intact.
     */
    public static final class Handler extends ChannelDuplexHandler {
        private final int delayMs;
        private final long halfMs;

        public Handler(int ms) {
            this.delayMs = clamp(ms);
            this.halfMs = delayMs / 2L;
        }

        private static boolean keepAlive(Object msg) {
            return msg instanceof PacketPlayInKeepAlive || msg instanceof PacketPlayOutKeepAlive;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (delayMs <= 0 || halfMs <= 0 || keepAlive(msg)) {
                ctx.fireChannelRead(msg);
                return;
            }
            final Object packet = msg;
            ctx.executor().schedule(new Runnable() {
                @Override public void run() { ctx.fireChannelRead(packet); }
            }, halfMs, TimeUnit.MILLISECONDS);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (delayMs <= 0 || halfMs <= 0 || keepAlive(msg)) {
                ctx.write(msg, promise);
                return;
            }
            final Object packet = msg;
            final ChannelPromise p = promise;
            ctx.executor().schedule(new Runnable() {
                @Override public void run() { ctx.write(packet, p); }
            }, halfMs, TimeUnit.MILLISECONDS);
        }
    }
}
