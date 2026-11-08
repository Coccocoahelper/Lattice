package net.h2020.bots;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.h2020.api.H2020;
import net.h2020.api.bot.Bot;
import net.h2020.api.bot.BotProfile;
import net.h2020.api.bot.BotService;
import net.h2020.api.bot.CheatProfile;
import net.h2020.api.core.ProfileService;
import net.h2020.bots.data.BotDataGuard;
import net.h2020.bots.profile.ProfileFactory;
import net.h2020.bots.profile.SkinRepository;
import net.minecraft.server.v1_8_R3.ChatComponentText;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.MinecraftServer;
import net.minecraft.server.v1_8_R3.PlayerConnection;
import net.minecraft.server.v1_8_R3.PlayerInteractManager;
import net.minecraft.server.v1_8_R3.WorldServer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_8_R3.CraftServer;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.spigotmc.SpigotConfig;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** BotService implementation: spawns fake players through the real login path and ticks their virtual clients. */
public final class BotManager implements BotService, Listener {
    private static final int[] ISP_FIRST_OCTETS = { 24, 47, 50, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 81, 82, 84,
            86, 88, 90, 92, 94, 96, 97, 98, 99, 107, 108, 109, 141, 151, 173, 174, 176, 178, 184, 185, 188, 194, 212, 217 };

    private final JavaPlugin plugin;
    private final Random rnd = new Random();
    /** Mutated on the main thread only; synchronized because Watchdog looks bots up from Netty threads. */
    private final Map<UUID, FakeBot> bots = Collections.synchronizedMap(new LinkedHashMap<UUID, FakeBot>());
    private final Set<String> names = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Location> pendingSpawn = new ConcurrentHashMap<UUID, Location>();
    private final List<FakeBot> tickList = new ArrayList<FakeBot>();
    private boolean tickListDirty = true;
    private final SkinRepository skins;
    private final ProfileFactory profiles;
    private BotDataGuard guard;
    private BukkitTask task;

    BotManager(JavaPlugin plugin, SkinRepository skins) {
        this.plugin = plugin;
        this.skins = skins;
        this.profiles = new ProfileFactory(rnd, skins, names);
    }

    Logger log() { return plugin.getLogger(); }

    /** Average skill for newly rolled bot profiles (/bot settings). -1 = the 2020 lobby mix. */
    public void setAverageSkill(double v) { profiles.setAverageSkill(v); }

    SkinRepository skins() { return skins; }

    void start() {
        guard = BotDataGuard.install(server(), plugin.getLogger());
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() { tickAll(); }
        }, 1L, 1L);
    }

    void shutdown() {
        if (task != null) task.cancel();
        task = null;
        for (FakeBot b : new ArrayList<FakeBot>(bots.values())) {
            try { remove(b); } catch (Throwable t) { plugin.getLogger().warning("[bots] remove failed: " + t); }
        }
        bots.clear();
        names.clear();
        HandlerList.unregisterAll(this);
        if (guard != null) guard.uninstall();
    }

    private static MinecraftServer server() {
        return ((CraftServer) Bukkit.getServer()).getServer();
    }

    // ================================================================== BotService
    @Override public BotProfile randomProfile() { return profiles.random(CheatProfile.NONE); }
    @Override public BotProfile randomProfile(CheatProfile cheat) { return profiles.random(cheat); }

    BotProfile profileNamed(String name, CheatProfile cheat) { return profiles.create(name, cheat); }

    boolean nameFree(String name) { return profiles.isFree(name); }

    @Override
    public Bot spawn(BotProfile profile, Location at) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Bots must be spawned on the main thread");
        if (profile == null || at == null || at.getWorld() == null) throw new IllegalArgumentException("profile/location required");
        if (Bukkit.getPlayerExact(profile.name) != null || Bukkit.getPlayer(profile.uuid) != null) {
            throw new IllegalArgumentException("A player named " + profile.name + " is already online");
        }
        MinecraftServer server = server();
        WorldServer world = ((CraftWorld) at.getWorld()).getHandle();
        GameProfile gp = new GameProfile(profile.uuid, profile.name);
        if (profile.skinValue != null) {
            gp.getProperties().put("textures", new Property("textures", profile.skinValue, profile.skinSignature));
        }

        guard.markBot(profile.uuid);
        names.add(profile.name.toLowerCase(Locale.ROOT));
        EntityPlayer ep = new EntityPlayer(server, world, gp, new PlayerInteractManager(world));
        guard.forgetStats(profile.uuid);
        ep.dimension = world.dimension;
        ep.setPositionRotation(at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch());
        ep.ping = profile.pingMs;

        FakeBot bot = new FakeBot(this, profile, ep, randomAddress(), rnd);
        bots.put(profile.uuid, bot);
        tickListDirty = true;
        pendingSpawn.put(profile.uuid, at.clone());

        boolean cacheFlag = SpigotConfig.saveUserCacheOnStopOnly;
        SpigotConfig.saveUserCacheOnStopOnly = true; // don't rewrite usercache.json for every bot login
        try {
            server.getPlayerList().a(bot.network, ep);
        } catch (RuntimeException e) {
            bots.remove(profile.uuid);
            tickListDirty = true;
            names.remove(profile.name.toLowerCase(Locale.ROOT));
            guard.unmarkBot(profile.uuid);
            throw e;
        } finally {
            SpigotConfig.saveUserCacheOnStopOnly = cacheFlag;
            pendingSpawn.remove(profile.uuid);
        }
        guard.forgetUserCache(server, gp);
        ep.ping = profile.pingMs;
        applyNetworkProfile(bot);
        return bot;
    }

    @Override
    public Collection<Bot> bots() {
        return Collections.<Bot>unmodifiableList(new ArrayList<Bot>(bots.values()));
    }

    @Override
    public boolean isBot(Player p) { return bot(p) != null; }

    @Override
    public Bot bot(Player p) {
        if (p == null) return null;
        FakeBot b = bots.get(p.getUniqueId());
        return b != null && !b.removed ? b : null;
    }

    FakeBot byName(String name) {
        for (FakeBot b : bots.values()) if (b.profile().name.equalsIgnoreCase(name)) return b;
        return null;
    }

    List<FakeBot> all() { return new ArrayList<FakeBot>(bots.values()); }

    // ================================================================== removal
    /** Disconnect like a client quitting: PlayerQuitEvent, quit message, tab removal (no data saved). */
    void remove(FakeBot bot) {
        if (bot.removed || bot.removing) return;
        bot.removing = true;
        PlayerConnection pc = bot.ep.playerConnection;
        if (pc != null) {
            try {
                pc.a(new ChatComponentText("Disconnected"));
            } catch (Throwable t) {
                plugin.getLogger().warning("[bots] disconnect of " + bot.profile().name + " failed: " + t);
            }
        }
        finish(bot); // in case the quit event did not reach us
    }

    private void finish(final FakeBot bot) {
        if (bot.removed) return;
        bot.removed = true;
        bots.remove(bot.profile().uuid);
        tickListDirty = true;
        names.remove(bot.profile().name.toLowerCase(Locale.ROOT));
        bot.onRemoved();
        final UUID id = bot.profile().uuid;
        final GameProfile gp = bot.ep.getProfile();
        // PlayerList.disconnect saves the player after the quit event: keep the save guard until it is done
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, new Runnable() {
                @Override public void run() { cleanupAfterQuit(id, gp); }
            });
        }
        // when shutting down the guard simply stays marked until it is uninstalled after every bot has left
    }

    private void cleanupAfterQuit(UUID id, GameProfile gp) {
        if (bots.containsKey(id)) return; // respawned with the same uuid
        guard.forgetUserCache(server(), gp);
        guard.unmarkBot(id);
    }

    // ================================================================== tick
    private void tickAll() {
        if (tickListDirty) {
            tickList.clear();
            tickList.addAll(bots.values());
            tickListDirty = false;
        }
        for (int i = 0, n = tickList.size(); i < n; i++) {
            FakeBot b = tickList.get(i);
            if (b.removed) continue;
            b.tick();
        }
    }

    // ================================================================== events
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawnLocation(PlayerSpawnLocationEvent e) {
        Location at = pendingSpawn.get(e.getPlayer().getUniqueId());
        if (at != null) e.setSpawnLocation(at);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        FakeBot b = bots.get(e.getPlayer().getUniqueId());
        if (b != null) applyNetworkProfile(b);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        FakeBot b = bots.get(e.getPlayer().getUniqueId());
        if (b != null && b.player == e.getPlayer()) finish(b);
    }

    private void applyNetworkProfile(FakeBot b) {
        ProfileService ps = H2020.find(ProfileService.class);
        if (ps == null) return;
        BotProfile p = b.profile();
        try {
            ps.setRank(b.player, p.rank, p.plusColor);
            ps.setNetworkLevel(b.player, p.networkLevel);
        } catch (Throwable t) {
            plugin.getLogger().fine("[bots] ProfileService rejected bot " + p.name + ": " + t);
        }
    }

    private SocketAddress randomAddress() {
        byte[] ip = new byte[4];
        ip[0] = (byte) ISP_FIRST_OCTETS[rnd.nextInt(ISP_FIRST_OCTETS.length)];
        ip[1] = (byte) rnd.nextInt(256);
        ip[2] = (byte) rnd.nextInt(256);
        ip[3] = (byte) (1 + rnd.nextInt(254));
        try {
            return new InetSocketAddress(InetAddress.getByAddress(ip), 49152 + rnd.nextInt(16000));
        } catch (Exception e) {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), 49152 + rnd.nextInt(16000));
        }
    }
}
