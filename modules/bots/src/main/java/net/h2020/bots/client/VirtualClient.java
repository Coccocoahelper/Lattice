package net.h2020.bots.client;

import net.h2020.api.bot.AimIntent;
import net.h2020.api.bot.Bot;
import net.h2020.api.bot.BotController;
import net.h2020.api.bot.BotProfile;
import net.h2020.api.bot.CheatProfile;
import net.h2020.api.packet.PacketHub;
import net.h2020.api.stats.CombatStats;
import net.h2020.bots.input.ClickScheduler;
import net.h2020.bots.input.HumanMouse;
import net.h2020.bots.net.FakeNetworkManager;
import net.h2020.bots.net.LatencyQueue;
import net.h2020.bots.net.PacketCodec;
import net.minecraft.server.v1_8_R3.AxisAlignedBB;
import net.minecraft.server.v1_8_R3.Block;
import net.minecraft.server.v1_8_R3.BlockPosition;
import net.minecraft.server.v1_8_R3.Container;
import net.minecraft.server.v1_8_R3.EnchantmentManager;
import net.minecraft.server.v1_8_R3.Entity;
import net.minecraft.server.v1_8_R3.EntityArmorStand;
import net.minecraft.server.v1_8_R3.EntityHorse;
import net.minecraft.server.v1_8_R3.EntityItemFrame;
import net.minecraft.server.v1_8_R3.EntityMinecartAbstract;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.EntityVillager;
import net.minecraft.server.v1_8_R3.EnumAnimation;
import net.minecraft.server.v1_8_R3.EnumDirection;
import net.minecraft.server.v1_8_R3.IBlockData;
import net.minecraft.server.v1_8_R3.Item;
import net.minecraft.server.v1_8_R3.ItemBlock;
import net.minecraft.server.v1_8_R3.ItemStack;
import net.minecraft.server.v1_8_R3.Material;
import net.minecraft.server.v1_8_R3.MovingObjectPosition;
import net.minecraft.server.v1_8_R3.Packet;
import net.minecraft.server.v1_8_R3.PacketDataSerializer;
import net.minecraft.server.v1_8_R3.PacketListenerPlayIn;
import net.minecraft.server.v1_8_R3.PacketPlayInBlockDig;
import net.minecraft.server.v1_8_R3.PacketPlayInClientCommand;
import net.minecraft.server.v1_8_R3.PacketPlayInEntityAction;
import net.minecraft.server.v1_8_R3.PacketPlayOutAbilities;
import net.minecraft.server.v1_8_R3.PacketPlayOutCloseWindow;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityEffect;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityStatus;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityVelocity;
import net.minecraft.server.v1_8_R3.PacketPlayOutExplosion;
import net.minecraft.server.v1_8_R3.PacketPlayOutGameStateChange;
import net.minecraft.server.v1_8_R3.PacketPlayOutHeldItemSlot;
import net.minecraft.server.v1_8_R3.PacketPlayOutKeepAlive;
import net.minecraft.server.v1_8_R3.PacketPlayOutLogin;
import net.minecraft.server.v1_8_R3.PacketPlayOutOpenWindow;
import net.minecraft.server.v1_8_R3.PacketPlayOutPosition;
import net.minecraft.server.v1_8_R3.PacketPlayOutRemoveEntityEffect;
import net.minecraft.server.v1_8_R3.PacketPlayOutRespawn;
import net.minecraft.server.v1_8_R3.PacketPlayOutTransaction;
import net.minecraft.server.v1_8_R3.PacketPlayOutUpdateHealth;
import net.minecraft.server.v1_8_R3.PacketPlayOutWorldParticles;
import net.minecraft.server.v1_8_R3.PlayerConnection;
import net.minecraft.server.v1_8_R3.Vec3D;
import net.minecraft.server.v1_8_R3.World;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.lang.reflect.Field;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * The bot's game client. Mirrors the parts of Minecraft.runTick / EntityPlayerSP / PlayerControllerMP /
 * NetHandlerPlayClient that matter for what the server sees:
 *  - receives clientbound packets after half the simulated ping, reacts like vanilla (teleport confirm, velocity,
 *    explosions, window state, health/respawn, abilities, effects, keep-alive, transactions);
 *  - each tick: mouse frames (human aim) -> objectMouseOver -> clicks (attack / dig / place / use) -> movement
 *    physics -> flying packets (C0B sprint/sneak changes, C03/C04/C05/C06), queued to the server after half the ping;
 *  - implements the BotController "keyboard and mouse".
 * Cheat profiles alter what this client does (see CheatEngine); nothing is ever faked on the server side.
 */
public final class VirtualClient implements BotController, FakeNetworkManager.Sink {
    private static final BlockPosition ORIGIN = new BlockPosition(0, 0, 0);

    final Bot bot;
    final EntityPlayer ep;
    final Player player;
    final Random rnd;
    final int entityId;

    public final ClientPhysics phys;
    public final HumanMouse mouse;
    final ClickScheduler clicks;
    final MouseOver over = new MouseOver();
    public final CheatEngine cheats;
    private volatile CheatProfile cheat;

    // ---- network ----
    private final ConcurrentLinkedQueue<Timed> captured = new ConcurrentLinkedQueue<Timed>();
    private final LatencyQueue<Packet<?>> toClient = new LatencyQueue<Packet<?>>();
    private final LatencyQueue<Packet<?>> toServer = new LatencyQueue<Packet<?>>();
    private final int pingMs;
    private volatile boolean connectionClosed; // read by the keep-alive timer thread

    // ---- keys (what the bot's fingers hold) ----
    private float keyForward, keyStrafe;
    private boolean keyJump, keySneak, keySprint;
    /** Set by the brain each tick when this bot has a reason to move; used only by the movement counters. */
    public boolean clientTickHasPurpose;

    @Override public void setTickPurpose(boolean purpose) { clientTickHasPurpose = purpose; }
    private boolean rightHeld;
    private int pendingRightClicks;
    private BlockPosition forcedUseBlock;

    // ---- movement input after MovementInputFromOptions ----
    float inForward, inStrafe;
    boolean inJump, inSneak;

    // ---- client state ----
    private boolean loggedIn, doneLoadingTerrain;
    float health = 20.0F;
    int food = 20;
    boolean dead;
    private int deathTicks;
    private double respawnAt = -1.0D;
    int gamemode;
    private boolean abilitiesFlying, allowFlying, creativeAbility;
    private boolean blind;
    private int hasteAmp = -1, fatigueAmp = -1;

    int currentItem;
    private int syncedItem;

    // ---- windows ----
    private int windowId = -1;          // open server container (>0), -1 none
    private boolean inventoryOpen;      // own inventory GUI
    private short windowAction, inventoryAction;
    private double lastWindowActivity;
    /** Idle time after which an inventory screen opened only for clicks is closed again (press E, swap, press E). */
    private double inventoryIdleCloseMs = 700.0D;

    // ---- PlayerControllerMP digging ----
    private boolean isHittingBlock;
    private BlockPosition currentBlock = new BlockPosition(-1, -1, -1);
    private EnumDirection currentFace = EnumDirection.DOWN;
    private Item currentItemHittingBlock;
    private float curBlockDamageMP;
    private int blockHitDelay;
    int leftClickCounter;

    // ---- item use ----
    boolean usingItem;
    private int itemInUseCount;
    private int itemInUseSlot;
    private Item itemInUse;
    int rightClickDelayTimer;

    /** -Dh2020.botdebug=<name> logs that bot's per-tick movement input and sent deltas. */
    private static final String DEBUG_NAME = System.getProperty("h2020.botdebug");

    // ---- EntityPlayerSP.onUpdateWalkingPlayer ----
    private boolean serverSprintState, serverSneakState;
    private double lastReportedPosX, lastReportedPosY, lastReportedPosZ;
    private float lastReportedYaw, lastReportedPitch;
    private int positionUpdateTicks;

    // ---- per-tick ----
    private boolean tookKnockback;
    /** Every arm swing this client has sent (the denominator of the aim model's hit rate). */
    private int swings;
    boolean rotOverride;
    float rotOverrideYaw, rotOverridePitch;
    double clientTime;                  // ms of client time (50 per tick)
    double combatUntil;

    public VirtualClient(Bot bot, EntityPlayer ep, BotProfile profile, Random rnd) {
        this.bot = bot;
        this.ep = ep;
        this.player = ep.getBukkitEntity();
        this.rnd = rnd;
        this.entityId = ep.getId();
        this.pingMs = profile.pingMs;
        this.cheat = profile.cheat == null ? CheatProfile.NONE : profile.cheat;
        this.phys = new ClientPhysics(ep);
        this.phys.setPosition(ep.locX, ep.locY, ep.locZ);
        this.mouse = new HumanMouse(rnd, profile.skill, ep.yaw, ep.pitch);
        this.clicks = new ClickScheduler(rnd, profile.skill.overall);
        this.cheats = new CheatEngine(this, rnd);
        applyCheat();
    }

    public CheatProfile cheat() { return cheat; }

    public void setCheat(CheatProfile c) {
        this.cheat = c == null ? CheatProfile.NONE : c;
        applyCheat();
    }

    private void applyCheat() {
        clicks.setAutoClicker(cheat == CheatProfile.AUTOCLICKER);
        phys.speedHack = cheat == CheatProfile.SPEED;
        cheats.onProfileChanged(cheat);
    }

    // =================================================================================== network: server -> client
    private static final class Timed {
        final Packet<?> packet;
        final long nanos;
        Timed(Packet<?> packet, long nanos) { this.packet = packet; this.nanos = nanos; }
    }

    private static final Field VELOCITY_ID = field(PacketPlayOutEntityVelocity.class, "a");
    private static final Field EFFECT_ID = field(PacketPlayOutEntityEffect.class, "a");
    private static final Field REMOVE_EFFECT_ID = field(PacketPlayOutRemoveEntityEffect.class, "a");
    private static final Field STATUS_ID = field(PacketPlayOutEntityStatus.class, "a");

    private static Field field(Class<?> c, String name) {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isOurs(Field f, Packet<?> p) {
        if (f == null) return true;
        try { return f.getInt(p) == entityId; } catch (Exception e) { return true; }
    }

    private static final Field KEEPALIVE_ID = field(PacketPlayOutKeepAlive.class, "a");

    // world particle packets: position + count (1.8.9: a=type, b/c/d = x/y/z, e/f/g = spread, h = speed, i = count)
    private static final Field PARTICLE_X = field(PacketPlayOutWorldParticles.class, "b");
    private static final Field PARTICLE_Y = field(PacketPlayOutWorldParticles.class, "c");
    private static final Field PARTICLE_Z = field(PacketPlayOutWorldParticles.class, "d");
    private static final Field PARTICLE_COUNT = field(PacketPlayOutWorldParticles.class, "i");

    /** Keep-alive replies are answered off-thread at the exact simulated round trip (vanilla handles C00 on the
     *  network thread), so the server-measured ping matches the profile instead of being rounded up to ticks. */
    private static ScheduledExecutorService keepAliveExec;

    private static synchronized ScheduledExecutorService keepAliveExec() {
        if (keepAliveExec == null || keepAliveExec.isShutdown()) {
            keepAliveExec = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "H2020 bot keep-alive");
                    t.setDaemon(true);
                    return t;
                }
            });
        }
        return keepAliveExec;
    }

    private void scheduleKeepAlive(Packet<?> p) {
        if (KEEPALIVE_ID == null) { captured.add(new Timed(p, System.nanoTime())); return; }
        final int id;
        try { id = KEEPALIVE_ID.getInt(p); } catch (Exception e) { captured.add(new Timed(p, System.nanoTime())); return; }
        long delayMs = Math.max(1L, Math.round(pingMs * (1.0D + rnd.nextGaussian() * 0.05D)));
        keepAliveExec().schedule(new Runnable() {
            @Override public void run() {
                if (connectionClosed) return;
                PlayerConnection pc = ep.playerConnection;
                if (pc == null) return;
                net.minecraft.server.v1_8_R3.PacketPlayInKeepAlive reply = new net.minecraft.server.v1_8_R3.PacketPlayInKeepAlive();
                setKeepAliveId(reply, id);
                if (!PacketHub.dispatchInbound(player, reply)) return;
                try { pc.a(reply); } catch (Throwable ignored) {}
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private static final Field KEEPALIVE_IN_ID = field(net.minecraft.server.v1_8_R3.PacketPlayInKeepAlive.class, "a");

    private static void setKeepAliveId(net.minecraft.server.v1_8_R3.PacketPlayInKeepAlive p, int id) {
        try { KEEPALIVE_IN_ID.setInt(p, id); } catch (Exception ignored) {}
    }

    public static synchronized void shutdownExecutors() {
        if (keepAliveExec != null) keepAliveExec.shutdownNow();
        keepAliveExec = null;
    }

    /** FakeNetworkManager sink: any thread. */
    @Override
    public void onServerPacket(Packet<?> p) {
        PacketHub.dispatchOutbound(player, p);
        Class<?> c = p.getClass();
        if (c == PacketPlayOutWorldParticles.class) { captureParticles(p); return; }   // nothing to keep, just to see
        if (c == PacketPlayOutKeepAlive.class && KEEPALIVE_IN_ID != null) { scheduleKeepAlive(p); return; }
        boolean keep;
        if (c == PacketPlayOutEntityVelocity.class) keep = isOurs(VELOCITY_ID, p);
        else if (c == PacketPlayOutEntityEffect.class) keep = isOurs(EFFECT_ID, p);
        else if (c == PacketPlayOutRemoveEntityEffect.class) keep = isOurs(REMOVE_EFFECT_ID, p);
        else if (c == PacketPlayOutEntityStatus.class) keep = isOurs(STATUS_ID, p);
        else keep = c == PacketPlayOutPosition.class || c == PacketPlayOutKeepAlive.class || c == PacketPlayOutUpdateHealth.class
                || c == PacketPlayOutExplosion.class || c == PacketPlayOutOpenWindow.class || c == PacketPlayOutCloseWindow.class
                || c == PacketPlayOutRespawn.class || c == PacketPlayOutLogin.class || c == PacketPlayOutAbilities.class
                || c == PacketPlayOutGameStateChange.class || c == PacketPlayOutHeldItemSlot.class
                || c == PacketPlayOutTransaction.class;
        if (keep) captured.add(new Timed(p, System.nanoTime()));
    }

    /**
     * World particles are part of what a client sees; the AI only uses the ones that give an invisible player away
     * (potion particles around a body), matched against a suspect it already remembers - see ParticleBus.
     */
    private void captureParticles(Packet<?> p) {
        if (PARTICLE_X == null || PARTICLE_COUNT == null) return;
        try {
            float x = PARTICLE_X.getFloat(p), y = PARTICLE_Y.getFloat(p), z = PARTICLE_Z.getFloat(p);
            int n = PARTICLE_COUNT.getInt(p);
            if (n > 0) net.h2020.bots.ai.perception.ParticleBus.emit(player.getWorld(), x, y, z, n);
        } catch (Exception ignored) {
        }
    }

    private long oneWayDownNanos() {
        // the client only sees packets at its next tick start, so part of the latency is already "spent"
        double ms = Math.max(0.0D, pingMs * 0.5D - 30.0D + rnd.nextGaussian() * pingMs * 0.03D);
        return (long) (ms * 1.0E6D);
    }

    private long oneWayUpNanos() {
        double ms = Math.max(0.0D, pingMs * 0.5D + rnd.nextGaussian() * pingMs * 0.03D);
        return (long) (ms * 1.0E6D);
    }

    /** Queue a serverbound packet (delivered after half the ping). */
    void send(Packet<?> p) {
        if (connectionClosed) return;
        toServer.add(p, System.nanoTime() + oneWayUpNanos());
    }

    /** Deliver due serverbound packets into the real PlayerConnection, like Netty would. Main thread. */
    @SuppressWarnings("unchecked")
    public void flushToServer() {
        long now = System.nanoTime();
        Packet<?> p;
        while ((p = toServer.pollDue(now)) != null) {
            PlayerConnection pc = ep.playerConnection;
            if (pc == null || connectionClosed) { toServer.clear(); return; }
            if (!PacketHub.dispatchInbound(player, p)) continue;
            // The "Taking Inventory" achievement is purely cosmetic; bots start with empty stats every session, so
            // letting the server award it would broadcast an achievement line on every bot's first E press.
            if (p instanceof PacketPlayInClientCommand && ((PacketPlayInClientCommand) p).a()
                    == PacketPlayInClientCommand.EnumClientCommand.OPEN_INVENTORY_ACHIEVEMENT) continue;
            try {
                ((Packet<PacketListenerPlayIn>) p).a(pc);
            } catch (Throwable t) {
                // CancelledPacketHandleException etc. - a real connection would just drop it
            }
        }
    }

    public void closeConnection() {
        connectionClosed = true;
        toServer.clear();
        toClient.clear();
        captured.clear();
    }

    /** Process clientbound packets whose latency has elapsed. Called at the start of each tick. */
    public void receive() {
        tookKnockback = false;
        long down = oneWayDownNanos();
        Timed t;
        while ((t = captured.poll()) != null) toClient.add(t.packet, t.nanos + down);
        long now = System.nanoTime();
        Packet<?> p;
        while ((p = toClient.pollDue(now)) != null) {
            try { handle(p); } catch (Throwable ignored) {}
        }
    }

    private void handle(Packet<?> p) {
        Class<?> c = p.getClass();
        PacketDataSerializer s = PacketCodec.decode(p);
        if (s == null) return;
        try {
            if (c == PacketPlayOutKeepAlive.class) {
                send(PacketCodec.keepAlive(s.e()));
            } else if (c == PacketPlayOutPosition.class) {
                handlePosition(s);
            } else if (c == PacketPlayOutEntityVelocity.class) {
                int id = s.e();
                double mx = s.readShort() / 8000.0D, my = s.readShort() / 8000.0D, mz = s.readShort() / 8000.0D;
                if (id == entityId) {
                    tookKnockback = true;
                    if (!cheats.ignoresVelocity()) { phys.motionX = mx; phys.motionY = my; phys.motionZ = mz; }
                }
            } else if (c == PacketPlayOutExplosion.class) {
                s.readFloat(); s.readFloat(); s.readFloat(); s.readFloat();
                int n = s.readInt();
                s.skipBytes(n * 3);
                float mx = s.readFloat(), my = s.readFloat(), mz = s.readFloat();
                tookKnockback = true;
                if (!cheats.ignoresVelocity()) { phys.motionX += mx; phys.motionY += my; phys.motionZ += mz; }
            } else if (c == PacketPlayOutUpdateHealth.class) {
                health = s.readFloat();
                food = s.e();
                if (health <= 0.0F && !dead) onDeath();
                else if (health > 0.0F && dead) dead = false;
            } else if (c == PacketPlayOutOpenWindow.class) {
                int id = s.readUnsignedByte();
                windowId = id;
                windowAction = 0;
                inventoryOpen = false;
                lastWindowActivity = clientTime;
                onGuiOpened();
            } else if (c == PacketPlayOutCloseWindow.class) {
                s.readUnsignedByte();
                windowId = -1;
                inventoryOpen = false;
            } else if (c == PacketPlayOutEntityEffect.class) {
                int id = s.e();
                int effect = s.readByte();
                int amp = s.readByte();
                if (id == entityId) setEffect(effect, amp);
            } else if (c == PacketPlayOutRemoveEntityEffect.class) {
                int id = s.e();
                int effect = s.readUnsignedByte();
                if (id == entityId) setEffect(effect, -1);
            } else if (c == PacketPlayOutAbilities.class) {
                int flags = s.readByte();
                phys.flySpeed = s.readFloat();
                phys.walkSpeed = s.readFloat();
                abilitiesFlying = (flags & 2) != 0;
                allowFlying = (flags & 4) != 0;
                creativeAbility = (flags & 8) != 0;
                phys.allowFlying = allowFlying;
            } else if (c == PacketPlayOutGameStateChange.class) {
                int reason = s.readUnsignedByte();
                float value = s.readFloat();
                if (reason == 3) setGamemode((int) Math.floor(value + 0.5F));
            } else if (c == PacketPlayOutHeldItemSlot.class) {
                int slot = s.readByte();
                if (slot >= 0 && slot < 9) currentItem = slot;
            } else if (c == PacketPlayOutTransaction.class) {
                int wid = s.readUnsignedByte();
                short action = s.readShort();
                boolean accepted = s.readBoolean();
                if (!accepted && (wid == 0 || wid == windowId)) send(PacketCodec.transaction(wid, action, true));
            } else if (c == PacketPlayOutEntityStatus.class) {
                int id = s.readInt();
                int status = s.readByte();
                if (id == entityId && status == 9) stopUsingLocal(); // item use finished (server)
            } else if (c == PacketPlayOutLogin.class) {
                s.readInt();
                int gm = s.readUnsignedByte() & ~8;
                setGamemode(gm);
                loggedIn = true;
                // NetHandlerPlayClient.handleJoinGame: settings, then brand
                send(PacketCodec.settings("en_US", 8 + rnd.nextInt(5), 0, true, 0x7F));
                send(PacketCodec.brand("vanilla"));
            } else if (c == PacketPlayOutRespawn.class) {
                s.readInt();
                s.readUnsignedByte();
                int gm = s.readUnsignedByte();
                onRespawn(gm);
            }
        } finally {
            s.clear();
        }
    }

    private void handlePosition(PacketDataSerializer s) {
        double x = s.readDouble(), y = s.readDouble(), z = s.readDouble();
        float yaw = s.readFloat(), pitch = s.readFloat();
        int flags = s.readUnsignedByte();
        if ((flags & 1) != 0) x += phys.posX; else phys.motionX = 0.0D;
        if ((flags & 2) != 0) y += phys.posY; else phys.motionY = 0.0D;
        if ((flags & 4) != 0) z += phys.posZ; else phys.motionZ = 0.0D;
        if ((flags & 16) != 0) pitch += mouse.pitch;
        if ((flags & 8) != 0) yaw += mouse.yaw;
        phys.world = ep.world;
        phys.setPosition(x, y, z);
        mouse.setRotation(yaw % 360.0F, pitch % 360.0F);
        send(PacketCodec.positionLook(phys.posX, phys.minY(), phys.posZ, mouse.yaw, mouse.pitch, false));
        doneLoadingTerrain = true;
    }

    private void setEffect(int effect, int amp) {
        switch (effect) {
            case 1: phys.speedAmp = amp; break;
            case 2: phys.slowAmp = amp; break;
            case 3: hasteAmp = amp; break;
            case 4: fatigueAmp = amp; break;
            case 8: phys.jumpAmp = amp; break;
            case 15: blind = amp >= 0; break;
            default: break;
        }
    }

    private void setGamemode(int gm) {
        gamemode = gm;
        phys.spectator = gm == 3;
        phys.creative = gm == 1;
    }

    private void onDeath() {
        dead = true;
        stopUsingLocal();
        resetBlockRemoving();
        windowId = -1;
        inventoryOpen = false;
        // GuiGameOver buttons unlock after 20 ticks; humans click "Respawn" a little later
        double delay = 1000.0D + Math.abs(rnd.nextGaussian()) * 700.0D + rnd.nextDouble() * 500.0D;
        respawnAt = clientTime + delay;
    }

    private void onRespawn(int gm) {
        setGamemode(gm);
        dead = false;
        health = 20.0F;
        respawnAt = -1.0D;
        phys.resetMotion();
        phys.sprinting = false;
        phys.flying = false;
        phys.speedAmp = phys.slowAmp = phys.jumpAmp = -1;
        hasteAmp = fatigueAmp = -1;
        blind = false;
        serverSprintState = false;
        serverSneakState = false;
        lastReportedPosX = lastReportedPosY = lastReportedPosZ = 0.0D;
        lastReportedYaw = lastReportedPitch = 0.0F;
        positionUpdateTicks = 0;
        windowId = -1;
        inventoryOpen = false;
        inventoryAction = 0;
        stopUsingLocal();
        isHittingBlock = false;
        curBlockDamageMP = 0.0F;
        doneLoadingTerrain = false;
        phys.world = ep.world;
    }

    // =================================================================================== client tick
    /** One client tick (Minecraft.runTick + world tick of the player). Main thread, after brain.tick. */
    public void tick() {
        clientTime += 50.0D;
        phys.nowMs = clientTime;
        if (!loggedIn) return;
        phys.world = ep.world;

        // ---- mouse frames rendered since the last tick ----
        mouse.tickStart();

        // ---- Minecraft.runTick ----
        if (rightClickDelayTimer > 0) --rightClickDelayTimer;
        // EntityRenderer.getMouseOver: idle bots keep the lazy ray trace; the click path re-traces per frame below
        overValid = false;
        overYaw = mouse.yaw;
        overPitch = mouse.pitch;
        syncCurrentPlayItem();

        if (dead) {
            if (respawnAt >= 0.0D && clientTime >= respawnAt) {
                send(PacketCodec.clientCommand(PacketPlayInClientCommand.EnumClientCommand.PERFORM_RESPAWN));
                respawnAt = clientTime + 2500.0D; // retry if the server ignores us
            }
        }
        if (inventoryOpen && clientTime - lastWindowActivity > inventoryIdleCloseMs) closeWindow();

        clicks.tick(clientTime);
        int presses = clicks.presses;
        boolean leftDown = clicks.keyDown;
        int rights = pendingRightClicks;
        pendingRightClicks = 0;
        BlockPosition forced = forcedUseBlock;
        forcedUseBlock = null;

        double eyeX = phys.posX, eyeY = phys.posY + (double) phys.eyeHeight(), eyeZ = phys.posZ;
        if (guiOpen() || !doneLoadingTerrain) {
            leftClickCounter = 10000;
            if (!guiOpen()) mouse.frames(eyeX, eyeY, eyeZ);
            overValid = false;
            overYaw = mouse.yaw;
            overPitch = mouse.pitch;
        } else {
            if (leftClickCounter > 0) --leftClickCounter;
            if (presses > 0 || leftDown || rights > 0 || forced != null || rightHeld || isHittingBlock) {
                // per-frame rotation + uncached raycast + the queued click with THAT frame's aim
                frameClickLoop(presses, rights, forced);
            } else {
                // idle path: one simulation of every frame, ray trace still deferred until something asks for it
                mouse.frames(eyeX, eyeY, eyeZ);
                overValid = false;
                overYaw = mouse.yaw;
                overPitch = mouse.pitch;
            }
            if (usingItem) {
                if (!rightHeld) onStoppedUsingItem();
            }
            sendClickBlockToController(leftDown);
            cheats.preMovement();
        }

        // ---- player.onUpdate (only once the terrain is there) ----
        if (!doneLoadingTerrain || !phys.isChunkLoadedHere()) return;
        if (dead) {
            // EntityLivingBase.onDeathUpdate: after 20 ticks the client entity is setDead() and stops ticking
            // (no more flying packets; server teleports are still answered by the packet handler)
            if (++deathTicks > 20) return;
        } else {
            deathTicks = 0;
        }
        playerUpdate();
    }

    /**
     * One tick's mouse frames with the click budget spread over them. Frame 0 gets the first queued press, so a tick
     * with presses = 1 still sends exactly one attack; every action is preceded by a rotation packet carrying that
     * frame's aim, which is what lets a bot turn and place inside a single server tick.
     */
    private void frameClickLoop(int presses, int rights, BlockPosition forced) {
        double ex = phys.posX, ey = phys.posY + (double) phys.eyeHeight(), ez = phys.posZ;
        boolean canAct = !usingItem;
        boolean forcedDone = false;
        boolean heldDone = false;
        for (int i = 0; i < HumanMouse.FRAMES_PER_TICK; i++) {
            mouse.frameStep(ex, ey, ez);
            frameOver();
            boolean used = false;
            if (canAct) {
                if (presses > 0) {
                    --presses;
                    sendRotationNow();
                    if (clickMouse()) {
                        used = true;
                        ++clickFramesUsed;
                        CombatStats.inc("click.frames.used");
                    }
                }
                if (!forcedDone) {
                    forcedDone = true;
                    if (forced != null) {
                        sendRotationNow();
                        useOnBlock(forced);
                        used = true;
                        ++clickFramesUsed;
                        CombatStats.inc("click.frames.used");
                    }
                }
                if (rights > 0) {
                    --rights;
                    sendRotationNow();
                    rightClickMouse();
                    used = true;
                    ++clickFramesUsed;
                    CombatStats.inc("click.frames.used");
                }
            }
            if (!heldDone && rightHeld && rightClickDelayTimer == 0 && !usingItem) {
                heldDone = true;
                sendRotationNow();
                rightClickMouse();
                used = true;
                ++clickFramesUsed;
                CombatStats.inc("click.frames.used");
            }
            if (!used) {
                ++clickFramesIdle;
                CombatStats.inc("click.frames.idle");
            }
        }
        // A burst larger than the frame count still has to be spent this tick; otherwise a queue build-up would
        // silently lower the CPS the model asked for. Vanilla processes every queued press in the same tick too.
        while (canAct && presses > 0) {
            --presses;
            sendRotationNow();
            if (clickMouse()) {
                ++clickFramesUsed;
                CombatStats.inc("click.frames.used");
            }
        }
        while (canAct && rights > 0) {
            --rights;
            sendRotationNow();
            rightClickMouse();
            ++clickFramesUsed;
            CombatStats.inc("click.frames.used");
        }
    }

    /** objectMouseOver for the frame that was just simulated: always re-traced, never a cached hit. */
    private void frameOver() {
        overValid = true;
        overYaw = mouse.yaw;
        overPitch = mouse.pitch;
        over.compute(phys.world, ep, phys, overYaw, overPitch, cheats.entityReach(),
                phys.creative ? 5.0D : 4.5D, phys.creative);
    }

    /**
     * Sends the current mouse rotation immediately, before the action that depends on it. Real 1.8 orders packets
     * as Flying (rotation) -> arm swing -> block place / use item / attack, and the server processes them in arrival
     * order. A no-op when the rotation was already reported, so onUpdateWalkingPlayer does not repeat it at the end
     * of the tick.
     */
    void sendRotationNow() {
        float yaw = mouse.yaw, pitch = mouse.pitch;
        if (yaw == lastReportedYaw && pitch == lastReportedPitch) return;
        // 1.8: PacketCodec.flying(...) carries only onGround. The rotation-carrying PacketPlayInFlying variant is
        // the C05 look packet, so that is what the pre-click rotation uses.
        send(PacketCodec.look(yaw, pitch, cheats.groundFlag(phys.onGround)));
        lastReportedYaw = yaw;
        lastReportedPitch = pitch;
    }

    private boolean overValid;
    private float overYaw, overPitch;
    private int clickFramesUsed, clickFramesIdle;

    /** objectMouseOver for this tick (computed on first use). */
    MouseOver ensureOver() {
        if (!overValid) {
            overValid = true;
            if (loggedIn && doneLoadingTerrain) {
                over.compute(phys.world, ep, phys, overYaw, overPitch, cheats.entityReach(), phys.creative ? 5.0D : 4.5D, phys.creative);
            } else {
                over.clear();
            }
        }
        return over;
    }

    boolean guiOpen() {
        return windowId > 0 || inventoryOpen || dead;
    }

    private void onGuiOpened() {
        // KeyBinding.unPressAllKeys(): the GUI swallows input (handled by guiOpen() checks in the tick); keys that
        // are still physically held resume when it closes (Minecraft.setIngameFocus -> updateKeyBindState)
        lastWindowActivity = clientTime;
    }

    /** EntityPlayer.onUpdate -> EntityLivingBase -> EntityPlayerSP.onLivingUpdate -> onUpdateWalkingPlayer. */
    private void playerUpdate() {
        // item in use countdown (the server finishes it and tells us with entity status 9)
        if (usingItem) {
            ItemStack held = heldStack();
            if (held == null || held.getItem() != itemInUse || currentItem != itemInUseSlot) stopUsingLocal();
            else if (itemInUseCount > 0) --itemInUseCount;
        }

        phys.entityUpdate();

        // MovementInputFromOptions.updatePlayerMoveState
        boolean gui = guiOpen();
        float fwd = gui ? 0.0F : digital(keyForward);
        float str = gui ? 0.0F : digital(keyStrafe);
        inJump = !gui && keyJump;
        inSneak = !gui && keySneak;
        if (inSneak) {
            str = (float) ((double) str * 0.3D);
            fwd = (float) ((double) fwd * 0.3D);
        }
        if (usingItem) {
            str *= 0.2F;
            fwd *= 0.2F;
        }
        inForward = fwd;
        inStrafe = str;
        phys.sneaking = inSneak;
        phys.rotationYaw = mouse.yaw;
        phys.rotationPitch = mouse.pitch;

        phys.pushOutOfBlocks();

        // sprint rules
        float f = 0.8F;
        boolean flag3 = (float) food > 6.0F || allowFlying;
        boolean sprintKey = !gui && keySprint;
        if (!phys.sprinting && inForward >= f && flag3 && !usingItem && !blind && sprintKey) phys.sprinting = true;
        if (phys.sprinting && (inForward < f || phys.isCollidedHorizontally || !flag3)) phys.sprinting = false;

        // flight (server-granted, or the FLY cheat)
        cheats.updateFly();
        phys.flying = abilitiesFlying || cheats.hackFlying;
        if (phys.flying) {
            if (inSneak) phys.motionY -= (double) (phys.flySpeed * 3.0F);
            if (inJump) phys.motionY += (double) (phys.flySpeed * 3.0F);
        }

        ItemStack boots = ep.inventory.armor[0];
        phys.depthStrider = boots == null ? 0 : EnchantmentManager.getEnchantmentLevel(8, boots);

        double preX = phys.posX, preZ = phys.posZ;
        phys.livingUpdate(inStrafe, inForward, inJump, dead);
        // what the body was actually doing this tick: input present, sprinting, and how far it went
        if (inForward != 0 || inStrafe != 0) {
            if (phys.sprinting) net.h2020.api.stats.CombatStats.inc("mv.sprint.tick");
            else net.h2020.api.stats.CombatStats.inc("mv.walk.tick");
            if (inStrafe != 0) net.h2020.api.stats.CombatStats.inc("mv.strafe.tick");
        }
        double step = Math.hypot(phys.posX - preX, phys.posZ - preZ);
        if (step > 0.24) net.h2020.api.stats.CombatStats.inc("mv.fast.tick");
        else if (step > 0.10) net.h2020.api.stats.CombatStats.inc("mv.normal.tick");
        else net.h2020.api.stats.CombatStats.inc("mv.slow.tick");
        if (inForward != 0 || inStrafe != 0) net.h2020.api.stats.CombatStats.inc("move.input.tick");
        else if (clientTickHasPurpose) net.h2020.api.stats.CombatStats.inc("move.noinput.tick");
        if (Math.abs(phys.posX - preX) + Math.abs(phys.posZ - preZ) < 0.02) {
            if (inForward != 0 || inStrafe != 0) net.h2020.api.stats.CombatStats.inc("move.input.nomove");
        } else net.h2020.api.stats.CombatStats.inc("move.moved.tick");

        if (phys.onGround && abilitiesFlying && gamemode != 3) {
            abilitiesFlying = false;
            send(PacketCodec.abilities(false, false, allowFlying, creativeAbility, phys.flySpeed, phys.walkSpeed));
        }
        if (phys.onGround) cheats.hackFlying = false;

        onUpdateWalkingPlayer();
    }

    private static float digital(float v) {
        return v > 0.3F ? 1.0F : (v < -0.3F ? -1.0F : 0.0F);
    }

    /** EntityPlayerSP.onUpdateWalkingPlayer */
    private void onUpdateWalkingPlayer() {
        boolean flag = phys.sprinting;
        if (flag != serverSprintState) {
            send(PacketCodec.entityAction(entityId, flag ? PacketPlayInEntityAction.EnumPlayerAction.START_SPRINTING
                    : PacketPlayInEntityAction.EnumPlayerAction.STOP_SPRINTING, 0));
            serverSprintState = flag;
        }
        boolean flag1 = phys.sneaking;
        if (flag1 != serverSneakState) {
            send(PacketCodec.entityAction(entityId, flag1 ? PacketPlayInEntityAction.EnumPlayerAction.START_SNEAKING
                    : PacketPlayInEntityAction.EnumPlayerAction.STOP_SNEAKING, 0));
            serverSneakState = flag1;
        }

        float yaw = rotOverride ? rotOverrideYaw : mouse.yaw;
        float pitch = rotOverride ? rotOverridePitch : mouse.pitch;
        rotOverride = false;

        double d0 = phys.posX - lastReportedPosX;
        double d1 = phys.minY() - lastReportedPosY;
        double d2 = phys.posZ - lastReportedPosZ;
        double d3 = (double) (yaw - lastReportedYaw);
        double d4 = (double) (pitch - lastReportedPitch);
        boolean flag2 = d0 * d0 + d1 * d1 + d2 * d2 > 9.0E-4D || positionUpdateTicks >= 20;
        // sendRotationNow() already reported the rotation of every frame that clicked; only a later change is sent here
        boolean flag3 = d3 != 0.0D || d4 != 0.0D;
        boolean ground = cheats.groundFlag(phys.onGround);
        if (DEBUG_NAME != null && DEBUG_NAME.equalsIgnoreCase(player.getName())) {
            org.bukkit.Bukkit.getLogger().info(String.format(
                    "[botdbg] %s t=%.0f fwd=%.2f str=%.2f spr=%s gnd=%s h=%.4f mot=%.4f send=%s",
                    player.getName(), clientTime, inForward, inStrafe, phys.sprinting, phys.onGround,
                    Math.sqrt(d0 * d0 + d2 * d2), Math.sqrt(phys.motionX * phys.motionX + phys.motionZ * phys.motionZ),
                    flag2 ? "pos" : "none"));
        }

        if (flag2 && flag3) send(PacketCodec.positionLook(phys.posX, phys.minY(), phys.posZ, yaw, pitch, ground));
        else if (flag2) send(PacketCodec.position(phys.posX, phys.minY(), phys.posZ, ground));
        else if (flag3) send(PacketCodec.look(yaw, pitch, ground));
        else send(PacketCodec.flying(ground));

        ++positionUpdateTicks;
        if (flag2) {
            lastReportedPosX = phys.posX;
            lastReportedPosY = phys.minY();
            lastReportedPosZ = phys.posZ;
            positionUpdateTicks = 0;
        }
        if (flag3) {
            lastReportedYaw = yaw;
            lastReportedPitch = pitch;
        }
    }

    // =================================================================================== PlayerControllerMP
    void syncCurrentPlayItem() {
        if (currentItem != syncedItem) {
            syncedItem = currentItem;
            send(PacketCodec.heldItem(currentItem));
        }
    }

    ItemStack heldStack() {
        ItemStack[] items = ep.inventory.items;
        return currentItem >= 0 && currentItem < items.length ? items[currentItem] : null;
    }

    void swingItem() {
        send(PacketCodec.swing());
    }

    /** Minecraft.clickMouse. @return true if the click was actually performed (the miss guard can swallow it). */
    private boolean clickMouse() {
        if (leftClickCounter > 0) return false;
        swingItem();
        swings++;
        if (over.type == MouseOver.Type.ENTITY && over.entity != null) {
            attackEntity(over.entity);
            markCombat();
        } else if (over.type == MouseOver.Type.BLOCK && material(over.blockPos) != Material.AIR) {
            clickBlock(over.blockPos, over.face);
        } else if (gamemode != 1) {
            leftClickCounter = 10;
        }
        return true;
    }

    /** PlayerControllerMP.attackEntity + client-side EntityPlayer.attackTargetEntityWithCurrentItem effects. */
    void attackEntity(Entity target) {
        syncCurrentPlayItem();
        send(PacketCodec.attack(target.getId()));
        if (gamemode == 3) return;
        // EntityOtherPlayerMP.attackEntityFrom returns true on the client, so the attacker's own sprint-hit
        // slowdown happens client-side: motion *= 0.6, sprint off (the famous 1.8 sprint reset).
        if (target instanceof EntityPlayer) {
            int i = EnchantmentManager.getEnchantmentLevel(19, heldStack());
            if (phys.sprinting) ++i;
            if (i > 0) {
                phys.motionX *= 0.6D;
                phys.motionZ *= 0.6D;
                phys.sprinting = false;
            }
        }
    }

    private Material material(BlockPosition pos) {
        return phys.world.getType(pos).getBlock().getMaterial();
    }

    private boolean isHittingPosition(BlockPosition pos) {
        ItemStack held = heldStack();
        Item item = held == null ? null : held.getItem();
        return pos.equals(currentBlock) && item == currentItemHittingBlock;
    }

    /** PlayerControllerMP.clickBlock */
    private void clickBlock(BlockPosition loc, EnumDirection face) {
        if (!phys.world.getWorldBorder().a(loc)) return;
        if (gamemode == 2 || gamemode == 3) return; // adventure/spectator can't dig (client checks canPlayerEdit)
        if (gamemode == 1) {
            send(PacketCodec.dig(PacketPlayInBlockDig.EnumPlayerDigType.START_DESTROY_BLOCK, loc, face));
            blockHitDelay = 5;
            return;
        }
        if (!isHittingBlock || !isHittingPosition(loc)) {
            if (isHittingBlock) send(PacketCodec.dig(PacketPlayInBlockDig.EnumPlayerDigType.ABORT_DESTROY_BLOCK, currentBlock, face));
            send(PacketCodec.dig(PacketPlayInBlockDig.EnumPlayerDigType.START_DESTROY_BLOCK, loc, face));
            Block block = phys.world.getType(loc).getBlock();
            boolean flag = block.getMaterial() != Material.AIR;
            if (flag && block.getDamage(ep, phys.world, loc) >= 1.0F) {
                // instant break: the server breaks it on START
                isHittingBlock = false;
            } else {
                isHittingBlock = true;
                currentBlock = loc;
                currentFace = face;
                ItemStack held = heldStack();
                currentItemHittingBlock = held == null ? null : held.getItem();
                curBlockDamageMP = 0.0F;
            }
        }
    }

    /** PlayerControllerMP.onPlayerDamageBlock */
    private boolean onPlayerDamageBlock(BlockPosition pos, EnumDirection face) {
        syncCurrentPlayItem();
        if (blockHitDelay > 0) {
            --blockHitDelay;
            return true;
        }
        if (gamemode == 1) {
            blockHitDelay = 5;
            send(PacketCodec.dig(PacketPlayInBlockDig.EnumPlayerDigType.START_DESTROY_BLOCK, pos, face));
            return true;
        }
        if (isHittingPosition(pos)) {
            Block block = phys.world.getType(pos).getBlock();
            if (block.getMaterial() == Material.AIR) {
                isHittingBlock = false;
                return false;
            }
            curBlockDamageMP += block.getDamage(ep, phys.world, pos);
            if (curBlockDamageMP >= 1.0F) {
                isHittingBlock = false;
                send(PacketCodec.dig(PacketPlayInBlockDig.EnumPlayerDigType.STOP_DESTROY_BLOCK, pos, face));
                curBlockDamageMP = 0.0F;
                blockHitDelay = 5;
            }
            return true;
        }
        clickBlock(pos, face);
        return true;
    }

    /** PlayerControllerMP.resetBlockRemoving */
    private void resetBlockRemoving() {
        if (isHittingBlock) {
            send(PacketCodec.dig(PacketPlayInBlockDig.EnumPlayerDigType.ABORT_DESTROY_BLOCK, currentBlock, EnumDirection.DOWN));
            isHittingBlock = false;
            curBlockDamageMP = 0.0F;
        }
    }

    /** Minecraft.sendClickBlockToController */
    private void sendClickBlockToController(boolean leftClick) {
        if (!leftClick) leftClickCounter = 0;
        if (leftClickCounter <= 0 && !usingItem) {
            if (leftClick && over.type == MouseOver.Type.BLOCK && over.blockPos != null) {
                if (material(over.blockPos) != Material.AIR && onPlayerDamageBlock(over.blockPos, over.face)) {
                    swingItem();
                }
            } else {
                resetBlockRemoving();
            }
        }
    }

    public float breakProgress() { return isHittingBlock ? curBlockDamageMP : 0.0F; }

    /** Minecraft.rightClickMouse */
    void rightClickMouse() {
        if (isHittingBlock) return;
        rightClickDelayTimer = cheats.fastPlace() ? 0 : 4;
        boolean flag = true;
        ItemStack stack = heldStack();
        if (over.type == MouseOver.Type.ENTITY && over.entity != null) {
            Entity e = over.entity;
            syncCurrentPlayItem();
            Vec3D hv = over.hitVec;
            send(PacketCodec.interactAt(e.getId(), (float) (hv.a - e.locX), (float) (hv.b - e.locY), (float) (hv.c - e.locZ)));
            boolean handled = gamemode != 3 && e instanceof EntityArmorStand;
            if (!handled) {
                syncCurrentPlayItem();
                send(PacketCodec.interact(e.getId()));
                handled = gamemode != 3 && clientInteractsWith(e);
            }
            if (handled) flag = false;
        } else if (over.type == MouseOver.Type.BLOCK && over.blockPos != null) {
            BlockPosition pos = over.blockPos;
            if (material(pos) != Material.AIR) {
                if (onPlayerRightClick(stack, pos, over.face, over.hitVec)) {
                    flag = false;
                    swingItem();
                }
                if (stack == null) return;
            }
        }
        if (flag) {
            ItemStack s1 = heldStack();
            if (s1 != null) sendUseItem(s1);
        }
    }

    /** Right-click a specific block (openContainer) even if the crosshair is slightly off it. */
    private void useOnBlock(BlockPosition pos) {
        if (isHittingBlock) return;
        double ex = phys.posX, ey = phys.posY + (double) phys.eyeHeight(), ez = phys.posZ;
        Vec3D eye = new Vec3D(ex, ey, ez);
        Vec3D centre = new Vec3D(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        IBlockData st = phys.world.getType(pos);
        Block b = st.getBlock();
        b.updateShape(phys.world, pos);
        MovingObjectPosition mop = b.a(phys.world, pos, eye, centre);
        if (mop == null) {
            AxisAlignedBB box = new AxisAlignedBB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
            mop = box.a(eye, centre);
        }
        if (mop == null || MouseOver.dist(mop.pos, ex, ey, ez) > 4.5D) return;
        rightClickDelayTimer = 4;
        ItemStack stack = heldStack();
        if (onPlayerRightClick(stack, pos, mop.direction, mop.pos)) swingItem();
        else if (stack != null) sendUseItem(stack);
    }

    /** PlayerControllerMP.onPlayerRightClick */
    boolean onPlayerRightClick(ItemStack stack, BlockPosition pos, EnumDirection face, Vec3D hit) {
        syncCurrentPlayItem();
        float fx = (float) (hit.a - (double) pos.getX());
        float fy = (float) (hit.b - (double) pos.getY());
        float fz = (float) (hit.c - (double) pos.getZ());
        boolean flag = false;
        World w = phys.world;
        if (!w.getWorldBorder().a(pos)) return false;
        if (gamemode != 3) {
            IBlockData st = w.getType(pos);
            if ((!phys.sneaking || stack == null) && ClientBlocks.activates(st.getBlock())) flag = true;
            if (!flag && stack != null && stack.getItem() instanceof ItemBlock) {
                if (!canPlaceBlockOnSide((ItemBlock) stack.getItem(), pos, face, stack)) return false;
            }
        }
        send(PacketCodec.place(pos, face.a(), heldStack(), fx, fy, fz));
        if (!flag && gamemode != 3) {
            if (stack == null) return false;
            if (stack.getItem() instanceof ItemBlock) predictPlacement((ItemBlock) stack.getItem(), pos, face);
            if (gamemode == 1) return true;
            return ClientBlocks.itemUseOnBlockSucceeds(stack);
        }
        return true;
    }

    /** The client places the block locally right away; remember it for collisions until the server confirms. */
    void predictPlacement(ItemBlock item, BlockPosition against, EnumDirection face) {
        Block placed = item.d();
        if (!placed.d() || !placed.getMaterial().isSolid()) return; // only full solid cubes matter for movement
        Block at = phys.world.getType(against).getBlock();
        boolean replaceable = !phys.isPredicted(against.getX(), against.getY(), against.getZ())
                && (Block.getId(at) == 78 || at.getMaterial().isReplaceable());
        BlockPosition target = replaceable ? against : against.shift(face);
        phys.predictBlock(target.getX(), target.getY(), target.getZ(), pingMs + 250.0D);
    }

    /** ItemBlock.canPlaceBlockOnSide (client) */
    boolean canPlaceBlockOnSide(ItemBlock item, BlockPosition pos, EnumDirection side, ItemStack stack) {
        World w = phys.world;
        Block block = w.getType(pos).getBlock();
        if (Block.getId(block) == 78) side = EnumDirection.UP; // snow layer
        else if (!block.getMaterial().isReplaceable()) pos = pos.shift(side);
        return w.a(item.d(), pos, false, side, null, stack);
    }

    /** PlayerControllerMP.sendUseItem + the client-side ItemStack.useItemRightClick prediction. */
    void sendUseItem(ItemStack stack) {
        if (gamemode == 3) return;
        syncCurrentPlayItem();
        send(PacketCodec.useItem(heldStack()));
        EnumAnimation anim = stack.m();
        boolean start = false;
        if (anim == EnumAnimation.EAT) start = food < 20 || Item.getId(stack.getItem()) == 322 || gamemode == 1;
        else if (anim == EnumAnimation.DRINK || anim == EnumAnimation.BLOCK) start = true;
        else if (anim == EnumAnimation.BOW) start = gamemode == 1 || hasArrows();
        if (start) {
            usingItem = true;
            itemInUse = stack.getItem();
            itemInUseSlot = currentItem;
            itemInUseCount = stack.l();
        }
    }

    private boolean hasArrows() {
        ItemStack[] items = ep.inventory.items;
        for (ItemStack it : items) if (it != null && Item.getId(it.getItem()) == 262) return true;
        return false;
    }

    /** PlayerControllerMP.onStoppedUsingItem */
    private void onStoppedUsingItem() {
        syncCurrentPlayItem();
        send(PacketCodec.dig(PacketPlayInBlockDig.EnumPlayerDigType.RELEASE_USE_ITEM, ORIGIN, EnumDirection.DOWN));
        stopUsingLocal();
    }

    private void stopUsingLocal() {
        usingItem = false;
        itemInUse = null;
        itemInUseCount = 0;
    }

    /** EntityPlayer.interactWith on the client: which entities consume the right click. */
    private boolean clientInteractsWith(Entity e) {
        if (e instanceof EntityVillager) return !((EntityVillager) e).isBaby() && !phys.sneaking;
        return e instanceof EntityMinecartAbstract || e instanceof EntityItemFrame || e instanceof EntityHorse;
    }

    void markCombat() {
        combatUntil = clientTime + 6000.0D;
    }

    // =================================================================================== BotController
    @Override
    public void setMovement(float forward, float strafe) {
        keyForward = Math.max(-1.0F, Math.min(1.0F, forward));
        keyStrafe = Math.max(-1.0F, Math.min(1.0F, strafe));
    }

    @Override public void setJumping(boolean jump) { keyJump = jump; }
    @Override public void setSneaking(boolean sneak) { keySneak = sneak; }
    @Override public void setSprinting(boolean sprint) { keySprint = sprint; }

    @Override
    public void aimAt(Location point, AimIntent intent) {
        if (point == null) { clearAim(); return; }
        double ey = phys.posY + (double) phys.eyeHeight();
        mouse.aimAt(point.getX(), point.getY(), point.getZ(), intent, phys.posX, ey, phys.posZ);
    }

    @Override
    public void aimAt(org.bukkit.entity.Entity entity, AimIntent intent) {
        if (entity == null) { clearAim(); return; }
        mouse.aimAt(((CraftEntity) entity).getHandle(), intent);
        if (intent == AimIntent.COMBAT || intent == AimIntent.FLICK) markCombat();
    }

    @Override
    public void aimRotation(float yaw, float pitch, AimIntent intent) {
        mouse.aimRotation(yaw, pitch, intent);
    }

    @Override public void clearAim() { mouse.clear(); }

    @Override public void setAimPolicy(double fracBias, double leadScale) { mouse.setAimPolicy(fracBias, leadScale); }

    @Override public int swings() { return swings; }
    @Override public boolean aimSettled() { return mouse.settled(); }
    @Override public float yaw() { return mouse.yaw; }
    @Override public float pitch() { return mouse.pitch; }

    @Override
    public org.bukkit.entity.Entity targetedEntity() {
        MouseOver o = ensureOver();
        return o.type == MouseOver.Type.ENTITY && o.entity != null ? o.entity.getBukkitEntity() : null;
    }

    @Override
    public org.bukkit.block.Block targetedBlock() {
        MouseOver o = ensureOver();
        if (o.type != MouseOver.Type.BLOCK || o.blockPos == null) return null;
        return player.getWorld().getBlockAt(o.blockPos.getX(), o.blockPos.getY(), o.blockPos.getZ());
    }

    @Override
    public BlockFace targetedFace() {
        MouseOver o = ensureOver();
        if (o.type != MouseOver.Type.BLOCK || o.face == null) return null;
        return toBukkit(o.face);
    }

    static BlockFace toBukkit(EnumDirection d) {
        switch (d) {
            case DOWN: return BlockFace.DOWN;
            case UP: return BlockFace.UP;
            case NORTH: return BlockFace.NORTH;
            case SOUTH: return BlockFace.SOUTH;
            case WEST: return BlockFace.WEST;
            default: return BlockFace.EAST;
        }
    }

    @Override
    public void leftClick() {
        clicks.click(clientTime);
        if (ensureOver().type == MouseOver.Type.ENTITY) markCombat();
    }

    @Override
    public void setLeftHeld(boolean held, double cps) {
        clicks.setHeld(held, cps, clientTime);
        if (held && cps > 0.0D) markCombat();
    }

    @Override public void rightClick() { pendingRightClicks++; }

    @Override public void swing() { swingItem(); }
    @Override public void setRightHeld(boolean held) { rightHeld = held; }

    @Override
    public void selectHotbar(int slot) {
        if (slot < 0 || slot > 8 || guiOpen()) return;
        currentItem = slot;
    }

    @Override public int heldSlot() { return currentItem; }

    @Override
    public void openContainer(org.bukkit.block.Block block) {
        if (block == null) return;
        BlockPosition pos = new BlockPosition(block.getX(), block.getY(), block.getZ());
        MouseOver o = ensureOver();
        if (o.type == MouseOver.Type.BLOCK && pos.equals(o.blockPos)) pendingRightClicks++;
        else forcedUseBlock = pos;
    }

    @Override public boolean containerOpen() { return windowId > 0 || inventoryOpen; }

    public int windowId() { return windowId > 0 ? windowId : (inventoryOpen ? 0 : -1); }

    @Override
    public void clickWindow(int rawSlot, int button, int mode) {
        if (dead) return;
        int wid;
        Container c;
        if (windowId > 0) {
            wid = windowId;
            c = ep.activeContainer;
            if (c == null || c.windowId != wid) return;
        } else {
            if (!inventoryOpen) {
                // Minecraft.runTick: pressing E sends C16 OPEN_INVENTORY_ACHIEVEMENT before showing GuiInventory
                send(PacketCodec.clientCommand(PacketPlayInClientCommand.EnumClientCommand.OPEN_INVENTORY_ACHIEVEMENT));
                inventoryOpen = true;
                inventoryIdleCloseMs = 600.0D + rnd.nextDouble() * 450.0D;
                onGuiOpened();
            }
            wid = 0;
            c = ep.defaultContainer;
        }
        ItemStack predicted = null;
        if (rawSlot >= 0 && rawSlot < c.c.size()) {
            ItemStack inSlot = c.getSlot(rawSlot).getItem();
            if ((mode == 0 || mode == 1) && inSlot != null) predicted = inSlot.cloneItemStack();
        }
        short action = wid == 0 ? ++inventoryAction : ++windowAction;
        send(PacketCodec.windowClick(wid, rawSlot, button, action, mode, predicted));
        lastWindowActivity = clientTime;
    }

    @Override
    public void closeWindow() {
        if (windowId > 0) {
            send(PacketCodec.closeWindow(windowId));
            windowId = -1;
        } else if (inventoryOpen) {
            send(PacketCodec.closeWindow(0));
            inventoryOpen = false;
        }
    }

    @Override
    public void dropHeld(boolean wholeStack) {
        if (guiOpen() || gamemode == 3) return;
        send(PacketCodec.dig(wholeStack ? PacketPlayInBlockDig.EnumPlayerDigType.DROP_ALL_ITEMS
                : PacketPlayInBlockDig.EnumPlayerDigType.DROP_ITEM, ORIGIN, EnumDirection.DOWN));
    }

    @Override
    public void chat(String message) {
        if (message == null || message.isEmpty()) return;
        send(PacketCodec.chat(message));
    }

    @Override
    public void command(String commandLine) {
        if (commandLine == null || commandLine.isEmpty()) return;
        send(PacketCodec.chat(commandLine.startsWith("/") ? commandLine : "/" + commandLine));
    }

    @Override public boolean onGround() { return phys.onGround; }
    @Override public boolean collidedHorizontally() { return phys.isCollidedHorizontally; }
    @Override public Vector velocity() { return new Vector(phys.motionX, phys.motionY, phys.motionZ); }

    @Override
    public Location position() {
        return new Location(phys.world.getWorld(), phys.posX, phys.posY, phys.posZ, mouse.yaw, mouse.pitch);
    }

    @Override public boolean tookKnockback() { return tookKnockback; }

    // ---- extras for the body / debug ----
    public boolean isDead() { return dead; }
    public boolean loggedIn() { return loggedIn && doneLoadingTerrain; }
    public float health() { return health; }
    public int pingMs() { return pingMs; }
    public boolean leftHeld() { return clicks.held(); }
    public double clicksPerSecond() { return clicks.effectiveCps(); }

    public String debug() {
        StringBuilder sb = new StringBuilder();
        sb.append(mouse.debug()).append('\n');
        sb.append(String.format("pos=(%.3f, %.3f, %.3f) mot=(%.3f, %.3f, %.3f) ground=%s sprint=%s sneak=%s fly=%s web=%s water=%s",
                phys.posX, phys.posY, phys.posZ, phys.motionX, phys.motionY, phys.motionZ, phys.onGround,
                phys.sprinting, phys.sneaking, phys.flying, phys.isInWeb, phys.inWater)).append('\n');
        sb.append(String.format("server pos=(%.3f, %.3f, %.3f) ground=%s hp=%.1f food=%d fall=%.2f tabPing=%d",
                ep.locX, ep.locY, ep.locZ, ep.onGround, ep.getHealth(), food, phys.fallDistance, ep.ping)).append('\n');
        ensureOver();
        sb.append("over=").append(over.type);
        if (over.type == MouseOver.Type.ENTITY && over.entity != null) sb.append(' ').append(over.entity.getName());
        if (over.type == MouseOver.Type.BLOCK && over.blockPos != null) sb.append(' ').append(over.blockPos).append(' ').append(over.face);
        sb.append(String.format(" dist=%.2f", over.distance));
        sb.append(" slot=").append(currentItem).append(" using=").append(usingItem)
                .append(" dig=").append(isHittingBlock ? String.format("%.2f", curBlockDamageMP) : "-")
                .append(" window=").append(windowId()).append(" cps=").append(String.format("%.1f", clicks.effectiveCps()))
                .append(" cheat=").append(cheat).append(" ping=").append(pingMs).append("ms")
                .append(" queued=").append(toServer.size()).append('/').append(toClient.size())
                .append(" click.frames=").append(clickFramesUsed).append('/').append(clickFramesIdle);
        return sb.toString();
    }
}
