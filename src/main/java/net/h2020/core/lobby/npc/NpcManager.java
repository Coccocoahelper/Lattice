package net.h2020.core.lobby.npc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.h2020.api.H2020;
import net.h2020.api.bot.BotProfile;
import net.h2020.api.bot.BotService;
import net.h2020.api.npc.Hologram;
import net.h2020.api.npc.PacketNpc;
import net.h2020.api.game.GameFamily;
import net.h2020.api.game.GameType;
import net.h2020.api.packet.PacketHub;
import net.h2020.core.Core;
import net.h2020.core.CoreUtil;
import net.h2020.core.HypixelFormat;
import net.minecraft.server.v1_8_R3.Packet;
import net.minecraft.server.v1_8_R3.PacketPlayInUseEntity;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Clickable game-mode NPCs with holograms ("§bBED WARS" / "§e§lCLICK TO PLAY" / "§e1,234 Players") in the main lobby
 * (one per game) and the family lobbies (one per mode). Fully packet based; per-viewer range tracking.
 */
public final class NpcManager implements Listener, PacketHub.Listener {
    private static final double VIEW_RANGE = 56;
    private static Field useEntityId;

    /** Static description of one NPC. */
    private static final class Spec {
        final String id;
        final GameFamily lobby;     // lobby it stands in (null = main)
        final GameFamily target;    // main lobby NPCs: family to connect to
        final GameType mode;        // family lobby NPCs: mode to queue
        final String label;
        final double offsetX, offsetZ;
        Spec(String id, GameFamily lobby, GameFamily target, GameType mode, String label, double ox, double oz) {
            this.id = id; this.lobby = lobby; this.target = target; this.mode = mode; this.label = label; this.offsetX = ox; this.offsetZ = oz;
        }
    }

    /** A spawned NPC instance. */
    private static final class Live {
        final Spec spec;
        final PacketNpc npc;
        final Hologram holo;
        final Set<UUID> viewers = new HashSet<>();
        Live(Spec spec, PacketNpc npc, Hologram holo) { this.spec = spec; this.npc = npc; this.holo = holo; }
        World world() { return npc.location().getWorld(); }
    }

    private static final List<Spec> SPECS = Arrays.asList(
            new Spec("bedwars", null, GameFamily.BEDWARS, null, "BED WARS", -2.5, 6),
            new Spec("skywars", null, GameFamily.SKYWARS, null, "SKYWARS", 2.5, 6),
            new Spec("bw_solo", GameFamily.BEDWARS, null, GameType.BEDWARS_SOLO, "SOLO", -4.5, 6),
            new Spec("bw_doubles", GameFamily.BEDWARS, null, GameType.BEDWARS_DOUBLES, "DOUBLES", -1.5, 6.5),
            new Spec("bw_3v3v3v3", GameFamily.BEDWARS, null, GameType.BEDWARS_3V3V3V3, "3v3v3v3", 1.5, 6.5),
            new Spec("bw_4v4v4v4", GameFamily.BEDWARS, null, GameType.BEDWARS_4V4V4V4, "4v4v4v4", 4.5, 6),
            new Spec("sw_solo_normal", GameFamily.SKYWARS, null, GameType.SKYWARS_SOLO_NORMAL, "SOLO NORMAL", -4.5, 6),
            new Spec("sw_solo_insane", GameFamily.SKYWARS, null, GameType.SKYWARS_SOLO_INSANE, "SOLO INSANE", -1.5, 6.5),
            new Spec("sw_teams_normal", GameFamily.SKYWARS, null, GameType.SKYWARS_TEAMS_NORMAL, "TEAMS NORMAL", 1.5, 6.5),
            new Spec("sw_teams_insane", GameFamily.SKYWARS, null, GameType.SKYWARS_TEAMS_INSANE, "TEAMS INSANE", 4.5, 6));

    private final Core core;
    private final List<Live> live = new ArrayList<>();
    private final Map<Integer, Live> byEntityId = new ConcurrentHashMap<>();
    private final Map<UUID, Long> eligibleAt = new HashMap<>();
    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();
    private final Map<GameFamily, String[]> skins = new HashMap<>();
    private final List<BukkitTask> tasks = new ArrayList<>();

    public NpcManager(Core core) { this.core = core; }

    public static List<String> ids() {
        List<String> l = new ArrayList<>();
        for (Spec s : SPECS) l.add(s.id);
        return l;
    }

    // ---- lifecycle ----

    public void start() {
        PacketHub.register(this);
        resolveSkins();
        tasks.add(Bukkit.getScheduler().runTaskTimer(core.plugin, new Runnable() { @Override public void run() { track(); } }, 40L, 10L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(core.plugin, new Runnable() { @Override public void run() { updateCounts(); } }, 60L, 40L));
    }

    public void stop() {
        PacketHub.unregister(this);
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
        despawnAll();
    }

    /** (Re)build every NPC from config/default positions. */
    public void rebuild() {
        despawnAll();
        List<String> names = new ArrayList<>();
        for (Spec s : SPECS) {
            // family lobby missing (no maps module): only the main lobby NPCs make sense
            if (s.lobby != null && core.lobby.lobbyWorld(s.lobby).equals(core.lobby.lobbyWorld(null))) continue;
            Location loc = position(s);
            if (loc == null) continue;
            String[] skin = skins.get(s.lobby != null ? s.lobby : s.target);
            String profileName = ChatColor.DARK_GRAY + "[NPC] " + Integer.toString(live.size(), 36);
            PacketNpc npc = new PacketNpc(profileName, loc, skin == null ? null : skin[0], skin == null ? null : skin[1]);
            Location bottom = loc.clone().add(0, 2.1, 0);
            Hologram holo = new Hologram(bottom, Arrays.asList(ChatColor.AQUA + s.label, ChatColor.YELLOW + "" + ChatColor.BOLD + "CLICK TO PLAY", countLine(s)));
            Live l = new Live(s, npc, holo);
            live.add(l);
            byEntityId.put(npc.entityId(), l);
            for (int id : holo.entityIds()) byEntityId.put(id, l);
            names.add(npc.profileName());
        }
        core.scoreboards.hideNametags(names);
    }

    private void despawnAll() {
        for (Live l : live) {
            for (UUID id : l.viewers) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) { l.npc.hide(p); l.holo.hide(p); }
            }
        }
        live.clear();
        byEntityId.clear();
    }

    // ---- positions ----

    private Location position(Spec s) {
        World w = core.lobby.lobbyWorld(s.lobby);
        if (w == null) return null;
        ConfigurationSection c = core.plugin.getConfig().getConfigurationSection("core.npc-positions." + s.id);
        if (c != null) {
            World cw = Bukkit.getWorld(c.getString("world", w.getName()));
            if (cw != null) return new Location(cw, c.getDouble("x"), c.getDouble("y"), c.getDouble("z"), (float) c.getDouble("yaw"), 0f);
        }
        // generated hubs: stand on the pad the maps module built for this game / mode
        Location pad = core.lobby.pads().pad(w, s.mode != null ? s.mode.id() : s.id);
        if (pad != null) return pad;
        // default: a row in front of the lobby spawn, facing it
        Location spawn = core.lobby.spawn(w);
        double yawRad = Math.toRadians(spawn.getYaw());
        double fx = -Math.sin(yawRad), fz = Math.cos(yawRad);   // forward
        double rx = -fz, rz = fx;                                // right
        double x = spawn.getX() + fx * s.offsetZ + rx * s.offsetX;
        double z = spawn.getZ() + fz * s.offsetZ + rz * s.offsetX;
        double y = ground(w, x, spawn.getY(), z);
        Location loc = new Location(w, x, y, z);
        // face the spawn point
        double dx = spawn.getX() - x, dz = spawn.getZ() - z;
        loc.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        return loc;
    }

    private static double ground(World w, double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        int top = (int) Math.floor(y) + 4;
        for (int yy = top; yy > top - 12 && yy > 1; yy--) {
            Block b = w.getBlockAt(bx, yy - 1, bz);
            if (b.getType().isSolid() && !w.getBlockAt(bx, yy, bz).getType().isSolid() && !w.getBlockAt(bx, yy + 1, bz).getType().isSolid()) return yy;
        }
        return y;
    }

    /** /h2020 npc <id>: move an NPC to the admin's position and save it. */
    public boolean move(String id, Location to) {
        Spec spec = null;
        for (Spec s : SPECS) if (s.id.equalsIgnoreCase(id)) spec = s;
        if (spec == null) return false;
        String path = "core.npc-positions." + spec.id;
        core.plugin.getConfig().set(path + ".world", to.getWorld().getName());
        core.plugin.getConfig().set(path + ".x", Math.round(to.getX() * 100) / 100.0);
        core.plugin.getConfig().set(path + ".y", Math.round(to.getY() * 100) / 100.0);
        core.plugin.getConfig().set(path + ".z", Math.round(to.getZ() * 100) / 100.0);
        core.plugin.getConfig().set(path + ".yaw", Math.round(to.getYaw()));
        core.plugin.saveConfig();
        rebuild();
        return true;
    }

    // ---- skins ----

    private void resolveSkins() {
        final Map<GameFamily, String> names = new HashMap<>();
        names.put(GameFamily.BEDWARS, core.config.npcSkinBedwars);
        names.put(GameFamily.SKYWARS, core.config.npcSkinSkywars);
        Bukkit.getScheduler().runTaskAsynchronously(core.plugin, new Runnable() {
            @Override public void run() {
                final Map<GameFamily, String[]> fetched = new HashMap<>();
                for (Map.Entry<GameFamily, String> e : names.entrySet()) {
                    if (e.getValue() == null || e.getValue().trim().isEmpty()) continue;
                    String[] s = fetchSkin(e.getValue().trim());
                    if (s != null) fetched.put(e.getKey(), s);
                }
                Bukkit.getScheduler().runTask(core.plugin, new Runnable() {
                    @Override public void run() {
                        BotService bs = H2020.find(BotService.class);
                        for (GameFamily f : GameFamily.values()) {
                            String[] s = fetched.get(f);
                            if (s == null && bs != null) {
                                try {
                                    BotProfile bp = bs.randomProfile();
                                    if (bp != null && bp.skinValue != null) s = new String[]{bp.skinValue, bp.skinSignature};
                                } catch (Throwable ignored) {}
                            }
                            if (s != null) skins.put(f, s);
                        }
                        rebuild();
                    }
                });
            }
        });
    }

    private static String[] fetchSkin(String username) {
        try {
            JsonObject id = getJson("https://api.mojang.com/users/profiles/minecraft/" + username);
            if (id == null || !id.has("id")) return null;
            JsonObject prof = getJson("https://sessionserver.mojang.com/session/minecraft/profile/" + id.get("id").getAsString() + "?unsigned=false");
            if (prof == null || !prof.has("properties")) return null;
            JsonArray props = prof.getAsJsonArray("properties");
            for (JsonElement el : props) {
                JsonObject o = el.getAsJsonObject();
                if ("textures".equals(o.get("name").getAsString())) {
                    return new String[]{o.get("value").getAsString(), o.has("signature") ? o.get("signature").getAsString() : null};
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static JsonObject getJson(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(4000);
        if (c.getResponseCode() != 200) return null;
        try (Reader r = new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8)) {
            return new JsonParser().parse(r).getAsJsonObject();
        }
    }

    // ---- tracking ----

    private void track() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (CoreUtil.isBot(p)) continue;
            Long at = eligibleAt.get(p.getUniqueId());
            boolean eligible = at == null || now >= at;
            Location pl = p.getLocation();
            for (final Live l : live) {
                boolean shown = l.viewers.contains(p.getUniqueId());
                boolean inRange = eligible && pl.getWorld().equals(l.world()) && pl.distanceSquared(l.npc.location()) < VIEW_RANGE * VIEW_RANGE;
                if (inRange && !shown) {
                    l.npc.show(p);
                    l.holo.show(p);
                    l.viewers.add(p.getUniqueId());
                    final UUID id = p.getUniqueId();
                    Bukkit.getScheduler().runTaskLater(core.plugin, new Runnable() {
                        @Override public void run() {
                            Player pp = Bukkit.getPlayer(id);
                            if (pp != null) l.npc.removeFromTab(pp);
                        }
                    }, 50L);
                } else if (!inRange && shown) {
                    if (pl.getWorld().equals(l.world())) { l.npc.hide(p); l.holo.hide(p); }
                    l.viewers.remove(p.getUniqueId());
                }
            }
        }
    }

    /** The client forgot every entity (new world, respawn, long teleport): resend after the chunks arrive. */
    private void reset(Player p) {
        for (Live l : live) l.viewers.remove(p.getUniqueId());
        eligibleAt.put(p.getUniqueId(), System.currentTimeMillis() + 1500);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) { reset(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) { reset(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) { reset(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getTo() == null || e.getFrom().getWorld() != e.getTo().getWorld()) return;
        if (e.getFrom().distanceSquared(e.getTo()) > 64 * 64) {
            final Player p = e.getPlayer();
            for (Live l : live) if (l.viewers.remove(p.getUniqueId())) { l.npc.hide(p); l.holo.hide(p); }
            eligibleAt.put(p.getUniqueId(), System.currentTimeMillis() + 1000);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        for (Live l : live) l.viewers.remove(id);
        eligibleAt.remove(id);
        lastClick.remove(id);
    }

    // ---- counts ----

    private String countLine(Spec s) {
        int n = s.mode != null ? core.counts.count(s.mode) : core.counts.count(s.target);
        return ChatColor.YELLOW + HypixelFormat.commas(n) + " Players";
    }

    private void updateCounts() {
        for (Live l : live) {
            List<Player> viewers = new ArrayList<>();
            for (UUID id : l.viewers) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) viewers.add(p);
            }
            l.holo.setLine(2, countLine(l.spec), viewers);
        }
    }

    // ---- clicks (Netty thread for humans, main thread for bots) ----

    @Override
    public boolean onInbound(final Player player, Packet<?> packet) {
        if (!(packet instanceof PacketPlayInUseEntity)) return true;
        int id;
        try {
            if (useEntityId == null) {
                Field f = PacketPlayInUseEntity.class.getDeclaredField("a");
                f.setAccessible(true);
                useEntityId = f;
            }
            id = useEntityId.getInt(packet);
        } catch (ReflectiveOperationException e) {
            return true;
        }
        final Live l = byEntityId.get(id);
        if (l == null) return true;
        long now = System.currentTimeMillis();
        Long last = lastClick.get(player.getUniqueId());
        if (last != null && now - last < 600) return false;
        lastClick.put(player.getUniqueId(), now);
        Runnable r = new Runnable() {
            @Override public void run() {
                if (!player.isOnline()) return;
                if (l.spec.mode != null) core.games.queue(player, l.spec.mode);
                else core.lobby.connectFamily(player, l.spec.target);
            }
        };
        // always defer: bots dispatch on the main thread from inside their packet loop, and queue()/teleport must
        // not run re-entrantly in the middle of that
        Bukkit.getScheduler().runTask(core.plugin, r);
        return false; // fake entity: never let the server see it
    }
}
