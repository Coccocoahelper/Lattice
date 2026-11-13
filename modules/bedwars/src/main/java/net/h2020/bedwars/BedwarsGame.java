package net.h2020.bedwars;

import net.h2020.api.stats.CombatStats;
import net.h2020.api.H2020;
import net.h2020.api.bot.BotService;
import net.h2020.api.core.ProfileService;
import net.h2020.api.core.Rank;
import net.h2020.api.core.ScoreboardService;
import net.h2020.api.core.SidebarTemplate;
import net.h2020.api.core.Sidebars;
import net.h2020.api.game.BedwarsShopItem;
import net.h2020.api.game.BedwarsView;
import net.h2020.api.game.GameFamily;
import net.h2020.api.game.GameManager;
import net.h2020.api.game.GameState;
import net.h2020.api.game.GameTeam;
import net.h2020.api.game.GameMode;
import net.h2020.api.game.GameType;
import net.h2020.api.game.TeamColor;
import net.h2020.api.game.events.GameKillEvent;
import net.h2020.api.game.events.GameStateChangeEvent;
import net.h2020.api.map.MapKeys;
import net.h2020.api.map.MapService;
import net.h2020.api.map.MapTemplate;
import net.h2020.bedwars.shop.ItemShop;
import net.h2020.bedwars.shop.ShopCatalog;
import net.h2020.bedwars.shop.Trap;
import net.h2020.bedwars.shop.UpgradeShop;
import net.h2020.bedwars.shop.Upgrade;
import net.h2020.bedwars.util.Items;
import net.h2020.bedwars.util.Text;
import net.h2020.bedwars.world.BelowNameHealth;
import net.h2020.bedwars.world.Generator;
import net.h2020.bedwars.world.ShopNpc;
import net.h2020.bedwars.world.Region;
import net.h2020.bedwars.world.TeamForge;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityEquipment;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Difficulty;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.v1_8_R3.inventory.CraftItemStack;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/** One Bed Wars match (summer 2020 rules) running in its own world instance. */
public final class BedwarsGame implements BedwarsView {
    static final long COMBAT_TAG_MS = 10000;

    /** 2020 event sequence, seconds after the game started. */
    private static final int[] EVENT_TIMES = {6 * 60, 12 * 60, 18 * 60, 24 * 60, 30 * 60, 40 * 60, 50 * 60};
    private static final String[] EVENT_NAMES = {"Diamond II", "Emerald II", "Diamond III", "Emerald III",
            "Bed gone", "Sudden Death", "Game End"};

    final BedwarsModule module;
    final BedwarsSettings settings;
    final GameMode type;
    private String privateCode;
    /** "PUNCH TO / DEPOSIT" stands above each team chest, removed with the game. */
    private final java.util.List<net.h2020.bedwars.world.Hologram> chestLabels = new java.util.ArrayList<>();
    private int privateCap;
    final MapTemplate map;
    final String id;
    final World world;
    private final String date;

    GameState state;
    long tick;
    private long playStartTick = -1;
    private int countdown = -1;
    private int nextEvent;
    private long endAt = -1;
    private BwTeam winner;
    private boolean tornDown;

    final Map<UUID, BwPlayer> players = new LinkedHashMap<>();
    final List<BwTeam> teams = new ArrayList<>();
    private final Map<TeamColor, BwTeam> byColor = new EnumMap<>(TeamColor.class);
    final Set<Long> placed = new HashSet<>();
    private final List<Generator> generators = new ArrayList<>();
    private final List<Location> diamondLocs = new ArrayList<>(), emeraldLocs = new ArrayList<>();
    final List<ShopNpc> npcs = new ArrayList<>();
    final Gadgets gadgets;
    final Dragons dragons;
    private final BelowNameHealth health = new BelowNameHealth();

    Location waitingLobby, spectatorSpawn, mid;

    BedwarsGame(BedwarsModule module, GameMode type, MapTemplate map, String id) {
        this.module = module;
        this.settings = module.settings();
        this.type = type;
        this.map = map;
        this.id = id;
        // the fake 2020 scoreboard date (core.date, settable with /h2020 date), not the real one
        this.date = fakeDate(module.plugin());
        this.world = map.createInstance("bw_" + id + "_" + Long.toString(System.currentTimeMillis() % 100000L, 36));
        if (world == null) throw new IllegalStateException("Map " + map.name() + " failed to create an instance");
        setupWorld();
        loadPoints();
        gadgets = new Gadgets(this);
        dragons = new Dragons(this);
        state = GameState.WAITING;
        Bukkit.getPluginManager().callEvent(new GameStateChangeEvent(this, null, GameState.WAITING));
    }

    // =================================================================== setup

    private void setupWorld() {
        world.setDifficulty(Difficulty.NORMAL);
        world.setSpawnFlags(false, false);
        world.setPVP(true);
        world.setAutoSave(false);
        world.setStorm(false);
        world.setThundering(false);
        world.setGameRuleValue("doMobSpawning", "false");
        world.setGameRuleValue("doDaylightCycle", "false");
        world.setGameRuleValue("doFireTick", "false");
        world.setGameRuleValue("mobGriefing", "true");
        world.setGameRuleValue("naturalRegeneration", "true");
        world.setGameRuleValue("keepInventory", "false");
        world.setGameRuleValue("showDeathMessages", "false");
        world.setGameRuleValue("doTileDrops", "true");
        world.setGameRuleValue("doMobLoot", "false");
        world.setTime(6000);
    }

    private List<Vector> pts(String key) {
        List<Vector> l = map.points(key);
        return l == null ? Collections.<Vector>emptyList() : l;
    }

    private Location point(String key, boolean center) {
        List<Vector> pts = pts(key);
        if (pts == null || pts.isEmpty()) return null;
        return toLoc(pts.get(0), map.yaw(key, 0), center);
    }

    private Location toLoc(Vector v, float yaw, boolean center) {
        double x = v.getX(), z = v.getZ();
        if (center) {
            if (x == Math.floor(x)) x += 0.5;
            if (z == Math.floor(z)) z += 0.5;
        }
        return new Location(world, x, v.getY(), z, yaw, 0);
    }

    private void loadPoints() {
        mid = point(MapKeys.MID, true);
        if (mid == null) mid = world.getSpawnLocation();
        waitingLobby = point(MapKeys.WAITING_LOBBY, true);
        if (waitingLobby == null) waitingLobby = mid.clone().add(0, 40, 0);
        spectatorSpawn = point(MapKeys.SPECTATOR, true);
        if (spectatorSpawn == null) spectatorSpawn = mid.clone().add(0, 20, 0);
        spectatorSpawn = safeSpawn(spectatorSpawn);

        for (Vector v : pts(MapKeys.DIAMOND_GEN)) diamondLocs.add(toLoc(v, 0, true));
        for (Vector v : pts(MapKeys.EMERALD_GEN)) emeraldLocs.add(toLoc(v, 0, true));

        TeamColor[] colors = TeamColor.values();
        for (int i = 0; i < type.teams() && i < colors.length; i++) {
            TeamColor c = colors[i];
            BwTeam t = new BwTeam(this, c);
            t.spawn = point(MapKeys.teamSpawn(c), true);
            if (t.spawn == null) {
                module.plugin().getLogger().warning("[bedwars] map " + map.name() + " has no spawn for team " + c);
                continue;
            }
            List<Vector> bed = pts(MapKeys.teamBed(c));
            if (bed.size() >= 1) t.bedFoot = toLoc(bed.get(0), 0, false);
            if (bed.size() >= 2) t.bedHead = toLoc(bed.get(1), 0, false);
            t.generator = point(MapKeys.teamGenerator(c), true);
            if (t.generator == null) t.generator = t.spawn.clone();
            // NPCs stand exactly where map.yml says: a whole number puts them on a block edge, e.g. centred in a
            // 2-long pen
            t.shop = point(MapKeys.teamShop(c), false);
            t.upgradesNpc = point(MapKeys.teamUpgrades(c), false);
            List<Vector> base = pts(MapKeys.teamBase(c));
            if (base.size() >= 2) {
                Vector a = base.get(0), b = base.get(1);
                t.baseMin = new Location(world, Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
                t.baseMax = new Location(world, Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
            }
            t.chest = findTeamChest(t);
            labelChests(t);
            teams.add(t);
            byColor.put(c, t);
        }
    }

    /** Two grey lines over a chest: PUNCH TO on top, DEPOSIT on the row below. */
    private void labelChest(Location chest) {
        if (chest == null) return;
        Location at = chest.clone().add(0.5, 1.55, 0.5);
        chestLabels.add(new net.h2020.bedwars.world.Hologram(at.clone().add(0, 0.27, 0), org.bukkit.ChatColor.GRAY + "PUNCH TO"));
        chestLabels.add(new net.h2020.bedwars.world.Hologram(at, org.bukkit.ChatColor.GRAY + "DEPOSIT"));
    }

    /** The team chest and any ender chest on the island get the label; nothing is placed. */
    private void labelChests(BwTeam t) {
        labelChest(t.chest);
        for (Location e : findEnderChests(t)) labelChest(e);
    }

    /**
     * Some maps put the spectator point in the air over the void. Teleporting there drops the player and, for a bot
     * that respawns into a loop, counts as an unassisted void death every few seconds. Land it on the nearest floor
     * within a few blocks, or put a small glass platform under it so spectators have something to stand on.
     */
    private Location safeSpawn(Location at) {
        if (at == null) return null;
        int land = Integer.MIN_VALUE;
        for (int y = at.getBlockY(); y >= at.getBlockY() - 8; y--) {
            if (world.getBlockAt(at.getBlockX(), y, at.getBlockZ()).getType().isSolid()) { land = y; break; }
        }
        if (land != Integer.MIN_VALUE) {
            Location out = at.clone();
            out.setY(land + 1);
            return out;
        }
        // nothing solid below: give the spectators a 3x3 glass perch
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Block b = world.getBlockAt(at.getBlockX() + dx, at.getBlockY() - 1, at.getBlockZ() + dz);
                if (b.getType() == Material.AIR) b.setType(Material.GLASS);
            }
        }
        return at;
    }

    /** The chest the map already has on this island. Nothing is placed: maps carry their own chests. */
    private Location findTeamChest(BwTeam t) {
        if (t.baseMin != null && t.baseMax != null) {
            int x1 = (int) Math.floor(Math.min(t.baseMin.getX(), t.baseMax.getX())) - 2;
            int x2 = (int) Math.ceil(Math.max(t.baseMin.getX(), t.baseMax.getX())) + 2;
            int z1 = (int) Math.floor(Math.min(t.baseMin.getZ(), t.baseMax.getZ())) - 2;
            int z2 = (int) Math.ceil(Math.max(t.baseMin.getZ(), t.baseMax.getZ())) + 2;
            int y1 = (int) Math.floor(Math.min(t.baseMin.getY(), t.baseMax.getY()));
            int y2 = (int) Math.ceil(Math.max(t.baseMin.getY(), t.baseMax.getY()));
            Location found = scanChest(x1, y1, z1, x2, y2, z2);
            if (found != null) return found;
        }
        // no base box on this map: look around the bed, then the spawn
        Location c = t.bedFoot != null ? t.bedFoot : t.spawn;
        if (c != null) return scanChest(c.getBlockX() - 14, c.getBlockY() - 8, c.getBlockZ() - 14,
                c.getBlockX() + 14, c.getBlockY() + 8, c.getBlockZ() + 14);
        return null;
    }

    /** Ender chests already standing on this island (up to three), for the deposit label. */
    private java.util.List<Location> findEnderChests(BwTeam t) {
        java.util.List<Location> out = new java.util.ArrayList<>();
        Location c = t.bedFoot != null ? t.bedFoot : t.spawn;
        if (c == null) return out;
        int r = 14;
        for (int y = c.getBlockY() - 6; y <= c.getBlockY() + 6; y++) {
            for (int x = c.getBlockX() - r; x <= c.getBlockX() + r; x++) {
                for (int z = c.getBlockZ() - r; z <= c.getBlockZ() + r; z++) {
                    if (world.getBlockAt(x, y, z).getType() == Material.ENDER_CHEST) {
                        out.add(new Location(world, x, y, z));
                        if (out.size() >= 3) return out;
                    }
                }
            }
        }
        return out;
    }

    private Location scanChest(int x1, int y1, int z1, int x2, int y2, int z2) {
        for (int y = Math.max(0, y1); y <= Math.min(255, y2); y++) {
            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getType() == Material.CHEST) return b.getLocation();
                }
            }
        }
        return null;
    }

    /** Makes sure both bed halves exist (template worlds normally contain them already). */
    @SuppressWarnings("deprecation")
    private void ensureBed(BwTeam t) {
        if (t.bedFoot == null) return;
        Block foot = t.bedFoot.getBlock();
        if (foot.getType() == Material.BED_BLOCK) {
            if (t.bedHead == null) {
                for (Block n : new Block[]{foot.getRelative(1, 0, 0), foot.getRelative(-1, 0, 0),
                        foot.getRelative(0, 0, 1), foot.getRelative(0, 0, -1)}) {
                    if (n.getType() == Material.BED_BLOCK) { t.bedHead = n.getLocation(); break; }
                }
            }
            return;
        }
        if (t.bedHead == null) t.bedHead = t.bedFoot.clone().add(0, 0, 1);
        Block head = t.bedHead.getBlock();
        int dx = head.getX() - foot.getX(), dz = head.getZ() - foot.getZ();
        byte dir = (byte) (dz > 0 ? 0 : dx < 0 ? 1 : dz < 0 ? 2 : 3);
        foot.setTypeIdAndData(Material.BED_BLOCK.getId(), dir, false);
        head.setTypeIdAndData(Material.BED_BLOCK.getId(), (byte) (dir | 8), false);
    }

    // =================================================================== Game / BedwarsView

    @Override public String id() { return id; }
    @Override public GameMode type() { return type; }
    @Override public String privateCode() { return privateCode; }
    @Override public void makePrivate(String code, int maxPlayers) { privateCode = code; privateCap = Math.max(0, maxPlayers); }
    @Override public int playerCap() { return privateCap > 0 ? privateCap : type.maxPlayers(); }
    @Override public GameState state() { return state; }
    @Override public World world() { return world; }
    @Override public String mapName() { return map.name(); }

    @Override
    public List<Player> players() {
        List<Player> out = new ArrayList<>();
        for (BwPlayer bp : players.values()) out.add(bp.player);
        return out;
    }

    @Override
    public List<Player> alivePlayers() {
        List<Player> out = new ArrayList<>();
        for (BwPlayer bp : players.values()) {
            if (state == GameState.WAITING || state == GameState.STARTING || (bp.team != null && !bp.eliminated)) out.add(bp.player);
        }
        return out;
    }

    @Override public List<? extends GameTeam> teams() { return Collections.unmodifiableList(teams); }

    @Override
    public GameTeam teamOf(Player p) {
        BwPlayer bp = data(p);
        return bp == null ? null : bp.team;
    }

    @Override
    public boolean isSpectator(Player p) {
        BwPlayer bp = data(p);
        return bp != null && (bp.eliminated || (state == GameState.PLAYING && bp.team == null));
    }

    @Override public int secondsUntilStart() { return state == GameState.STARTING ? countdown : -1; }
    @Override public Location spectatorSpawn() { return spectatorSpawn.clone(); }

    @Override public boolean bedAlive(TeamColor team) { BwTeam t = byColor.get(team); return t != null && t.bedAlive; }
    @Override public Location bedLocation(TeamColor team) { BwTeam t = byColor.get(team); return t == null || t.bedFoot == null ? null : t.bedFoot.clone(); }
    @Override public Location teamSpawn(TeamColor team) { BwTeam t = byColor.get(team); return t == null ? null : t.spawn.clone(); }
    @Override public Location teamGenerator(TeamColor team) { BwTeam t = byColor.get(team); return t == null ? null : t.generator.clone(); }
    @Override public Location shopNpc(TeamColor team) { BwTeam t = byColor.get(team); return t == null || t.shop == null ? null : t.shop.clone(); }
    @Override public Location upgradesNpc(TeamColor team) { BwTeam t = byColor.get(team); return t == null || t.upgradesNpc == null ? null : t.upgradesNpc.clone(); }

    @Override
    public List<Location> diamondGenerators() {
        List<Location> out = new ArrayList<>();
        for (Location l : diamondLocs) out.add(l.clone());
        return out;
    }

    @Override
    public List<Location> emeraldGenerators() {
        List<Location> out = new ArrayList<>();
        for (Location l : emeraldLocs) out.add(l.clone());
        return out;
    }

    @Override public Location mid() { return mid.clone(); }

    @Override
    public boolean isRespawning(Player p) {
        BwPlayer bp = data(p);
        return bp != null && bp.respawning();
    }

    /** -Dh2020.shopdebug=true logs every API purchase (bots shop through these) to the console. */
    private static final boolean SHOP_DEBUG = Boolean.getBoolean("h2020.shopdebug");

    @Override
    public boolean purchase(Player p, BedwarsShopItem item) {
        boolean ok = state == GameState.PLAYING && ItemShop.purchase(this, p, item);
        if (SHOP_DEBUG && ok) Bukkit.getLogger().info("[shopdebug] " + id + " " + p.getName() + " bought " + item);
        return ok;
    }

    @Override
    public boolean canAfford(Player p, BedwarsShopItem item) {
        return state == GameState.PLAYING && ItemShop.canAfford(this, p, item);
    }

    @Override
    public boolean purchaseUpgrade(Player p, String upgradeId) {
        boolean ok = state == GameState.PLAYING && UpgradeShop.purchase(this, p, upgradeId);
        if (SHOP_DEBUG && ok) Bukkit.getLogger().info("[shopdebug] " + id + " " + p.getName() + " upgraded " + upgradeId);
        return ok;
    }

    @Override
    public int upgradeLevel(TeamColor team, String upgradeId) {
        BwTeam t = byColor.get(team);
        if (t == null) return 0;
        Upgrade u = Upgrade.byId(upgradeId);
        if (u != null) return t.upgrades.level(u);
        Trap trap = Trap.byId(upgradeId);
        return trap == null ? 0 : t.upgrades.count(trap);
    }

    @Override
    public String nextEventName() {
        return nextEvent < EVENT_NAMES.length ? EVENT_NAMES[nextEvent] : "Game End";
    }

    @Override
    public int nextEventSeconds() {
        if (state != GameState.PLAYING || nextEvent >= EVENT_TIMES.length) return 0;
        return Math.max(0, eventTime(nextEvent) - elapsedSeconds());
    }

    private int eventTime(int i) { return (int) Math.round(EVENT_TIMES[i] * settings.efTimelineScale()); }

    private int elapsedSeconds() { return playStartTick < 0 ? 0 : (int) ((tick - playStartTick) / 20); }

    // =================================================================== helpers used across the module

    public BwPlayer data(Player p) { return p == null ? null : players.get(p.getUniqueId()); }
    public BwTeam team(TeamColor c) { return byColor.get(c); }
    public List<BwTeam> teamList() { return teams; }
    public boolean bigMode() { return type.teamSize() >= 3; }
    public boolean soloMode() { return type.teamSize() == 1; }
    public BedwarsModule module() { return module; }
    public long currentTick() { return tick; }

    static long key(Block b) { return key(b.getX(), b.getY(), b.getZ()); }
    static long key(int x, int y, int z) { return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF); }

    public boolean isPlaced(Block b) { return placed.contains(key(b)); }

    /** Map coordinates version for the bot AI (BedwarsView). */
    @Override
    public boolean isPlaced(Location at) {
        return at != null && at.getWorld() == world && placed.contains(key(at.getBlockX(), at.getBlockY(), at.getBlockZ()));
    }
    public void markPlaced(Block b) { placed.add(key(b)); }
    public void unmarkPlaced(Block b) { placed.remove(key(b)); }

    public void broadcast(String msg) {
        for (BwPlayer bp : players.values()) bp.player.sendMessage(msg);
        // mirror game messages (kills, beds, eliminations) to the console like a real server's chat log
        if (msg != null && !org.bukkit.ChatColor.stripColor(msg).trim().isEmpty())
            Bukkit.getLogger().info("[" + id + "] " + org.bukkit.ChatColor.stripColor(msg));
    }

    /**
     * Kill feed for the living only: once you are a spectator the fight is over, and Hypixel does not keep narrating
     * other people's kills to you.
     */
    public void broadcastAlive(String msg) {
        // everyone still in the game, including somebody waiting out their respawn; eliminated players are out
        for (BwPlayer bp : players.values()) {
            if (!bp.eliminated) bp.player.sendMessage(msg);
            else net.h2020.api.stats.CombatStats.inc("bedwars.feed.skipped");
        }
        if (msg != null && !org.bukkit.ChatColor.stripColor(msg).trim().isEmpty())
            Bukkit.getLogger().info("[" + id + "] " + org.bukkit.ChatColor.stripColor(msg));
    }

    public void broadcastTeam(BwTeam t, String msg) {
        for (Player p : t.members) if (p.isOnline()) p.sendMessage(msg);
    }

    public void sound(Sound s, float volume, float pitch) {
        for (BwPlayer bp : players.values()) bp.player.playSound(bp.player.getLocation(), s, volume, pitch);
    }

    /** Name as seen in kill feed: team color, no rank. */
    String feedName(Player p) {
        BwPlayer bp = data(p);
        ChatColor c = bp != null && bp.team != null ? bp.team.color.chat : ChatColor.GRAY;
        return c + p.getName();
    }

    static String rankName(Player p) {
        ProfileService ps = H2020.find(ProfileService.class);
        if (ps != null) {
            try { return ps.displayName(p); } catch (Throwable ignored) { }
        }
        return ChatColor.GRAY + p.getName();
    }

    static ChatColor rankColor(Player p) {
        ProfileService ps = H2020.find(ProfileService.class);
        if (ps != null) {
            try {
                Rank r = ps.rank(p);
                if (r != null) return r.color;
            } catch (Throwable ignored) { }
        }
        return ChatColor.GRAY;
    }

    static boolean hasRank(Player p) {
        ProfileService ps = H2020.find(ProfileService.class);
        if (ps == null) return false;
        try { Rank r = ps.rank(p); return r != null && r != Rank.NONE; } catch (Throwable t) { return false; }
    }

    static boolean isBot(Player p) {
        BotService bs = H2020.find(BotService.class);
        return bs != null && bs.isBot(p);
    }

    private void setState(GameState to) {
        GameState from = state;
        if (from == to) return;
        state = to;
        Bukkit.getPluginManager().callEvent(new GameStateChangeEvent(this, from, to));
    }

    static void resetPlayer(Player p, org.bukkit.GameMode mode) {
        p.closeInventory();
        p.setGameMode(mode);
        p.setAllowFlight(false);
        p.setFlying(false);
        p.setMaxHealth(20);
        p.setHealth(20);
        p.setFoodLevel(20);
        p.setSaturation(5);
        p.setExhaustion(0);
        p.setExp(0);
        p.setLevel(0);
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.setItemOnCursor(null);
        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        p.setVelocity(new Vector(0, 0, 0));
    }

    // =================================================================== joining / leaving

    @Override
    public boolean addPlayer(Player p) {
        if (tornDown) return false;
        if (players.containsKey(p.getUniqueId())) return true;
        if (state != GameState.WAITING && state != GameState.STARTING) return false;
        if (players.size() >= playerCap()) return false;
        module.leaveOtherGames(p, this);
        BwPlayer bp = new BwPlayer(p);
        players.put(p.getUniqueId(), bp);
        module.index(p, this);
        resetPlayer(p, org.bukkit.GameMode.ADVENTURE);
        p.teleport(waitingLobby);
        giveLobbyItems(p);
        ScoreboardService sb = H2020.find(ScoreboardService.class);
        if (sb != null) sb.resetNametag(p);
        for (BwPlayer o : players.values()) {
            o.player.showPlayer(p);
            p.showPlayer(o.player);
        }
        broadcast(rankColor(p) + p.getName() + ChatColor.YELLOW + " has joined (" + ChatColor.AQUA + players.size()
                + ChatColor.YELLOW + "/" + ChatColor.AQUA + playerCap() + ChatColor.YELLOW + ")!");
        updateWaitingBoards();
        return true;
    }

    private void giveLobbyItems(Player p) {
        p.getInventory().setItem(8, Items.of(Material.BED).name(ChatColor.RED + "" + ChatColor.BOLD + "Return to Lobby"
                + ChatColor.GRAY + " (Right Click)").lore(ChatColor.GRAY + "Right-click to leave to the lobby!").build());
    }

    @Override
    public void removePlayer(Player p) {
        removePlayer(p, false);
    }

    /** @param silent true for Watchdog removals (no "disconnected" line, the ban message is shown instead). */
    void removePlayer(Player p, boolean silent) {
        BwPlayer bp = players.get(p.getUniqueId());
        if (bp == null) return;
        if (state == GameState.WAITING || state == GameState.STARTING) {
            players.remove(p.getUniqueId());
            broadcast(rankColor(p) + p.getName() + ChatColor.YELLOW + " has quit!");
        } else if (state == GameState.PLAYING) {
            boolean wasIn = bp.team != null && !bp.eliminated;
            players.remove(p.getUniqueId());
            if (wasIn) {
                broadcast(feedName(p) + ChatColor.GRAY + " disconnected.");
                bp.eliminated = true;
                bp.respawnAt = -1;
            }
            if (bp.team != null) {
                bp.team.members.remove(p);
                checkTeamElimination(bp.team);
            }
        } else {
            players.remove(p.getUniqueId());
        }
        module.unindex(p, this);
        cleanupPlayer(p);
        if (state == GameState.WAITING || state == GameState.STARTING) updateWaitingBoards();
        else if (state == GameState.PLAYING) checkWin();
    }

    /** Undo everything this game did to a player that is leaving. */
    private void cleanupPlayer(Player p) {
        health.removeViewer(p);
        for (BwPlayer o : players.values()) {
            o.player.showPlayer(p);
            p.showPlayer(o.player);
        }
        ScoreboardService sb = H2020.find(ScoreboardService.class);
        if (sb != null && p.isOnline()) {
            try {
                sb.clearSidebar(p);
                sb.resetNametag(p);
            } catch (Throwable ignored) { }
        }
        if (p.isOnline()) {
            p.getEnderChest().clear();
            for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
            p.setAllowFlight(false);
            p.setFlying(false);
        }
    }

    /** Return-to-lobby item / Play Again: leave and hand the player back to core. */
    void leaveToLobby(Player p, boolean playAgain) {
        removePlayer(p);
        GameManager gm = H2020.find(GameManager.class);
        if (gm != null) {
            if (playAgain) gm.queue(p, type);
            else gm.sendToLobby(p, GameFamily.BEDWARS);
        } else {
            resetPlayer(p, org.bukkit.GameMode.ADVENTURE);
            p.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
        }
    }

    // =================================================================== tick

    void tick() {
        tick++;
        try {
            switch (state) {
                case WAITING:
                case STARTING:
                    if (tick % 20 == 0) waitingSecond();
                    // waitingSecond() may have just started the game (players are now on their islands, far below
                    // the waiting box): don't treat them as having fallen out of it
                    if (state != GameState.WAITING && state != GameState.STARTING) break;
                    if (tick % 10 == 0) {
                        for (BwPlayer bp : players.values()) {
                            if (bp.player.getLocation().getY() < waitingLobby.getY() - 25 || bp.player.getWorld() != world) {
                                bp.player.teleport(waitingLobby);
                            }
                        }
                    }
                    break;
                case PLAYING:
                    playingTick();
                    break;
                case ENDING:
                    endingTick();
                    break;
            }
        } catch (Throwable t) {
            module.plugin().getLogger().log(Level.SEVERE, "[bedwars] tick error in game " + id, t);
        }
    }

    private void waitingSecond() {
        int n = players.size(), min = settings.minPlayers(type);
        if (state == GameState.WAITING) {
            if (n >= min && n > 0) {
                setState(GameState.STARTING);
                countdown = n >= playerCap() ? Math.min(settings.efCountdown(), settings.efFullCountdown()) : settings.efCountdown();
                announceCountdown();
            }
        } else {
            if (n < min) {
                setState(GameState.WAITING);
                countdown = -1;
                broadcast(ChatColor.RED + "We don't have enough players! Start cancelled.");
                for (BwPlayer bp : players.values()) {
                    Text.title(bp.player, ChatColor.RED + "Waiting for more players...", "", 0, 40, 10);
                    bp.player.playSound(bp.player.getLocation(), Sound.NOTE_STICKS, 1f, 1f);
                }
            } else {
                if (n >= playerCap() && countdown > settings.efFullCountdown()) countdown = settings.efFullCountdown() + 1;
                countdown--;
                if (countdown <= 0) {
                    start();
                    return;
                }
                announceCountdown();
            }
        }
        updateWaitingBoards();
    }

    private void announceCountdown() {
        int c = countdown;
        if (c == 20 || c == 15 || c == 10 || c <= 5) {
            ChatColor col = c > 10 ? ChatColor.YELLOW : c > 5 ? ChatColor.GOLD : ChatColor.RED;
            broadcast(ChatColor.YELLOW + "The game starts in " + col + c + ChatColor.YELLOW + (c == 1 ? " second!" : " seconds!"));
            for (BwPlayer bp : players.values()) bp.player.playSound(bp.player.getLocation(), Sound.NOTE_STICKS, 1f, 1f);
        }
        if (c == 10 || c <= 5) {
            ChatColor col = c == 10 ? ChatColor.GREEN : c > 3 ? ChatColor.YELLOW : ChatColor.RED;
            for (BwPlayer bp : players.values()) Text.title(bp.player, col + "" + c, "", 0, 25, 5);
        }
    }

    private void updateWaitingBoards() {
        ScoreboardService sb = H2020.find(ScoreboardService.class);
        if (sb == null) return;
        SidebarTemplate template = Sidebars.get("bedwars-waiting");
        if (template != null) {
            Map<String, String> vars = new HashMap<String, String>();
            vars.put("date", date);
            vars.put("id", id);
            vars.put("map", map.name());
            vars.put("players", String.valueOf(players.size()));
            vars.put("max", String.valueOf(type.maxPlayers()));
            vars.put("countdown", String.valueOf(countdown));
            vars.put("mode", type.displayName());
            Set<String> flags = new HashSet<String>();
            flags.add("waiting");
            flags.add(state == GameState.STARTING ? "starting" : "idle");
            flags.add(soloMode() ? "solo" : "teams");
            for (BwPlayer bp : players.values()) {
                try {
                    sb.setSidebar(bp.player, template.renderTitle(bp.player, vars),
                            template.render(bp.player, vars, flags, null));
                } catch (Throwable ignored) { }
            }
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add(ChatColor.GRAY + date + "  " + ChatColor.DARK_GRAY + id);
        lines.add(blank(1));
        lines.add(ChatColor.WHITE + "Map: " + ChatColor.GREEN + map.name());
        lines.add(ChatColor.WHITE + "Players: " + ChatColor.GREEN + players.size() + "/" + type.maxPlayers());
        lines.add(blank(2));
        lines.add(state == GameState.STARTING ? ChatColor.WHITE + "Starting in " + ChatColor.GREEN + countdown + "s"
                : ChatColor.WHITE + "Waiting...");
        lines.add(blank(3));
        lines.add(ChatColor.WHITE + "Mode: " + ChatColor.GREEN + type.displayName());
        lines.add(ChatColor.WHITE + "Version: " + ChatColor.GRAY + "v1.6");
        lines.add(blank(4));
        lines.add(ChatColor.YELLOW + "www.hypixel.net");
        String title = ChatColor.YELLOW + "" + ChatColor.BOLD + "BED WARS";
        for (BwPlayer bp : players.values()) {
            try { sb.setSidebar(bp.player, title, lines); } catch (Throwable ignored) { }
        }
    }

    /** Distinct invisible blank lines (sidebars can't contain duplicate entries). */
    private static String blank(int i) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < i; k++) sb.append(ChatColor.RESET);
        return sb.toString();
    }

    // =================================================================== start

    private void start() {
        countdown = -1;
        setState(GameState.PLAYING);
        playStartTick = tick;
        assignTeams();
        ScoreboardService sb = H2020.find(ScoreboardService.class);
        for (BwTeam t : teams) {
            ensureBed(t);
            t.participated = !t.members.isEmpty();
            t.forge = new TeamForge(module.plugin(), t.color.name(), t.generator, bigMode());
            if (!t.participated) {
                // empty islands lose their bed straight away (scoreboard shows ✘)
                t.bedAlive = false;
                t.eliminationAnnounced = true;
                breakBedBlocks(t);
            }
            if (t.shop != null) npcs.add(new ShopNpc(ShopNpc.Kind.ITEM_SHOP, t.shop, ChatColor.AQUA + "ITEM SHOP"));
            if (t.upgradesNpc != null) npcs.add(new ShopNpc(ShopNpc.Kind.UPGRADES, t.upgradesNpc,
                    ChatColor.AQUA + (soloMode() ? "SOLO UPGRADES" : "TEAM UPGRADES")));
        }
        for (Location l : diamondLocs) generators.add(new Generator(Generator.Kind.DIAMOND, l, bigMode()));
        for (Location l : emeraldLocs) generators.add(new Generator(Generator.Kind.EMERALD, l, bigMode()));
        for (Generator g : generators) g.spawnDisplay();

        List<Player> all = players();
        for (BwPlayer bp : players.values()) {
            Player p = bp.player;
            resetPlayer(p, org.bukkit.GameMode.SURVIVAL);
            p.getEnderChest().clear();
            if (bp.team == null) { // should not happen, but never leave someone stranded in the lobby box
                makeSpectator(bp);
                bp.eliminated = true;
                continue;
            }
            if (!p.teleport(bp.team.spawn))
                module.plugin().getLogger().warning("[bedwars] start teleport refused for " + p.getName() + " in " + id);
            giveKit(bp);
            if (sb != null) {
                try { sb.setNametag(p, bp.team.color.chat + bp.team.color.letter() + " ", "", bp.team.color.chat); }
                catch (Throwable ignored) { }
            }
            health.addViewer(p, all);
        }
        sendStartMessage();
        updatePlayingBoards();
    }

    private void assignTeams() {
        List<BwPlayer> list = new ArrayList<>(players.values());
        if ("bedwars_duel".equals(type.id())) {
            // a duel is RED against AQUA, and the first player to join takes RED: no shuffle, no third team
            java.util.List<BwTeam> duel = new ArrayList<>();
            for (BwTeam t : teams) if (t.color == net.h2020.api.game.TeamColor.RED
                    || t.color == net.h2020.api.game.TeamColor.AQUA) duel.add(t);
            int i = 0;
            for (BwPlayer bp : list) {
                if (i >= duel.size()) break;
                BwTeam t = duel.get(i++);
                bp.team = t;
                t.members.add(bp.player);
                org.bukkit.Bukkit.getLogger().info("[duel] " + bp.player.getName() + " -> " + t.color.name());
            }
            return;
        }
        Collections.shuffle(list);
        if (teams.isEmpty()) return;
        int used = Math.min(teams.size(), Math.max(1, (int) Math.ceil(list.size() / (double) type.teamSize())));
        // Spread across used teams evenly (round-robin keeps teams balanced when not full).
        int i = 0;
        for (BwPlayer bp : list) {
            BwTeam t = null;
            for (int tries = 0; tries < used; tries++) {
                BwTeam cand = teams.get((i + tries) % used);
                if (cand.members.size() < type.teamSize()) { t = cand; i = i + tries + 1; break; }
            }
            if (t == null) break;
            bp.team = t;
            t.members.add(bp.player);
        }
    }

    private void sendStartMessage() {
        List<String> lines = new ArrayList<>();
        lines.add(Text.BAR);
        lines.add(Text.center(ChatColor.WHITE + "" + ChatColor.BOLD + "Bed Wars"));
        lines.add("");
        lines.add(Text.center(ChatColor.YELLOW + "" + ChatColor.BOLD + "Protect your bed and destroy the enemy beds."));
        lines.add(Text.center(ChatColor.YELLOW + "" + ChatColor.BOLD + "Upgrade yourself and your team by collecting"));
        lines.add(Text.center(ChatColor.YELLOW + "" + ChatColor.BOLD + "Iron, Gold, Emerald and Diamond from generators"));
        lines.add(Text.center(ChatColor.YELLOW + "" + ChatColor.BOLD + "to access powerful upgrades."));
        lines.add("");
        lines.add(Text.BAR);
        for (BwPlayer bp : players.values()) for (String l : lines) bp.player.sendMessage(l);
    }

    // =================================================================== kit / inventory

    /** Spawn kit: wooden sword, permanent armor + tools, team effects. */
    void giveKit(BwPlayer bp) {
        Player p = bp.player;
        PlayerInventory inv = p.getInventory();
        inv.clear();
        int sharp = bp.team.upgrades.level(Upgrade.SHARPNESS);
        inv.setItem(0, ShopCatalog.sword(Material.WOOD_SWORD, sharp));
        if (bp.pickaxeTier > 0) inv.addItem(ShopCatalog.tool(ShopCatalog.PICKAXES[bp.pickaxeTier - 1], sharp));
        if (bp.axeTier > 0) inv.addItem(ShopCatalog.tool(ShopCatalog.AXES[bp.axeTier - 1], sharp));
        if (bp.shears) inv.addItem(ShopCatalog.give(BedwarsShopItem.SHEARS, bp.team.color, 0));
        applyArmor(bp);
        applyTeamEffects(bp);
    }

    private static final Material[][] ARMOR = {
            {Material.LEATHER_BOOTS, Material.LEATHER_LEGGINGS},
            {Material.CHAINMAIL_BOOTS, Material.CHAINMAIL_LEGGINGS},
            {Material.IRON_BOOTS, Material.IRON_LEGGINGS},
            {Material.DIAMOND_BOOTS, Material.DIAMOND_LEGGINGS}};

    private ItemStack armorPiece(Material m, Color c, int prot) {
        Items b = Items.of(m).unbreakable().color(c);
        if (prot > 0) b.enchant(Enchantment.PROTECTION_ENVIRONMENTAL, prot);
        return b.build();
    }

    /** Re-applies armor if anything differs (tier, protection level, removed pieces). */
    public void applyArmor(BwPlayer bp) {
        int prot = bp.team.upgrades.level(Upgrade.PROTECTION);
        Color c = bp.team.color.armor;
        Material[] lower = ARMOR[Math.max(0, Math.min(3, bp.armorTier))];
        Material[] want = {lower[0], lower[1], Material.LEATHER_CHESTPLATE, Material.LEATHER_HELMET};
        PlayerInventory inv = bp.player.getInventory();
        ItemStack[] cur = inv.getArmorContents();
        boolean changed = false;
        for (int i = 0; i < 4; i++) {
            ItemStack s = cur[i];
            if (s == null || s.getType() != want[i] || s.getEnchantmentLevel(Enchantment.PROTECTION_ENVIRONMENTAL) != prot) {
                cur[i] = armorPiece(want[i], c, prot);
                changed = true;
            }
        }
        if (changed) inv.setArmorContents(cur);
    }

    void applyTeamEffects(BwPlayer bp) {
        int haste = bp.team.upgrades.level(Upgrade.HASTE);
        if (haste > 0) {
            PotionEffect cur = null;
            for (PotionEffect e : bp.player.getActivePotionEffects()) if (e.getType().equals(PotionEffectType.FAST_DIGGING)) cur = e;
            if (cur == null || cur.getAmplifier() != haste - 1 || cur.getDuration() < 200) {
                bp.player.addPotionEffect(new PotionEffect(PotionEffectType.FAST_DIGGING, 20 * 60 * 60, haste - 1, true, false), true);
            }
        }
    }

    /** Keeps the inventory Hypixel-consistent: wooden sword fallback, sharpness on swords/axes, armor. */
    public void normalizeInventory(BwPlayer bp) {
        Player p = bp.player;
        PlayerInventory inv = p.getInventory();
        int sharp = bp.team.upgrades.level(Upgrade.SHARPNESS);
        boolean anySword = false, betterSword = false;
        int woodSlot = -1;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s == null) continue;
            Material m = s.getType();
            if (Items.isSword(m)) {
                anySword = true;
                if (m == Material.WOOD_SWORD) woodSlot = i; else betterSword = true;
            }
            if (sharp > 0 && (Items.isSword(m) || Items.isAxe(m)) && s.getEnchantmentLevel(Enchantment.DAMAGE_ALL) < sharp) {
                s.addUnsafeEnchantment(Enchantment.DAMAGE_ALL, sharp);
                inv.setItem(i, s);
            }
        }
        ItemStack cursor = p.getItemOnCursor();
        if (cursor != null && Items.isSword(cursor.getType())) anySword = true;
        if (!anySword) inv.addItem(ShopCatalog.sword(Material.WOOD_SWORD, sharp));
        else if (betterSword && woodSlot >= 0) inv.setItem(woodSlot, null);
        applyArmor(bp);
    }

    // =================================================================== playing loop

    private void playingTick() {
        for (Generator g : generators) g.animate(tick);
        for (BwTeam t : teams) if (t.forge != null) t.forge.tick();
        gadgets.tick();
        dragons.tick();

        // respawns (tick precise)
        for (BwPlayer bp : new ArrayList<>(players.values())) {
            if (!bp.respawning()) continue;
            if (tick >= bp.respawnAt) respawn(bp);
            else {
                int secs = (int) Math.ceil((bp.respawnAt - tick) / 20.0);
                if (secs != bp.lastRespawnSecond) {
                    bp.lastRespawnSecond = secs;
                    Text.title(bp.player, ChatColor.RED + "" + ChatColor.BOLD + "YOU DIED!", ChatColor.YELLOW
                            + "You will respawn in " + ChatColor.RED + secs + ChatColor.YELLOW + (secs == 1 ? " second!" : " seconds!"), 0, 30, 0);
                }
            }
        }

        if (tick % 2 == 0) {
            List<Player> viewers = players();
            for (ShopNpc n : npcs) n.lookAt(viewers);
        }
        if (tick % 5 == 0) {
            checkTraps();
            invisibilityArmor();
            for (BwPlayer bp : new ArrayList<>(players.values())) {
                if (bp.player.getLocation().getY() < 0) {
                    if (bp.playing()) handleDeath(bp, DamageCause.VOID);
                    else bp.player.teleport(spectatorSpawn);
                }
            }
        }
        if (tick % 10 == 0) {
            for (BwPlayer bp : players.values()) if (bp.playing() && bp.team != null) normalizeInventory(bp);
            List<Player> all = players();
            List<Player> visible = new ArrayList<>();
            for (BwPlayer bp : players.values()) if (bp.playing()) visible.add(bp.player);
            health.update(all, visible);
        }
        if (tick % 20 == 0) {
            for (Generator g : generators) g.second();
            for (ShopNpc n : npcs) n.anchor();
            timeline();
            if (state != GameState.PLAYING) return;
            if (tick % 40 == 0) healPoolAndEffects();
            updatePlayingBoards();
        }
    }

    private void timeline() {
        while (nextEvent < EVENT_TIMES.length && elapsedSeconds() >= eventTime(nextEvent)) {
            int ev = nextEvent++;
            switch (ev) {
                case 0: upgradeGenerators(Generator.Kind.DIAMOND, 2); break;
                case 1: upgradeGenerators(Generator.Kind.EMERALD, 2); break;
                case 2: upgradeGenerators(Generator.Kind.DIAMOND, 3); break;
                case 3: upgradeGenerators(Generator.Kind.EMERALD, 3); break;
                case 4: allBedsGone(); break;
                case 5: suddenDeath(); break;
                case 6: end(null); return;
                default: break;
            }
        }
    }

    private void upgradeGenerators(Generator.Kind kind, int tier) {
        for (Generator g : generators) if (g.kind() == kind) g.setTier(tier);
        String name = kind == Generator.Kind.DIAMOND ? ChatColor.AQUA + "Diamond Generators" : ChatColor.DARK_GREEN + "Emerald Generators";
        broadcast(name + ChatColor.YELLOW + " have been upgraded to Tier " + ChatColor.RED + Text.roman(tier));
    }

    private void allBedsGone() {
        for (BwTeam t : teams) {
            if (!t.bedAlive) continue;
            t.bedAlive = false;
            breakBedBlocks(t);
        }
        broadcast("");
        broadcast(ChatColor.RED + "" + ChatColor.BOLD + "All beds have been destroyed!");
        broadcast("");
        for (BwPlayer bp : players.values()) {
            Text.title(bp.player, ChatColor.RED + "" + ChatColor.BOLD + "BED DESTROYED!",
                    ChatColor.WHITE + "All beds have been destroyed!", 10, 60, 20);
            bp.player.playSound(bp.player.getLocation(), Sound.ENDERDRAGON_GROWL, 1f, 1f);
        }
    }

    private void suddenDeath() {
        broadcast("");
        broadcast(ChatColor.RED + "" + ChatColor.BOLD + "SUDDEN DEATH " + ChatColor.GRAY + "- " + ChatColor.WHITE
                + "The Ender Dragons have been unleashed!");
        for (BwTeam t : teams) {
            if (!t.alive()) continue;
            int n = t.upgrades.level(Upgrade.DRAGON_BUFF) > 0 ? 2 : 1;
            broadcast(Text.center(t.coloredName() + ChatColor.GRAY + " - " + ChatColor.GOLD + "+" + n
                    + (n == 1 ? " Dragon" : " Dragons")));
        }
        broadcast("");
        dragons.spawnAll();
        for (BwPlayer bp : players.values()) {
            Text.title(bp.player, ChatColor.RED + "Sudden Death", "", 10, 50, 20);
            bp.player.playSound(bp.player.getLocation(), Sound.ENDERDRAGON_GROWL, 1f, 1f);
        }
    }

    private void healPoolAndEffects() {
        for (BwPlayer bp : players.values()) {
            if (!bp.playing() || bp.team == null) continue;
            applyTeamEffects(bp);
            if (bp.team.upgrades.level(Upgrade.HEAL_POOL) > 0 && bp.team.inBase(bp.player.getLocation())) {
                bp.player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 0, true, true), true);
            }
        }
    }

    /** Invisible players have their armor hidden from everyone else (equipment packets with empty slots). */
    private void invisibilityArmor() {
        List<Player> viewers = players();
        for (BwPlayer bp : players.values()) {
            Player p = bp.player;
            boolean invis = bp.playing() && p.hasPotionEffect(PotionEffectType.INVISIBILITY);
            if (invis) {
                bp.invisArmorHidden = true;
                for (Player v : viewers) {
                    if (v == p) continue;
                    for (int slot = 1; slot <= 4; slot++) Text.send(v, new PacketPlayOutEntityEquipment(p.getEntityId(), slot, null));
                }
            } else if (bp.invisArmorHidden) {
                bp.invisArmorHidden = false;
                revealArmor(bp, viewers);
            }
        }
    }

    void revealArmor(BwPlayer bp, List<Player> viewers) {
        ItemStack[] armor = bp.player.getInventory().getArmorContents();
        for (Player v : viewers) {
            if (v == bp.player) continue;
            for (int slot = 1; slot <= 4; slot++) {
                Text.send(v, new PacketPlayOutEntityEquipment(bp.player.getEntityId(), slot, CraftItemStack.asNMSCopy(armor[slot - 1])));
            }
        }
    }

    // =================================================================== traps

    private void checkTraps() {
        long now = System.currentTimeMillis();
        for (BwTeam t : teams) {
            if (!t.participated || !t.alive()) { t.intruders.clear(); continue; }
            Set<UUID> inside = new HashSet<>();
            for (BwPlayer bp : players.values()) {
                if (!bp.playing() || bp.team == null || bp.team == t) continue;
                if (!t.inBase(bp.player.getLocation())) continue;
                inside.add(bp.player.getUniqueId());
                if (t.intruders.contains(bp.player.getUniqueId())) continue; // already inside
                if (bp.trapImmuneUntil > now) continue;
                if (t.upgrades.traps().isEmpty()) continue;
                if (tick - t.lastTrapTick < 20 * 5) continue;
                Trap trap = t.upgrades.pollTrap();
                t.lastTrapTick = tick;
                triggerTrap(t, trap, bp);
            }
            t.intruders.clear();
            t.intruders.addAll(inside);
        }
    }

    private void triggerTrap(BwTeam t, Trap trap, BwPlayer intruder) {
        Player ip = intruder.player;
        net.h2020.api.stats.CombatStats.inc("trap.triggered." + trap.name().toLowerCase());
        net.h2020.bedwars.shop.TrapActions.of(trap).trigger(this, t, intruder);
        for (Player m : t.members) {
            if (!m.isOnline()) continue;
            Text.title(m, ChatColor.RED + "" + ChatColor.BOLD + "TRAP TRIGGERED!",
                    ChatColor.WHITE + "Your " + trap.displayName + " has been triggered!", 0, 50, 10);
            if (trap == Trap.ALARM) {
                m.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "Alarm! " + feedName(ip) + ChatColor.GRAY + " from "
                        + intruder.team.coloredName() + " Team " + ChatColor.GRAY + "has entered your base!");
            } else {
                m.sendMessage(net.h2020.api.core.Messages.get("bedwars.game.trap-triggered", "trap", trap.displayName));
            }
            m.playSound(m.getLocation(), Sound.ENDERMAN_TELEPORT, 1f, 1f);
        }
        ip.playSound(ip.getLocation(), Sound.ENDERMAN_TELEPORT, 0.6f, 0.5f);
    }

    // =================================================================== death / respawn

    /** Recent enemy attacker for credit (10s combat tag). */
    Player recentAttacker(BwPlayer v) {
        if (v.lastDamager == null || System.currentTimeMillis() - v.lastDamageAt > COMBAT_TAG_MS) return null;
        BwPlayer k = data(v.lastDamager);
        if (k == null || k.team == null || k.team == v.team) return null;
        return v.lastDamager;
    }

    /** Main death path: cancels the vanilla death entirely (no death screen), like Hypixel. */
    void handleDeath(BwPlayer v, DamageCause cause) {
        if (state != GameState.PLAYING || !v.playing() || v.team == null) return;
        Player p = v.player;
        Player killer = recentAttacker(v);
        boolean fin = !v.team.bedAlive;
        String msg = deathMessage(v, cause, killer);
        if (fin) msg += " " + ChatColor.AQUA + ChatColor.BOLD + "FINAL KILL!";
        broadcastAlive(msg);

        // a dead player is a spectator: no kills counted, no coins, and above all no resources handed to an inventory
        // that no longer exists in the game
        BwPlayer k = killer == null ? null : data(killer);
        if (k != null && !k.eliminated) {
            if (fin) { k.finalKills++; k.coins += 20; } else { k.kills++; k.coins += 10; }
            transferResources(p, killer);
            killer.playSound(killer.getLocation(), Sound.ORB_PICKUP, 1f, 1f);
        }
        Bukkit.getPluginManager().callEvent(new GameKillEvent(this, p, killer, fin));

        // permanent tools go down a tier (never below the first tier once bought)
        if (v.pickaxeTier > 1) v.pickaxeTier--;
        if (v.axeTier > 1) v.axeTier--;
        v.lastDamager = null;
        v.lastDamageMobName = null;
        p.closeInventory();
        p.setItemOnCursor(null);
        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        p.setHealth(p.getMaxHealth());
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.setVelocity(new Vector(0, 0, 0));

        if (fin) {
            v.eliminated = true;
            makeSpectator(v);
            Text.title(p, ChatColor.RED + "" + ChatColor.BOLD + "YOU DIED!", ChatColor.GRAY + "You are now a spectator!", 0, 60, 20);
            checkTeamElimination(v.team);
            checkWin();
        } else {
            v.respawnAt = tick + settings.efRespawnSeconds() * 20L;
            v.lastRespawnSecond = settings.efRespawnSeconds();
            makeGhost(p);
            p.teleport(spectatorSpawn);
            Text.title(p, ChatColor.RED + "" + ChatColor.BOLD + "YOU DIED!", ChatColor.YELLOW + "You will respawn in "
                    + ChatColor.RED + settings.efRespawnSeconds() + ChatColor.YELLOW + " seconds!", 0, 30, 0);
            p.sendMessage(net.h2020.api.core.Messages.get("bedwars.game.respawn-in",
                    "seconds", settings.efRespawnSeconds()));
        }
    }

    private String deathMessage(BwPlayer v, DamageCause cause, Player killer) {
        String vn = feedName(v.player);
        String kn = killer == null ? null : feedName(killer);
        String gray = ChatColor.GRAY.toString();
        boolean recentMob = v.lastDamageKind == BwPlayer.DamageKind.MOB && v.lastDamageMobName != null
                && System.currentTimeMillis() - v.lastDamageAt < COMBAT_TAG_MS;
        if (cause == DamageCause.VOID) {
            return killer != null ? vn + gray + " was knocked into the void by " + kn + gray + "."
                    : vn + gray + " fell into the void.";
        }
        if (cause == DamageCause.FALL) {
            return killer != null ? vn + gray + " was knocked off a cliff by " + kn + gray + "."
                    : vn + gray + " fell from a high place.";
        }
        if (v.lastDamageKind == BwPlayer.DamageKind.MOB && recentMob) {
            return killer != null ? vn + gray + " was killed by " + kn + gray + "'s " + v.lastDamageMobName + gray + "."
                    : vn + gray + " was killed by " + v.lastDamageMobName + gray + ".";
        }
        if (killer == null) return vn + gray + " died.";
        switch (v.lastDamageKind) {
            case BOW: return vn + gray + " was shot by " + kn + gray + ".";
            case EXPLOSION: return vn + gray + " was blown up by " + kn + gray + ".";
            default: return vn + gray + " was killed by " + kn + gray + ".";
        }
    }

    private void transferResources(Player from, Player to) {
        int[] amounts = new int[4];
        Material[] mats = {Material.IRON_INGOT, Material.GOLD_INGOT, Material.DIAMOND, Material.EMERALD};
        for (ItemStack s : from.getInventory().getContents()) {
            if (s == null) continue;
            for (int i = 0; i < 4; i++) if (s.getType() == mats[i]) amounts[i] += s.getAmount();
        }
        String[] names = {"Iron", "Gold", "Diamond", "Emerald"};
        ChatColor[] cols = {ChatColor.WHITE, ChatColor.GOLD, ChatColor.AQUA, ChatColor.DARK_GREEN};
        for (int i = 0; i < 4; i++) {
            if (amounts[i] <= 0) continue;
            Map<Integer, ItemStack> left = to.getInventory().addItem(new ItemStack(mats[i], amounts[i]));
            for (ItemStack s : left.values()) to.getWorld().dropItem(to.getLocation(), s);
            String n = names[i] + (i >= 2 && amounts[i] > 1 ? "s" : "");
            to.sendMessage(cols[i] + "+" + amounts[i] + " " + n);
        }
    }

    /** Respawning / spectating look: hidden, flying, no items. */
    private void makeGhost(Player p) {
        p.setGameMode(org.bukkit.GameMode.ADVENTURE);
        p.setAllowFlight(true);
        p.setFlying(true);
        p.getInventory().clear();
        for (BwPlayer o : players.values()) {
            if (o.player == p) continue;
            o.player.hidePlayer(p);
        }
        health.update(players(), Collections.<Player>emptyList());
    }

    void makeSpectator(BwPlayer bp) {
        Player p = bp.player;
        makeGhost(p);
        bp.respawnAt = -1;
        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, true, false), true);
        // spectators see each other, players don't see spectators
        for (BwPlayer o : players.values()) {
            if (o.player == p) continue;
            if (o.eliminated || o.team == null) { o.player.showPlayer(p); p.showPlayer(o.player); }
        }
        p.teleport(spectatorSpawn);
        giveSpectatorItems(p);
    }

    private void giveSpectatorItems(Player p) {
        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setItem(0, Items.of(Material.COMPASS).name(ChatColor.GREEN + "" + ChatColor.BOLD + "Teleporter"
                + ChatColor.GRAY + " (Right Click)").lore(ChatColor.GRAY + "Right-click to spectate players!").build());
        inv.setItem(7, Items.of(Material.PAPER).name(ChatColor.AQUA + "" + ChatColor.BOLD + "Play Again"
                + ChatColor.GRAY + " (Right Click)").lore(ChatColor.GRAY + "Right-click to play another game!").build());
        inv.setItem(8, Items.of(Material.BED).name(ChatColor.RED + "" + ChatColor.BOLD + "Return to Lobby"
                + ChatColor.GRAY + " (Right Click)").lore(ChatColor.GRAY + "Right-click to leave to the lobby!").build());
    }

    private void respawn(BwPlayer bp) {
        Player p = bp.player;
        bp.respawnAt = -1;
        bp.lastRespawnSecond = -1;
        p.setFlying(false);
        p.setAllowFlight(false);
        p.setGameMode(org.bukkit.GameMode.SURVIVAL);
        p.setFallDistance(0);
        p.setHealth(p.getMaxHealth());
        p.teleport(bp.team.spawn);
        giveKit(bp);
        for (BwPlayer o : players.values()) if (o.player != p) o.player.showPlayer(p);
        Text.title(p, ChatColor.GREEN + "" + ChatColor.BOLD + "RESPAWNED!", "", 0, 30, 10);
        net.h2020.api.core.Messages.send(p, "bedwars.bedwarsgame.you-have-respawned");
    }

    void checkTeamElimination(BwTeam t) {
        if (state != GameState.PLAYING || !t.participated || t.eliminationAnnounced || t.alive()) return;
        t.eliminationAnnounced = true;
        if (t.bedAlive) { t.bedAlive = false; breakBedBlocks(t); }
        broadcast("");
        broadcast(ChatColor.WHITE + "" + ChatColor.BOLD + "TEAM ELIMINATED > " + t.coloredName() + " Team "
                + ChatColor.RED + "has been eliminated!");
        broadcast("");
    }

    void checkWin() {
        if (state != GameState.PLAYING) return;
        List<BwTeam> alive = new ArrayList<>();
        for (BwTeam t : teams) if (t.participated && t.alive()) alive.add(t);
        if (alive.size() <= 1) end(alive.isEmpty() ? null : alive.get(0));
    }

    // =================================================================== beds

    @SuppressWarnings("deprecation")
    private void breakBedBlocks(BwTeam t) {
        if (t.bedFoot != null && t.bedFoot.getBlock().getType() == Material.BED_BLOCK) t.bedFoot.getBlock().setTypeIdAndData(0, (byte) 0, false);
        if (t.bedHead != null && t.bedHead.getBlock().getType() == Material.BED_BLOCK) t.bedHead.getBlock().setTypeIdAndData(0, (byte) 0, false);
    }

    BwTeam bedOwner(Block b) {
        for (BwTeam t : teams) {
            if (same(t.bedFoot, b) || same(t.bedHead, b)) return t;
        }
        // tolerate templates whose bed points are off by the other half
        for (BwTeam t : teams) {
            if (t.bedFoot != null && t.bedFoot.getWorld() == b.getWorld() && t.bedFoot.distanceSquared(b.getLocation()) <= 2.1) return t;
        }
        return null;
    }

    private static boolean same(Location l, Block b) {
        return l != null && l.getBlockX() == b.getX() && l.getBlockY() == b.getY() && l.getBlockZ() == b.getZ();
    }

    /** Player broke an enemy bed. */
    void destroyBed(BwTeam t, Player breaker) {
        if (!t.bedAlive) return;
        t.bedAlive = false;
        breakBedBlocks(t);
        BwPlayer b = data(breaker);
        if (b != null) { b.bedsBroken++; b.coins += 20; }
        String by = ChatColor.GRAY + " was destroyed by " + feedName(breaker) + ChatColor.GRAY + "!";
        String head = ChatColor.WHITE + "" + ChatColor.BOLD + "BED DESTRUCTION > ";
        for (BwPlayer bp : players.values()) {
            Player p = bp.player;
            p.sendMessage("");
            if (bp.team == t) {
                p.sendMessage(head + ChatColor.GRAY + "Your Bed" + by);
                Text.title(p, ChatColor.RED + "" + ChatColor.BOLD + "BED DESTROYED!", ChatColor.WHITE + "You will no longer respawn!", 0, 60, 20);
                p.playSound(p.getLocation(), Sound.WITHER_DEATH, 1f, 1f);
            } else {
                p.sendMessage(head + t.coloredName() + " Bed" + by);
                p.playSound(p.getLocation(), Sound.ENDERDRAGON_GROWL, 1f, 1f);
            }
            p.sendMessage("");
        }
        checkTeamElimination(t);
    }

    // =================================================================== placement rules

    /** null if allowed, else the Hypixel denial message. */
    public int islandRadius() {
        return module.settings().islandRadius;
    }

    /** Protection boxes for this arena: everything players must not build into. Built once, read by every placement. */
    private java.util.List<Region> protectedRegions() {
        java.util.List<Region> out = new java.util.ArrayList<>();
        for (Location g : diamondLocs) out.add(Region.around("diamond", g, 2.5, -1, 4, false, false));
        for (Location g : emeraldLocs) out.add(Region.around("emerald", g, 2.5, -1, 4, false, false));
        for (BwTeam t : teams) {
            if (t.spawn != null) out.add(Region.around("spawn", t.spawn, 1.6, -1, 3, false, false));
            if (t.generator != null) out.add(Region.around("forge", t.generator, 1.6, -1, 2, false, false));
            if (t.shop != null) out.add(Region.around("shop", t.shop, 1.1, -1, 3, false, false));
            if (t.upgradesNpc != null) out.add(Region.around("upgrades", t.upgradesNpc, 1.1, -1, 3, false, false));
        }
        return out;
    }

    public String placeDenial(Block b) {
        if (b.getY() > map.buildLimitY()) return ChatColor.RED + "Build height limit reached!";
        Vector min = map.boundsMin(), max = map.boundsMax();
        if (min != null && max != null) {
            if (b.getX() < min.getBlockX() || b.getX() > max.getBlockX() || b.getZ() < min.getBlockZ()
                    || b.getZ() > max.getBlockZ() || b.getY() < Math.max(0, min.getBlockY())) {
                return ChatColor.RED + "You can't place blocks there!";
            }
        }
        Location c = b.getLocation().add(0.5, 0, 0.5);
        for (Region r : protectedRegions()) {
            if (r.contains(c)) {
                net.h2020.api.stats.CombatStats.inc("region.denied." + r.name);
                return ChatColor.RED + "You can't place blocks there!";
            }
        }
        return null;
    }

    private static boolean near(Location block, Location p, double r, double dyMin, double dyMax) {
        if (p == null) return false;
        double dy = block.getY() - p.getBlockY();
        return Math.abs(block.getX() - p.getX()) <= r && Math.abs(block.getZ() - p.getZ()) <= r && dy >= dyMin && dy <= dyMax;
    }

    // =================================================================== scoreboards

    private void updatePlayingBoards() {
        ScoreboardService sb = H2020.find(ScoreboardService.class);
        if (sb == null) return;
        String title = ChatColor.YELLOW + "" + ChatColor.BOLD + "BED WARS";
        String event = state == GameState.PLAYING && nextEvent < EVENT_NAMES.length
                ? ChatColor.WHITE + EVENT_NAMES[nextEvent] + " in " + ChatColor.GREEN + Text.clock(nextEventSeconds())
                : ChatColor.WHITE + "Game End in " + ChatColor.GREEN + Text.clock(0);
        boolean stats = teams.size() <= 4;
        SidebarTemplate template = Sidebars.get("bedwars-playing");
        if (template != null) {
            for (BwPlayer bp : players.values()) {
                List<String> teamLines = new ArrayList<String>();
                for (BwTeam t : teams) {
                    String status;
                    if (t.bedAlive) status = ChatColor.GREEN + "✔";
                    else if (t.participated && t.remaining() > 0) status = ChatColor.GREEN + "" + t.remaining();
                    else status = ChatColor.RED + "✘";
                    String line = t.color.chat + t.color.letter() + " " + ChatColor.WHITE + t.color.displayName + ": " + status;
                    if (bp.team == t) line += ChatColor.GRAY + " YOU";
                    teamLines.add(line);
                }
                Map<String, String> vars = new HashMap<String, String>();
                vars.put("date", date);
                vars.put("id", id);
                vars.put("event", event);
                vars.put("kills", String.valueOf(bp.kills));
                vars.put("final_kills", String.valueOf(bp.finalKills));
                vars.put("beds", String.valueOf(bp.bedsBroken));
                Set<String> flags = new HashSet<String>();
                flags.add("playing");
                if (stats) flags.add("stats");
                flags.add(soloMode() ? "solo" : "teams");
                try {
                    sb.setSidebar(bp.player, template.renderTitle(bp.player, vars),
                            template.render(bp.player, vars, flags, teamLines));
                    sb.setTabHeaderFooter(bp.player, ChatColor.AQUA + "You are playing on " + ChatColor.YELLOW + ChatColor.BOLD + "MC.HYPIXEL.NET\n",
                            "\n" + ChatColor.AQUA + "Kills: " + ChatColor.YELLOW + bp.kills + ChatColor.AQUA + " Final Kills: "
                                    + ChatColor.YELLOW + bp.finalKills + ChatColor.AQUA + " Beds Broken: " + ChatColor.YELLOW + bp.bedsBroken
                                    + "\n\n" + ChatColor.GREEN + "Ranks, Boosters & MORE! " + ChatColor.RED + ChatColor.BOLD + "STORE.HYPIXEL.NET");
                } catch (Throwable ignored) { }
            }
            return;
        }
        for (BwPlayer bp : players.values()) {
            List<String> lines = new ArrayList<>();
            lines.add(ChatColor.GRAY + date + "  " + ChatColor.DARK_GRAY + id);
            lines.add(blank(1));
            lines.add(event);
            lines.add(blank(2));
            for (BwTeam t : teams) {
                String status;
                if (t.bedAlive) status = ChatColor.GREEN + "✔";
                else if (t.participated && t.remaining() > 0) status = ChatColor.GREEN + "" + t.remaining();
                else status = ChatColor.RED + "✘";
                String line = t.color.chat + t.color.letter() + " " + ChatColor.WHITE + t.color.displayName + ": " + status;
                if (bp.team == t) line += ChatColor.GRAY + " YOU";
                lines.add(line);
            }
            lines.add(blank(3));
            if (stats) {
                lines.add(ChatColor.WHITE + "Kills: " + ChatColor.GREEN + bp.kills);
                lines.add(ChatColor.WHITE + "Final Kills: " + ChatColor.GREEN + bp.finalKills);
                lines.add(ChatColor.WHITE + "Beds Broken: " + ChatColor.GREEN + bp.bedsBroken);
                lines.add(blank(4));
            }
            lines.add(ChatColor.YELLOW + "www.hypixel.net");
            try {
                sb.setSidebar(bp.player, title, lines);
                sb.setTabHeaderFooter(bp.player, ChatColor.AQUA + "You are playing on " + ChatColor.YELLOW + ChatColor.BOLD + "MC.HYPIXEL.NET\n",
                        "\n" + ChatColor.AQUA + "Kills: " + ChatColor.YELLOW + bp.kills + ChatColor.AQUA + " Final Kills: "
                                + ChatColor.YELLOW + bp.finalKills + ChatColor.AQUA + " Beds Broken: " + ChatColor.YELLOW + bp.bedsBroken
                                + "\n\n" + ChatColor.GREEN + "Ranks, Boosters & MORE! " + ChatColor.RED + ChatColor.BOLD + "STORE.HYPIXEL.NET");
            } catch (Throwable ignored) { }
        }
    }

    // =================================================================== end

    private void end(BwTeam winnerTeam) {
        if (state == GameState.ENDING) return;
        winner = winnerTeam;
        setState(GameState.ENDING);
        endAt = tick + settings.efEndSeconds() * 20L;
        gadgets.clear();
        dragons.clear();
        for (BwPlayer bp : players.values()) {
            bp.respawnAt = -1;
            net.h2020.api.core.LastGame.record(bp.player, type, mapName());
            net.h2020.api.core.PlayAgain.giveItem(bp.player);
            if (winner != null && bp.team == winner) {
                Text.title(bp.player, ChatColor.GOLD + "" + ChatColor.BOLD + "VICTORY!", "", 5, 80, 20);
                bp.coins += soloMode() ? 50 : 100;
            } else {
                Text.title(bp.player, ChatColor.RED + "" + ChatColor.BOLD + "GAME OVER!", "", 5, 80, 20);
            }
        }
        sendEndSummary();
        for (BwPlayer bp : players.values()) sendRewards(bp);
        // last line of the end screen, exactly where Hypixel puts it: the summary first, then the prompt
        for (BwPlayer bp : players.values())
            net.h2020.api.core.PlayAgain.sendPrompt(bp.player, winner != null && bp.team == winner, true);
        updatePlayingBoards();
    }

    private void sendEndSummary() {
        List<String> lines = new ArrayList<>();
        lines.add(Text.BAR);
        lines.add(Text.center(ChatColor.WHITE + "" + ChatColor.BOLD + "Bed Wars"));
        lines.add("");
        if (winner != null) {
            StringBuilder names = new StringBuilder();
            for (Player m : winner.members) {
                if (names.length() > 0) names.append(ChatColor.GRAY).append(", ");
                names.append(rankName(m));
            }
            if (soloMode()) lines.add(Text.center(ChatColor.YELLOW + "Winner " + ChatColor.GRAY + "- " + names));
            else lines.add(Text.center(winner.coloredName() + ChatColor.GRAY + " - " + names));
        } else {
            lines.add(Text.center(ChatColor.YELLOW + "Winner " + ChatColor.GRAY + "- " + ChatColor.RED + "None"));
        }
        lines.add("");
        List<BwPlayer> killers = new ArrayList<>(players.values());
        Collections.sort(killers, (a, b) -> (b.kills + b.finalKills) - (a.kills + a.finalKills));
        String[] place = {ChatColor.YELLOW + "" + ChatColor.BOLD + "1st Killer", ChatColor.GOLD + "" + ChatColor.BOLD + "2nd Killer",
                ChatColor.RED + "" + ChatColor.BOLD + "3rd Killer"};
        int shown = 0;
        for (BwPlayer k : killers) {
            if (shown >= 3 || k.kills + k.finalKills <= 0) break;
            lines.add(Text.center(place[shown] + ChatColor.GRAY + " - " + rankName(k.player) + ChatColor.GRAY + " - " + (k.kills + k.finalKills)));
            shown++;
        }
        if (shown > 0) lines.add("");
        lines.add(Text.BAR);
        for (BwPlayer bp : players.values()) for (String l : lines) bp.player.sendMessage(l);
    }

    private void sendRewards(BwPlayer bp) {
        Player p = bp.player;
        p.sendMessage("");
        if (winner != null && bp.team == winner) p.sendMessage(ChatColor.GOLD + "+" + (soloMode() ? 50 : 100) + " coins! (Win)");
        if (bp.kills > 0) p.sendMessage(ChatColor.GOLD + "+" + (bp.kills * 10) + " coins! (Kills)");
        if (bp.finalKills > 0) p.sendMessage(ChatColor.GOLD + "+" + (bp.finalKills * 20) + " coins! (Final Kills)");
        if (bp.bedsBroken > 0) p.sendMessage(ChatColor.GOLD + "+" + (bp.bedsBroken * 20) + " coins! (Beds Broken)");
        int minutes = Math.max(1, elapsedSeconds() / 60);
        p.sendMessage(ChatColor.AQUA + "+" + (minutes * 5 + (winner != null && bp.team == winner ? 50 : 0)) + " Bed Wars Experience (Time Played)");
    }

    private void endingTick() {
        for (Generator g : generators) g.animate(tick);
        if (tick % 20 == 0 && winner != null && endAt - tick > (settings.efEndSeconds() - 6) * 20L) {
            for (Player m : winner.members) {
                BwPlayer bp = data(m);
                if (bp != null && !bp.eliminated && m.isOnline()) firework(m.getLocation(), winner.color.armor);
            }
        }
        if (tick >= endAt) teardown();
    }

    private void firework(Location l, Color c) {
        try {
            Firework fw = l.getWorld().spawn(l.clone().add(0, 1, 0), Firework.class);
            FireworkMeta fm = fw.getFireworkMeta();
            fm.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).withColor(c).withFade(Color.WHITE)
                    .flicker(true).trail(true).build());
            fm.setPower(1);
            fw.setFireworkMeta(fm);
        } catch (Throwable ignored) { }
    }

    // =================================================================== teardown

    @Override
    public void shutdown() {
        if (tornDown) return;
        if (state != GameState.ENDING) {
            GameState from = state;
            state = GameState.ENDING;
            Bukkit.getPluginManager().callEvent(new GameStateChangeEvent(this, from, GameState.ENDING));
        }
        teardown();
    }

    private void teardown() {
        if (tornDown) return;
        tornDown = true;
        gadgets.clear();
        dragons.clear();
        for (ShopNpc n : npcs) n.remove();
        npcs.clear();
        for (Generator g : generators) g.remove();
        for (net.h2020.bedwars.world.Hologram h : chestLabels) h.remove();
        chestLabels.clear();
        generators.clear();

        GameManager gm = H2020.find(GameManager.class);
        // humans go back to the lobby; bots are handed to GameManager.finished
        for (BwPlayer bp : new ArrayList<>(players.values())) {
            Player p = bp.player;
            if (isBot(p)) continue;
            players.remove(p.getUniqueId());
            module.unindex(p, this);
            cleanupPlayer(p);
            if (!p.isOnline()) continue;
            if (gm != null) {
                try { gm.sendToLobby(p, GameFamily.BEDWARS); } catch (Throwable t) { evacuate(p); }
            } else evacuate(p);
        }
        module.unregister(this);
        if (gm != null) {
            try { gm.finished(this); } catch (Throwable t) {
                module.plugin().getLogger().log(Level.WARNING, "[bedwars] GameManager.finished failed", t);
            }
        }
        for (BwPlayer bp : new ArrayList<>(players.values())) {
            module.unindex(bp.player, this);
            cleanupPlayer(bp.player);
        }
        players.clear();
        // give everything a moment to leave, then unload the instance (immediately while the plugin is disabling)
        Runnable destroy = new Runnable() {
            @Override public void run() {
                for (Player p : new ArrayList<>(world.getPlayers())) evacuate(p);
                try { map.destroyInstance(world); } catch (Throwable t) {
                    module.plugin().getLogger().log(Level.WARNING, "[bedwars] failed to destroy instance " + world.getName(), t);
                }
            }
        };
        if (module.plugin().isEnabled()) Bukkit.getScheduler().runTaskLater(module.plugin(), destroy, 40L);
        else destroy.run();
    }

    private void evacuate(Player p) {
        World target = null;
        MapService ms = H2020.find(MapService.class);
        if (ms != null) {
            try { target = ms.mainLobby(); } catch (Throwable ignored) { }
        }
        if (target == null || target == world) {
            for (World w : Bukkit.getWorlds()) if (w != world) { target = w; break; }
        }
        if (target != null) p.teleport(target.getSpawnLocation());
    }

    // =================================================================== chat

    private static final long SHOUT_COOLDOWN_MS = 60000;

    private String teamTag(BwPlayer bp) {
        if (bp.team == null) return "";
        return bp.team.color.chat + "[" + bp.team.color.displayName.toUpperCase() + "] ";
    }

    /** In-game chat routing (main thread): team chat in team modes, "!" / shout to all, spectator channel. */
    void chat(Player p, String msg) {
        BwPlayer bp = data(p);
        if (bp == null || msg.trim().isEmpty()) return;
        String sep = (hasRank(p) ? ChatColor.WHITE : ChatColor.GRAY) + ": ";
        String name = rankName(p);
        String line;
        List<Player> to = new ArrayList<>();
        if (state == GameState.PLAYING && (bp.eliminated || bp.team == null)) {
            line = ChatColor.GRAY + "[SPECTATOR] " + name + sep + msg;
            for (BwPlayer o : players.values()) if (o.eliminated || o.team == null) to.add(o.player);
        } else if (state == GameState.PLAYING && !soloMode()) {
            if (msg.startsWith("!") && msg.length() > 1) { shout(p, msg.substring(1).trim()); return; }
            line = ChatColor.BLUE + "[TEAM] " + name + sep + msg;
            for (Player m : bp.team.members) to.add(m);
        } else {
            line = teamTag(bp) + name + sep + msg;
            to.addAll(players());
        }
        for (Player r : to) if (r.isOnline()) r.sendMessage(line);
        Bukkit.getConsoleSender().sendMessage("[" + id + "] " + line);
    }

    void shout(Player p, String msg) {
        BwPlayer bp = data(p);
        if (bp == null) return;
        if (state != GameState.PLAYING) {
            net.h2020.api.core.Messages.send(p, "bedwars.bedwarsgame.you-can-only-use");
            return;
        }
        if (soloMode()) {
            net.h2020.api.core.Messages.send(p, "bedwars.bedwarsgame.you-can-t-use");
            return;
        }
        if (bp.eliminated || bp.team == null) {
            net.h2020.api.core.Messages.send(p, "bedwars.bedwarsgame.you-can-t-use-2");
            return;
        }
        if (msg == null || msg.trim().isEmpty()) {
            net.h2020.api.core.Messages.send(p, "bedwars.bedwarsgame.usage-shout-message");
            return;
        }
        long now = System.currentTimeMillis();
        long left = SHOUT_COOLDOWN_MS - (now - bp.lastShout);
        if (left > 0) {
            p.sendMessage(ChatColor.RED + "You must wait " + ChatColor.YELLOW + ((left + 999) / 1000) + ChatColor.RED
                    + " seconds until you can use /shout again!");
            return;
        }
        bp.lastShout = now;
        String sep = (hasRank(p) ? ChatColor.WHITE : ChatColor.GRAY) + ": ";
        String line = ChatColor.GOLD + "[SHOUT] " + teamTag(bp) + rankName(p) + sep + msg.trim();
        broadcast(line);
        Bukkit.getConsoleSender().sendMessage("[" + id + "] " + line);
    }

    // =================================================================== misc for listeners/gadgets

    boolean sameTeam(Player a, Player b) {
        BwPlayer x = data(a), y = data(b);
        return x != null && y != null && x.team != null && x.team == y.team;
    }

    List<BwPlayer> playingPlayers() {
        List<BwPlayer> out = new ArrayList<>();
        for (BwPlayer bp : players.values()) if (bp.playing() && bp.team != null) out.add(bp);
        return out;
    }

    ShopNpc npcOf(org.bukkit.entity.Entity e) {
        for (ShopNpc n : npcs) if (n.entity().equals(e)) return n;
        return null;
    }

    boolean isNpcPart(org.bukkit.entity.Entity e) {
        for (ShopNpc n : npcs) if (n.entity().equals(e)) return true;
        return false;
    }

    /** Called when a team upgrade was bought so effects apply immediately. */
    public void onUpgrade(BwTeam t, Upgrade u) {
        if (u == Upgrade.FORGE && t.forge != null) t.forge.setLevel(t.upgrades.level(Upgrade.FORGE));
        for (Player m : t.members) {
            BwPlayer bp = data(m);
            if (bp == null || !bp.playing()) continue;
            normalizeInventory(bp);
            applyTeamEffects(bp);
        }
    }

    boolean tornDown() { return tornDown; }

    /** core.date from the shared config (falls back to today's date). */
    static String fakeDate(org.bukkit.plugin.java.JavaPlugin plugin) {
        String d = null;
        try { d = plugin.getConfig().getString("core.date"); } catch (Throwable ignored) { }
        return d == null || d.trim().isEmpty() || d.trim().equalsIgnoreCase("today") ? new SimpleDateFormat("MM/dd/yy").format(new Date()) : d.trim();
    }
}
