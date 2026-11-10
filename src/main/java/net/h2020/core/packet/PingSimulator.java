package net.h2020.core.packet;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.h2020.core.Core;
import net.h2020.core.CoreUtil;
import net.h2020.core.profile.ProfileData;
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
import java.util.concurrent.ThreadLocalRandom;
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
    private static final int MAX_JITTER = 500;

    private final Core core;
    private final Logger log;
    private final Map<UUID, Integer> delays = new ConcurrentHashMap<UUID, Integer>();
    private final Map<UUID, Integer> jitters = new ConcurrentHashMap<UUID, Integer>();

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

    /** Set both the base delay and the variability, save them, and reinstall the handler. */
    public void set(Player p, int ms, int jitter) {
        int value = clamp(ms);
        int jitterValue = clampJitter(jitter);
        set(p.getUniqueId(), value, jitterValue);
        try {
            ProfileData data = core.profiles.data(p);
            data.simulatedPing = value;
            data.simulatedJitter = jitterValue;
        } catch (Throwable ignored) {}
        apply(p);
    }

    /** The per-player setting, clamped to 0..1000. */
    public void set(UUID id, int ms) { delays.put(id, clamp(ms)); }

    /** The per-player base delay plus variability, both clamped. */
    public void set(UUID id, int ms, int jitter) {
        delays.put(id, clamp(ms));
        jitters.put(id, clampJitter(jitter));
    }

    public int delay(UUID id) {
        Integer ms = delays.get(id);
        return ms == null ? 0 : ms;
    }

    public int jitter(UUID id) {
        Integer ms = jitters.get(id);
        return ms == null ? 0 : ms;
    }

    public void clear(UUID id) { delays.remove(id); jitters.remove(id); }

    /** True when the real channel currently has our handler installed. */
    public boolean installed(Player p) {
        Channel ch = CoreUtil.realChannel(p);
        if (ch == null) return false;
        try { return ch.pipeline().get(HANDLER) != null; } catch (Throwable ignored) { return false; }
    }

    public void apply(final Player p) {
        // A bot's "channel" is an embedded stub that carries no traffic, so installing here would only make the
        // probe claim a delay that never happens. Bots have their own latency model instead.
        if (CoreUtil.isBot(p)) { remove(p); return; }
        final Channel ch = CoreUtil.realChannel(p);
        if (ch == null) return; // disconnected
        final int ms = delay(p.getUniqueId());
        final int jitter = jitter(p.getUniqueId());
        ch.eventLoop().execute(new Runnable() {
            @Override public void run() {
                try {
                    if (ch.pipeline().get(HANDLER) != null) ch.pipeline().remove(HANDLER);
                    if (ms <= 0) return;
                    if (ch.pipeline().get("packet_handler") == null) return;
                    ch.pipeline().addBefore("packet_handler", HANDLER, new Handler(ms, jitter));
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
        try {
            ProfileData data = core.profiles.data(p);
            set(p.getUniqueId(), data.simulatedPing, data.simulatedJitter);
        } catch (Throwable ignored) {}
        apply(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) { remove(e.getPlayer()); }

    static int clamp(int ms) { return Math.max(0, Math.min(MAX_MS, ms)); }

    static int clampJitter(int ms) { return Math.max(0, Math.min(MAX_JITTER, ms)); }

    /**
     * One handler per installation; its delay is fixed at the current setting. Half of it is applied on each
     * direction, so a 200 ms setting adds ~100 ms inbound and ~100 ms outbound. A non-zero jitter adds a random
     * -jitter..+jitter to the base latency of every packet before that halving.
     */
    public static final class Handler extends ChannelDuplexHandler {
        private final int delayMs;
        private final int jitterMs;
        private final long halfMs;

        public Handler(int ms) { this(ms, 0); }

        public Handler(int ms, int jitter) {
            this.delayMs = clamp(ms);
            this.jitterMs = clampJitter(jitter);
            this.halfMs = delayMs / 2L;
        }

        /** Half of this packet's effective latency; no randomness or allocation when the jitter is zero. */
        private long packetDelay() {
            if (jitterMs <= 0) return halfMs;
            int effective = delayMs + ThreadLocalRandom.current().nextInt(-jitterMs, jitterMs + 1);
            return Math.max(0L, effective / 2L);
        }

        private static boolean keepAlive(Object msg) {
            return msg instanceof PacketPlayInKeepAlive || msg instanceof PacketPlayOutKeepAlive;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (keepAlive(msg)) {
                ctx.fireChannelRead(msg);
                return;
            }
            final long delay = packetDelay();
            if (delay <= 0) {
                ctx.fireChannelRead(msg);
                return;
            }
            final Object packet = msg;
            ctx.executor().schedule(new Runnable() {
                @Override public void run() { ctx.fireChannelRead(packet); }
            }, delay, TimeUnit.MILLISECONDS);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (keepAlive(msg)) {
                ctx.write(msg, promise);
                return;
            }
            final long delay = packetDelay();
            if (delay <= 0) {
                ctx.write(msg, promise);
                return;
            }
            final Object packet = msg;
            final ChannelPromise p = promise;
            ctx.executor().schedule(new Runnable() {
                @Override public void run() { ctx.write(packet, p); }
            }, delay, TimeUnit.MILLISECONDS);
        }
    }
}
