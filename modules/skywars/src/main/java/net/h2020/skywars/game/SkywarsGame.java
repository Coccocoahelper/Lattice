package net.h2020.skywars.game;

import net.h2020.api.H2020;
import net.h2020.api.bot.BotService;
import net.h2020.api.core.ProfileService;
import net.h2020.api.core.Rank;
import net.h2020.api.core.ScoreboardService;
import net.h2020.api.core.SidebarTemplate;
import net.h2020.api.core.Sidebars;
import net.h2020.api.game.GameFamily;
import net.h2020.api.game.GameManager;
import net.h2020.api.game.GameState;
import net.h2020.api.game.GameTeam;
import net.h2020.api.game.GameMode;
import net.h2020.api.game.GameType;
import net.h2020.api.game.SkywarsView;
import net.h2020.api.game.TeamColor;
import net.h2020.api.game.events.GameKillEvent;
import net.h2020.api.game.events.GameStateChangeEvent;
import net.h2020.api.map.MapKeys;
import net.h2020.api.map.MapTemplate;
import net.h2020.skywars.SkywarsModule;
import net.h2020.skywars.kit.Kit;
import net.h2020.skywars.kit.KitMenu;
import net.h2020.skywars.loot.ChestFiller;
import net.h2020.skywars.loot.SkywarsLoot;
import net.h2020.skywars.util.Chat;
import net.h2020.skywars.util.Items;
import net.h2020.skywars.util.Titles;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/** One SkyWars match (Hypixel, summer 2020) running in its own world instance. */
public final class SkywarsGame implements SkywarsView {

    // ---- hotbar items (matched by type + name) -------------------------------------------------------------
    public static final String KIT_SELECTOR = ChatColor.GREEN + "Kit Selector " + ChatColor.GRAY + "(Right Click)";
    public static final String LEAVE = ChatColor.RED + "" + ChatColor.BOLD + "Leave " + ChatColor.GRAY + "(Right Click)";
    public static final String TELEPORTER = ChatColor.GREEN + "" + ChatColor.BOLD + "Teleporter " + ChatColor.GRAY + "(Right Click)";
    public static final String PLAY_AGAIN = ChatColor.AQUA + "" + ChatColor.BOLD + "Play Again " + ChatColor.GRAY + "(Right Click)";
    public static final String RETURN_TO_LOBBY = ChatColor.RED + "" + ChatColor.BOLD + "Return to Lobby " + ChatColor.GRAY + "(Right Click)";

    /** 2020 event timeline (seconds after the cages open). */
    private enum EventKind { REFILL, DOOM, END }

    private static final class TimedEvent {
        final EventKind kind; final String label; final int at;
        TimedEvent(EventKind kind, String label, int at) { this.kind = kind; this.label = label; this.at = at; }
    }

    private static final class Hit {
        final UUID attacker; final long time; final boolean projectile;
        Hit(UUID attacker, long time, boolean projectile) { this.attacker = attacker; this.time = time; this.projectile = projectile; }
    }

    /** Hits within this window credit the attacker for void/fall/fire deaths. */
    private static final long COMBAT_CREDIT_MS = 10_000L;
    private static final int COUNTDOWN = 20, FULL_COUNTDOWN = 10, END_SECONDS = 10;

    private final SkywarsModule module;
    private final GameMode type;
    private final MapTemplate template;
    private final String id;
    private final World world;
    private final Random random = new Random();
    private final String date;

    private final List<Island> islands = new ArrayList<>();
    private final List<SkywarsTeam> teams = new ArrayList<>();
    private final List<Location> midChests = new ArrayList<>();
    private final List<TimedEvent> events = new ArrayList<>();
    private final Location mid, spectatorSpawn;

    private final Set<UUID> players = new LinkedHashSet<>();
    private final Set<UUID> alive = new HashSet<>();
    private final Map<UUID, SkywarsTeam> teamOf = new HashMap<>();
    private final Map<UUID, Integer> cageSlot = new HashMap<>();
    private final Map<UUID, Integer> kills = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Map<UUID, Hit> lastHit = new HashMap<>();
    private final Map<UUID, Long> fallProtect = new HashMap<>();
    private final Set<String> looted = new HashSet<>();

    private GameState state;
    private int countdown = -1;
    private int elapsed;
    private int nextEvent;
    private int endSeconds;
    private int ticks;
    private boolean cagesOpen;
    private boolean shutDown;
    private SkywarsTeam winner;
    private EnderDragon dragon;
    private BukkitTask task;

    public SkywarsGame(SkywarsModule module, GameMode type, MapTemplate template, String id) {
        this.module = module;
        this.type = type;
        this.template = template;
        this.id = id;
        // the fake 2020 scoreboard date (core.date, settable with /h2020 date), not the real one
        String d = null;
        try { d = module.plugin().getConfig().getString("core.date"); } catch (Throwable ignored) { }
        this.date = d == null || d.trim().isEmpty() || d.trim().equalsIgnoreCase("today") ? new SimpleDateFormat("MM/dd/yy").format(new Date()) : d.trim();
        this.world = template.createInstance("skywars_" + id + "_" + Long.toHexString(System.nanoTime() & 0xFFFFFF));
        if (world == null) throw new IllegalStateException("Map instance failed for " + template.name());
        world.setGameRuleValue("doDaylightCycle", "false");
        world.setGameRuleValue("doMobSpawning", "false");
        world.setGameRuleValue("naturalRegeneration", "true");
        world.setGameRuleValue("doFireTick", "true");
        world.setGameRuleValue("keepInventory", "false");
        world.setTime(6000L);
        world.setStorm(false);
        world.setThundering(false);
        world.setDifficulty(org.bukkit.Difficulty.NORMAL);

        // islands
        int maxIslands = type.teams();
        for (int i = 0; i < maxIslands; i++) {
            List<Vector> cagePts = template.points(MapKeys.islandCage(i));
            if (cagePts.isEmpty()) break;
            Vector v = cagePts.get(0);
            Location spawn = new Location(world, Math.floor(v.getX()) + 0.5, Math.floor(v.getY()), Math.floor(v.getZ()) + 0.5,
                    template.yaw(MapKeys.islandCage(i), 0), 0f);
            List<Vector> bounds = template.points(MapKeys.islandBounds(i));
            Location center = bounds.size() >= 2
                    ? bounds.get(0).clone().add(bounds.get(1)).multiply(0.5).toLocation(world)
                    : spawn.clone();
            Island isl = new Island(i, spawn, center);
            for (Vector c : template.points(MapKeys.islandChests(i))) isl.chests.add(ensureChest(c));
            isl.cage = new Cage(spawn, type.teamSize() > 1 ? 2 : 1);
            isl.cage.build(Material.GLASS);
            islands.add(isl);
        }
        if (islands.isEmpty()) {
            template.destroyInstance(world);
            throw new IllegalStateException("Map " + template.name() + " has no island cages");
        }
        for (Vector c : template.points(MapKeys.MID_CHESTS)) midChests.add(ensureChest(c));

        TeamColor[] colors = TeamColor.values();
        for (Island isl : islands) teams.add(new SkywarsTeam(this, colors[isl.index % colors.length], isl));

        Vector midV = template.point(MapKeys.MID);
        if (midV != null) mid = midV.toLocation(world);
        else {
            Vector sum = new Vector();
            for (Island isl : islands) sum.add(isl.center.toVector());
            mid = sum.multiply(1.0 / islands.size()).toLocation(world);
        }
        Vector specV = template.point(MapKeys.SPECTATOR);
        spectatorSpawn = specV != null ? specV.toLocation(world) : mid.clone().add(0, 15, 0);

        // Hypixel 2020 SkyWars events
        events.add(new TimedEvent(EventKind.REFILL, "Refill", 180));
        events.add(new TimedEvent(EventKind.REFILL, "Refill", 360));
        events.add(new TimedEvent(EventKind.DOOM, "Doom", 900));
        events.add(new TimedEvent(EventKind.END, "Game End", 1200));

        state = GameState.WAITING;
        task = Bukkit.getScheduler().runTaskTimer(module.plugin(), new Runnable() {
            @Override public void run() {
                try { tick(); } catch (Throwable t) { module.plugin().getLogger().log(Level.SEVERE, "SkyWars tick " + SkywarsGame.this.id, t); }
            }
        }, 1L, 1L);
        Bukkit.getPluginManager().callEvent(new GameStateChangeEvent(this, null, GameState.WAITING));
    }

    private Location ensureChest(Vector v) {
        Block b = world.getBlockAt(v.getBlockX(), v.getBlockY(), v.getBlockZ());
        if (b.getType() != Material.CHEST && b.getType() != Material.TRAPPED_CHEST) b.setType(Material.CHEST);
        return b.getLocation();
    }

    // =========================================================================================================
    // Game contract
    // =========================================================================================================
    @Override public String id() { return id; }
    @Override public GameMode type() { return type; }
    @Override public GameState state() { return state; }
    @Override public World world() { return world; }
    @Override public String mapName() { return template.name(); }

    @Override public List<Player> players() { return online(players); }

    @Override public List<Player> alivePlayers() {
        List<Player> l = new ArrayList<>();
        for (UUID u : players) {
            if (!alive.contains(u)) continue;
            Player p = Bukkit.getPlayer(u);
            if (p != null) l.add(p);
        }
        return l;
    }

    @Override public List<? extends GameTeam> teams() { return Collections.unmodifiableList(teams); }

    @Override public GameTeam teamOf(Player p) { return teamOf.get(p.getUniqueId()); }

    @Override public boolean isSpectator(Player p) {
        UUID u = p.getUniqueId();
        return players.contains(u) && !alive.contains(u) && state != GameState.WAITING && state != GameState.STARTING;
    }

    @Override public int secondsUntilStart() { return state == GameState.STARTING ? countdown : -1; }

    @Override public Location spectatorSpawn() { return spectatorSpawn.clone(); }

    @Override
    public boolean addPlayer(Player p) {
        if (shutDown || (state != GameState.WAITING && state != GameState.STARTING)) return false;
        UUID u = p.getUniqueId();
        if (players.contains(u)) return true;
        if (players.size() >= maxPlayers()) return false;
        SkywarsTeam team = pickTeam();
        if (team == null) return false;

        int slot = 0;
        Set<Integer> used = new HashSet<>();
        for (UUID m : team.members) used.add(cageSlot.get(m));
        while (used.contains(slot)) slot++;

        players.add(u);
        alive.add(u);
        team.members.add(u);
        teamOf.put(u, team);
        cageSlot.put(u, slot);
        names.put(u, p.getName());
        kills.put(u, 0);

        Location spawn = team.island.memberSpawn(slot);
        p.teleport(spawn);
        prepareWaiting(p);
        if (type.teamSize() > 1) {
            ScoreboardService sb = H2020.find(ScoreboardService.class);
            if (sb != null) sb.setNametag(p, team.color.chat.toString(), "", team.color.chat);
        }
        broadcast(rankName(p) + ChatColor.YELLOW + " has joined (" + ChatColor.AQUA + players.size()
                + ChatColor.YELLOW + "/" + ChatColor.AQUA + maxPlayers() + ChatColor.YELLOW + ")!");
        updateBoards();
        return true;
    }

    private SkywarsTeam pickTeam() {
        List<SkywarsTeam> partial = new ArrayList<>(), empty = new ArrayList<>();
        for (SkywarsTeam t : teams) {
            if (t.members.isEmpty()) empty.add(t);
            else if (t.members.size() < type.teamSize()) partial.add(t);
        }
        if (!partial.isEmpty()) return partial.get(0);
        if (!empty.isEmpty()) return empty.get(random.nextInt(empty.size()));
        return null;
    }

    @Override
    public void removePlayer(Player p) {
        UUID u = p.getUniqueId();
        if (!players.contains(u)) return;
        if (state == GameState.WAITING || state == GameState.STARTING) {
            players.remove(u);
            alive.remove(u);
            SkywarsTeam t = teamOf.remove(u);
            if (t != null) t.members.remove(u);
            cageSlot.remove(u);
            kills.remove(u);
            broadcast(rankName(p) + ChatColor.YELLOW + " has quit!");
        } else {
            if (state == GameState.PLAYING && alive.contains(u)) {
                eliminate(p, DeathCause.DISCONNECT, recentAttacker(p), true, false);
            }
            players.remove(u);
            alive.remove(u);
        }
        lastHit.remove(u);
        fallProtect.remove(u);
        resetPlayer(p);
        if (!shutDown) {
            updateBoards();
            if (state == GameState.PLAYING) checkWin();
        }
    }

    @Override
    public void shutdown() {
        if (shutDown) return;
        shutDown = true;
        if (task != null) task.cancel();
        if (dragon != null && !dragon.isDead()) dragon.remove();
        // forced shutdowns (plugin disable, admin) still announce ENDING so listeners see a complete lifecycle
        if (state != GameState.ENDING) setState(GameState.ENDING);
        GameManager gm = H2020.find(GameManager.class);
        BotService bs = H2020.find(BotService.class);
        List<Player> everyone = players();
        for (Player p : everyone) resetPlayer(p);
        // humans go back to the SkyWars lobby; bots stay listed so GameManager.finished can churn them
        for (Player p : everyone) {
            boolean bot = false;
            try { bot = bs != null && bs.isBot(p); } catch (Throwable ignored) { }
            if (bot) continue;
            players.remove(p.getUniqueId());
            alive.remove(p.getUniqueId());
            if (gm != null) {
                try { gm.sendToLobby(p, GameFamily.SKYWARS); } catch (Throwable t) { evacuate(p); }
            } else evacuate(p);
        }
        module.unregister(this);
        // contract: finished() while the world still exists (core moves the remaining bots out of it)
        if (gm != null) {
            try { gm.finished(this); } catch (Throwable t) {
                module.plugin().getLogger().log(Level.WARNING, "GameManager.finished failed for " + id, t);
            }
        }
        players.clear();
        alive.clear();
        teamOf.clear();
        lastHit.clear();
        fallProtect.clear();
        Runnable destroy = new Runnable() {
            @Override public void run() {
                for (Player p : new ArrayList<>(world.getPlayers())) evacuate(p);
                try { template.destroyInstance(world); }
                catch (Throwable t) { module.plugin().getLogger().log(Level.WARNING, "Failed to destroy SkyWars world " + world.getName(), t); }
            }
        };
        if (module.plugin().isEnabled()) Bukkit.getScheduler().runTaskLater(module.plugin(), destroy, 40L);
        else destroy.run();
    }

    private void evacuate(Player p) {
        if (p.getWorld().equals(world)) {
            World main = Bukkit.getWorlds().get(0);
            if (main.equals(world) && Bukkit.getWorlds().size() > 1) main = Bukkit.getWorlds().get(1);
            p.teleport(main.getSpawnLocation());
        }
    }

    // =========================================================================================================
    // SkywarsView
    // =========================================================================================================
    @Override public int islandOf(Player p) {
        SkywarsTeam t = teamOf.get(p.getUniqueId());
        return t == null ? -1 : t.island.index;
    }

    @Override public int islandCount() { return islands.size(); }

    @Override public Location cage(int island) {
        return island >= 0 && island < islands.size() ? islands.get(island).spawn.clone() : null;
    }

    @Override public List<Location> islandChests(int island) {
        List<Location> l = new ArrayList<>();
        if (island >= 0 && island < islands.size()) for (Location c : islands.get(island).chests) l.add(c.clone());
        return l;
    }

    @Override public List<Location> midChests() {
        List<Location> l = new ArrayList<>();
        for (Location c : midChests) l.add(c.clone());
        return l;
    }

    @Override public Location mid() { return mid.clone(); }

    @Override public Location islandCenter(int island) {
        return island >= 0 && island < islands.size() ? islands.get(island).center.clone() : null;
    }

    @Override public boolean cagesOpen() { return cagesOpen; }

    @Override public int refillSeconds() {
        if (state != GameState.PLAYING) return state == GameState.ENDING ? -1 : events.get(0).at;
        for (int i = nextEvent; i < events.size(); i++) {
            if (events.get(i).kind == EventKind.REFILL) return events.get(i).at - elapsed;
        }
        return -1;
    }

    @Override public boolean chestLooted(Chest chest) {
        return chest != null && looted.contains(key(chest.getLocation()));
    }

    // =========================================================================================================
    // Module-facing helpers
    // =========================================================================================================
    public boolean contains(Player p) { return players.contains(p.getUniqueId()); }
    public boolean isAlive(UUID u) { return alive.contains(u); }
    public boolean isAlive(Player p) { return alive.contains(p.getUniqueId()); }
    public boolean preGame() { return state == GameState.WAITING || state == GameState.STARTING; }
    public int kills(Player p) { Integer k = kills.get(p.getUniqueId()); return k == null ? 0 : k; }
    public boolean insane() { return type.insane(); }
    public int maxPlayers() { return Math.min(type.maxPlayers(), islands.size() * type.teamSize()); }

    public boolean sameTeam(Player a, Player b) {
        if (type.teamSize() <= 1 || a.equals(b)) return false;
        SkywarsTeam ta = teamOf.get(a.getUniqueId());
        return ta != null && ta == teamOf.get(b.getUniqueId());
    }

    public boolean fallProtected(Player p) {
        Long until = fallProtect.get(p.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    public void recordHit(Player victim, Player attacker, boolean projectile) {
        if (attacker == null || attacker.equals(victim)) return;
        lastHit.put(victim.getUniqueId(), new Hit(attacker.getUniqueId(), System.currentTimeMillis(), projectile));
    }

    /** Last attacker within the combat-credit window, if still in this game. */
    public Player recentAttacker(Player victim) {
        Hit h = lastHit.get(victim.getUniqueId());
        if (h == null || System.currentTimeMillis() - h.time > COMBAT_CREDIT_MS) return null;
        Player a = Bukkit.getPlayer(h.attacker);
        return a != null && players.contains(a.getUniqueId()) ? a : null;
    }

    public void markLooted(Location chestBlock) {
        if (state == GameState.PLAYING) looted.add(key(chestBlock));
    }

    public boolean isGameChest(Location l) {
        String k = key(l);
        for (Island isl : islands) for (Location c : isl.chests) if (key(c).equals(k)) return true;
        for (Location c : midChests) if (key(c).equals(k)) return true;
        return false;
    }

    public boolean isCageBlock(Block b) {
        for (Island isl : islands) if (isl.cage != null && isl.cage.contains(b)) return true;
        return false;
    }

    /** May a block be placed here (inside map bounds and below the build limit)? */
    public boolean canBuildAt(Location l) {
        if (l.getBlockY() >= template.buildLimitY()) return false;
        Vector min = template.boundsMin(), max = template.boundsMax();
        if (min == null || max == null) return true;
        return l.getX() >= Math.min(min.getX(), max.getX()) && l.getX() <= Math.max(min.getX(), max.getX()) + 1
                && l.getZ() >= Math.min(min.getZ(), max.getZ()) && l.getZ() <= Math.max(min.getZ(), max.getZ()) + 1
                && l.getY() >= Math.min(min.getY(), max.getY());
    }

    public void openKitMenu(Player p) {
        p.openInventory(new KitMenu(module.selectedKit(p), insane()).getInventory());
    }

    public void selectKit(Player p, Kit k) {
        module.selectKit(p, k);
        p.sendMessage(net.h2020.api.core.Messages.get("skywars.skywarsgame.you-have-selected-the","p1",k.displayName));
        p.playSound(p.getLocation(), Sound.NOTE_PLING, 1f, 2f);
    }

    public void openTeleporter(Player p) { p.openInventory(new TeleporterMenu(this, p).getInventory()); }

    public void spectate(Player spectator, UUID target) {
        Player t = Bukkit.getPlayer(target);
        if (t == null || !isAlive(t)) {
            net.h2020.api.core.Messages.send(spectator, "skywars.skywarsgame.that-player-is-no");
            return;
        }
        spectator.teleport(t.getLocation());
        spectator.sendMessage(net.h2020.api.core.Messages.get("skywars.skywarsgame.teleported-to","p1",coloredName(t)));
    }

    /** Player clicked "Leave" / "Return to Lobby". */
    public void leaveToLobby(Player p) {
        removePlayer(p);
        GameManager gm = H2020.find(GameManager.class);
        if (gm != null) gm.sendToLobby(p, GameFamily.SKYWARS);
        else evacuate(p);
    }

    /** Spectator clicked "Play Again". */
    public void playAgain(Player p) {
        GameManager gm = H2020.find(GameManager.class);
        if (gm == null) { leaveToLobby(p); return; }
        removePlayer(p);
        net.h2020.api.core.Messages.send(p, "skywars.skywarsgame.sending-you-to-a");
        if (gm.queue(p, type) == null) gm.sendToLobby(p, GameFamily.SKYWARS);
    }

    /** Name coloured as in SkyWars chat: team colour in team modes, rank colour in solo. */
    public String coloredName(Player p) {
        SkywarsTeam t = teamOf.get(p.getUniqueId());
        if (type.teamSize() > 1 && t != null) return t.color.chat + p.getName();
        return rankName(p);
    }

    private String coloredName(UUID u) {
        Player p = Bukkit.getPlayer(u);
        if (p != null) return coloredName(p);
        String n = names.get(u);
        return ChatColor.GRAY + (n == null ? "?" : n);
    }

    private static String rankName(Player p) {
        ProfileService ps = H2020.find(ProfileService.class);
        if (ps == null) return ChatColor.GRAY + p.getName();
        Rank r = ps.rank(p);
        return (r == null ? ChatColor.GRAY : r.color) + p.getName();
    }

    private static String fullName(UUID u, String fallback) {
        Player p = Bukkit.getPlayer(u);
        ProfileService ps = H2020.find(ProfileService.class);
        if (p != null && ps != null) {
            String d = ps.displayName(p);
            if (d != null) return d;
        }
        return ChatColor.GRAY + (p != null ? p.getName() : fallback);
    }

    // =========================================================================================================
    // Ticking
    // =========================================================================================================
    private void tick() {
        if (shutDown) return;
        ticks++;
        voidCheck();
        if (ticks % 20 == 0) second();
    }

    private void voidCheck() {
        for (Player p : players()) {
            if (!p.getWorld().equals(world)) continue;
            UUID u = p.getUniqueId();
            double y = p.getLocation().getY();
            if (preGame()) {
                SkywarsTeam t = teamOf.get(u);
                if (t != null && y < t.island.spawn.getY() - 3) {
                    p.teleport(t.island.memberSpawn(slotOf(u)));
                }
            } else if (alive.contains(u) && state == GameState.PLAYING) {
                if (y < 0) eliminate(p, DeathCause.VOID, recentAttacker(p), false, false);
            } else if (y < 0) {
                p.setFallDistance(0f);
                p.teleport(spectatorSpawn);
            }
        }
    }

    private int slotOf(UUID u) { Integer s = cageSlot.get(u); return s == null ? 0 : s; }

    private void second() {
        switch (state) {
            case WAITING:
            case STARTING:
                countdownTick();
                break;
            case PLAYING:
                elapsed++;
                while (nextEvent < events.size() && elapsed >= events.get(nextEvent).at) {
                    TimedEvent e = events.get(nextEvent++);
                    runEvent(e);
                    if (shutDown || state != GameState.PLAYING) return;
                }
                break;
            case ENDING:
                endSeconds++;
                if (endSeconds <= END_SECONDS - 2) launchWinnerFireworks();
                if (endSeconds >= END_SECONDS) { shutdown(); return; }
                break;
        }
        updateBoards();
    }

    private int minPlayers() {
        int def = Math.max(2, maxPlayers() / 2);
        return Math.max(2, Math.min(maxPlayers(), module.minPlayers(type, def)));
    }

    private void countdownTick() {
        int n = players.size();
        if (state == GameState.WAITING) {
            if (n >= minPlayers()) {
                countdown = n >= maxPlayers() ? FULL_COUNTDOWN : COUNTDOWN;
                setState(GameState.STARTING);
            } else return;
        }
        if (n < minPlayers()) {
            countdown = -1;
            setState(GameState.WAITING);
            broadcast(ChatColor.RED + "We don't have enough players! Start cancelled.");
            for (Player p : players()) Titles.title(p, ChatColor.RED + "Waiting for more players...", "", 0, 30, 10);
            return;
        }
        if (n >= maxPlayers() && countdown > FULL_COUNTDOWN) countdown = FULL_COUNTDOWN;
        if (countdown <= 0) { start(); return; }
        if (countdown == 20 || countdown == 15 || countdown == 10 || countdown <= 5) {
            ChatColor c = countdown <= 5 ? ChatColor.RED : countdown <= 10 ? ChatColor.GOLD : ChatColor.YELLOW;
            broadcast(ChatColor.YELLOW + "The game starts in " + c + countdown + ChatColor.YELLOW
                    + " second" + (countdown == 1 ? "" : "s") + "!");
            if (countdown <= 10) {
                ChatColor tc = countdown <= 3 ? ChatColor.RED : countdown <= 5 ? ChatColor.YELLOW : ChatColor.GREEN;
                for (Player p : players()) {
                    Titles.title(p, tc + String.valueOf(countdown), "", 0, 25, 5);
                    p.playSound(p.getLocation(), Sound.NOTE_STICKS, 1f, 1f);
                }
            }
        }
        countdown--;
    }

    private void setState(GameState to) {
        GameState from = state;
        if (from == to) return;
        state = to;
        Bukkit.getPluginManager().callEvent(new GameStateChangeEvent(this, from, to));
    }

    // =========================================================================================================
    // Start
    // =========================================================================================================
    private void start() {
        countdown = -1;
        refillAll();
        setState(GameState.PLAYING);
        long protectUntil = System.currentTimeMillis() + 5000L;
        for (Player p : players()) {
            UUID u = p.getUniqueId();
            p.closeInventory();
            p.getInventory().clear();
            p.getInventory().setArmorContents(null);
            p.setGameMode(org.bukkit.GameMode.SURVIVAL);
            p.setHealth(p.getMaxHealth());
            p.setFoodLevel(20);
            p.setSaturation(5f);
            p.setFallDistance(0f);
            for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
            module.selectedKit(p).apply(p, insane());
            fallProtect.put(u, protectUntil);
        }
        for (Island isl : islands) if (isl.cage != null) isl.cage.remove();
        cagesOpen = true;

        String goal = type.teamSize() > 1 ? "eliminate every other team." : "eliminate every other player.";
        List<String> block = new ArrayList<>();
        block.add(Chat.BAR);
        block.add(Chat.center(ChatColor.WHITE + "" + ChatColor.BOLD + "SkyWars"));
        block.add("");
        block.add(Chat.center(ChatColor.YELLOW + "" + ChatColor.BOLD + "Gather resources and equipment on your"));
        block.add(Chat.center(ChatColor.YELLOW + "" + ChatColor.BOLD + "island in order to " + goal));
        block.add(Chat.center(ChatColor.YELLOW + "" + ChatColor.BOLD + "Go to the center island for special chests"));
        block.add(Chat.center(ChatColor.YELLOW + "" + ChatColor.BOLD + "with special items!"));
        block.add("");
        block.add(Chat.BAR);
        for (Player p : players()) {
            for (String l : block) p.sendMessage(l);
            if (type.teamSize() <= 1) p.sendMessage(net.h2020.api.core.Messages.get("skywars.game.teaming-solo"));
            Titles.title(p, ChatColor.YELLOW + "Cages opened! " + ChatColor.RED + "FIGHT!", "", 5, 30, 10);
            p.playSound(p.getLocation(), Sound.ENDERDRAGON_GROWL, 0.6f, 1.4f);
        }
        checkWin();
    }

    // =========================================================================================================
    // Events / loot
    // =========================================================================================================
    private void runEvent(TimedEvent e) {
        switch (e.kind) {
            case REFILL:
                refillAll();
                for (Player p : players()) {
                    p.sendMessage(net.h2020.api.core.Messages.get("skywars.game.chests-refilled"));
                    Titles.title(p, "", ChatColor.YELLOW + "Chests refilled!", 5, 40, 10);
                    p.playSound(p.getLocation(), Sound.CHEST_OPEN, 1f, 1f);
                }
                break;
            case DOOM:
                for (Player p : players()) {
                    p.sendMessage(net.h2020.api.core.Messages.get("skywars.game.doom"));
                    Titles.title(p, ChatColor.RED + "" + ChatColor.BOLD + "DOOM!", ChatColor.YELLOW + "The Ender Dragon has spawned!", 5, 50, 10);
                    p.playSound(p.getLocation(), Sound.ENDERDRAGON_GROWL, 1f, 1f);
                }
                try {
                    dragon = (EnderDragon) world.spawnEntity(mid.clone().add(0, 25, 0), EntityType.ENDER_DRAGON);
                } catch (Throwable t) {
                    module.plugin().getLogger().log(Level.WARNING, "Could not spawn SkyWars Doom dragon", t);
                }
                break;
            case END:
                for (Player p : players()) p.sendMessage(net.h2020.api.core.Messages.get("skywars.game.time-up"));
                end(null);
                break;
        }
    }

    private void refillAll() {
        boolean ins = insane();
        for (Island isl : islands) {
            List<Inventory> invs = new ArrayList<>();
            for (Location c : isl.chests) {
                Inventory inv = chestInventory(c);
                if (inv != null) invs.add(inv);
            }
            ChestFiller.fillIsland(invs, SkywarsLoot.island(ins), random);
        }
        for (Location c : midChests) {
            Inventory inv = chestInventory(c);
            if (inv != null) ChestFiller.fillMid(inv, SkywarsLoot.mid(ins), random);
        }
        looted.clear();
    }

    private static Inventory chestInventory(Location l) {
        BlockState st = l.getBlock().getState();
        return st instanceof Chest ? ((Chest) st).getBlockInventory() : null;
    }

    private static String key(Location l) { return l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ(); }

    // =========================================================================================================
    // Deaths
    // =========================================================================================================
    /**
     * Eliminate an alive player. dropItems: drop inventory at the death spot (not for void). vanillaDeath: the
     * player actually died (PlayerDeathEvent path) so drops/respawn are vanilla's business.
     */
    public void eliminate(Player victim, DeathCause cause, Player killer, boolean dropItems, boolean vanillaDeath) {
        UUID u = victim.getUniqueId();
        if (state != GameState.PLAYING || !alive.remove(u)) return;
        if (killer != null && (killer.equals(victim) || !players.contains(killer.getUniqueId()))) killer = null;
        Location at = victim.getLocation();

        if (dropItems && cause != DeathCause.VOID && !vanillaDeath) {
            PlayerInventory inv = victim.getInventory();
            List<ItemStack> drops = new ArrayList<>();
            for (ItemStack s : inv.getContents()) if (s != null && s.getType() != Material.AIR) drops.add(s);
            for (ItemStack s : inv.getArmorContents()) if (s != null && s.getType() != Material.AIR) drops.add(s);
            for (ItemStack s : drops) world.dropItemNaturally(at, s);
            if (victim.getItemOnCursor() != null && victim.getItemOnCursor().getType() != Material.AIR) {
                world.dropItemNaturally(at, victim.getItemOnCursor());
                victim.setItemOnCursor(null);
            }
        }

        String msg = cause.message(coloredName(victim), killer == null ? null : coloredName(killer));
        if (msg != null) broadcast(msg);

        if (killer != null) {
            UUID k = killer.getUniqueId();
            kills.put(k, kills(killer) + 1);
            killer.sendMessage(net.h2020.api.core.Messages.get("skywars.game.coins-kill", "amount", insane() ? 15 : 10));
            net.h2020.api.core.Messages.send(killer, "skywars.skywarsgame.1-soul");
            killer.playSound(killer.getLocation(), Sound.SUCCESSFUL_HIT, 1f, 1f);
        }
        Bukkit.getPluginManager().callEvent(new GameKillEvent(this, victim, killer, true));
        lastHit.remove(u);

        if (cause != DeathCause.DISCONNECT && victim.isOnline()) {
            if (!vanillaDeath) makeSpectator(victim, cause == DeathCause.VOID || at.getY() < 0);
            Titles.title(victim, ChatColor.RED + "" + ChatColor.BOLD + "YOU DIED!", ChatColor.GRAY + "You are now a spectator!", 5, 50, 15);
        }
        checkWin();
    }

    /** Hypixel-style spectator: invisible to players, flying, with the spectator hotbar. */
    public void makeSpectator(Player p, boolean toSpawn) {
        UUID u = p.getUniqueId();
        p.closeInventory();
        p.getInventory().clear();
        p.getInventory().setArmorContents(null);
        p.setItemOnCursor(null);
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        p.setGameMode(org.bukkit.GameMode.ADVENTURE);
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        p.setVelocity(new Vector(0, 0, 0));
        p.setAllowFlight(true);
        p.setFlying(true);
        p.spigot().setCollidesWithEntities(false);
        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false), true);
        if (toSpawn) p.teleport(spectatorSpawn);
        else p.teleport(p.getLocation().add(0, 0.5, 0));
        p.setFlying(true);
        for (Player q : players()) {
            if (q.equals(p)) continue;
            if (alive.contains(q.getUniqueId())) q.hidePlayer(p);
            else { q.showPlayer(p); p.showPlayer(q); }
        }
        giveSpectatorItems(p);
    }

    /** Respawn path after a real (vanilla) death: turn the player into a spectator where they respawned. */
    public void afterRespawn(Player p) {
        if (!players.contains(p.getUniqueId()) || alive.contains(p.getUniqueId()) || preGame()) return;
        makeSpectator(p, false);
    }

    private void giveSpectatorItems(Player p) {
        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setItem(0, Items.of(Material.COMPASS).name(TELEPORTER)
                .lore(ChatColor.GRAY + "Right-click to spectate players!").build());
        inv.setItem(7, Items.of(Material.PAPER).name(PLAY_AGAIN)
                .lore(ChatColor.GRAY + "Right-click to play another game!").build());
        inv.setItem(8, Items.of(Material.BED).name(RETURN_TO_LOBBY)
                .lore(ChatColor.GRAY + "Right-click to leave to the lobby!").build());
        inv.setHeldItemSlot(0);
        p.updateInventory();
    }

    private void prepareWaiting(Player p) {
        p.closeInventory();
        p.spigot().setCollidesWithEntities(true);
        p.setGameMode(org.bukkit.GameMode.ADVENTURE);
        p.setAllowFlight(false);
        p.setFlying(false);
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setSaturation(5f);
        p.setExp(0f);
        p.setLevel(0);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setArmorContents(null);
        inv.setItem(0, Items.of(Material.BOW).name(KIT_SELECTOR)
                .lore(ChatColor.GRAY + "Right-click to select a kit!").build());
        inv.setItem(8, Items.of(Material.BED).name(LEAVE)
                .lore(ChatColor.GRAY + "Right-click to leave to the lobby!").build());
        inv.setHeldItemSlot(0);
        p.updateInventory();
        for (Player q : players()) { q.showPlayer(p); p.showPlayer(q); }
    }

    /** Undo everything this game did to the player (visibility, effects, flight, name tag, sidebar). */
    private void resetPlayer(Player p) {
        for (Player q : Bukkit.getOnlinePlayers()) { q.showPlayer(p); p.showPlayer(q); }
        p.removePotionEffect(PotionEffectType.INVISIBILITY);
        p.spigot().setCollidesWithEntities(true);
        p.setFlying(false);
        p.setAllowFlight(false);
        p.closeInventory();
        Titles.clear(p);
        ScoreboardService sb = H2020.find(ScoreboardService.class);
        if (sb != null) {
            sb.clearSidebar(p);
            if (type.teamSize() > 1) sb.resetNametag(p);
        }
    }

    // =========================================================================================================
    // End
    // =========================================================================================================
    private void checkWin() {
        if (state != GameState.PLAYING || shutDown) return;
        List<SkywarsTeam> left = new ArrayList<>();
        for (SkywarsTeam t : teams) if (t.alive()) left.add(t);
        if (left.size() <= 1) end(left.isEmpty() ? null : left.get(0));
    }

    private void end(SkywarsTeam won) {
        if (state == GameState.ENDING || shutDown) return;
        winner = won;
        setState(GameState.ENDING);
        endSeconds = 0;
        if (dragon != null && !dragon.isDead()) dragon.remove();

        List<String> block = new ArrayList<>();
        block.add(Chat.BAR);
        block.add(Chat.center(ChatColor.WHITE + "" + ChatColor.BOLD + "SkyWars"));
        block.add("");
        if (won == null) {
            block.add(Chat.center(ChatColor.YELLOW + "Winner " + ChatColor.GRAY + "- " + ChatColor.GRAY + "Nobody"));
        } else {
            StringBuilder w = new StringBuilder();
            for (UUID m : won.members) {
                if (w.length() > 0) w.append(ChatColor.GRAY).append(", ");
                w.append(fullName(m, names.get(m)));
            }
            block.add(Chat.center(ChatColor.YELLOW + (won.members.size() > 1 ? "Winners " : "Winner ") + ChatColor.GRAY + "- " + w));
        }
        block.add("");
        List<Map.Entry<UUID, Integer>> top = new ArrayList<>(kills.entrySet());
        Collections.sort(top, new Comparator<Map.Entry<UUID, Integer>>() {
            @Override public int compare(Map.Entry<UUID, Integer> a, Map.Entry<UUID, Integer> b) { return b.getValue() - a.getValue(); }
        });
        String[] labels = {ChatColor.YELLOW + "" + ChatColor.BOLD + "1st Killer", ChatColor.GOLD + "" + ChatColor.BOLD + "2nd Killer",
                ChatColor.RED + "" + ChatColor.BOLD + "3rd Killer"};
        int shown = 0;
        for (Map.Entry<UUID, Integer> e : top) {
            if (shown >= 3 || e.getValue() <= 0) break;
            block.add(Chat.center(labels[shown] + ChatColor.GRAY + " - " + coloredName(e.getKey()) + ChatColor.GRAY + " - " + e.getValue()));
            shown++;
        }
        if (shown > 0) block.add("");
        block.add(Chat.BAR);

        String winName;
        if (won == null || won.members.isEmpty()) winName = ChatColor.GRAY + "Nobody";
        else if (type.teamSize() > 1) winName = won.color.chat + won.color.displayName + " Team";
        else winName = coloredName(won.members.get(0));
        for (Player p : players()) {
            for (String l : block) p.sendMessage(l);
            // the same end-screen contract as Bed Wars: the mode is remembered for /playagain and the item is handed
            // out, so a SkyWars player can ask for another game exactly the way a Bed Wars player can
            net.h2020.api.core.LastGame.record(p, type, mapName());
            net.h2020.api.core.PlayAgain.giveItem(p);
            boolean isWinner = won != null && won.members.contains(p.getUniqueId());
            if (isWinner) {
                Titles.title(p, ChatColor.GOLD + "" + ChatColor.BOLD + "VICTORY!",
                        ChatColor.GRAY + (type.teamSize() > 1 ? "Your team was the last one standing!" : "You were the last man standing!"), 5, 80, 20);
                p.sendMessage(net.h2020.api.core.Messages.get("skywars.game.coins-win", "amount", insane() ? 50 : 20));
                p.playSound(p.getLocation(), Sound.LEVEL_UP, 1f, 1f);
            } else {
                Titles.title(p, ChatColor.RED + "" + ChatColor.BOLD + "GAME OVER!",
                        won == null ? ChatColor.GRAY + "Nobody won this time!" : winName + ChatColor.GRAY + " won the game!", 5, 80, 20);
            }
        }
        for (Player p : players())
            net.h2020.api.core.PlayAgain.sendPrompt(p, won != null && won.members.contains(p.getUniqueId()), false);
        updateBoards();
    }

    private void launchWinnerFireworks() {
        if (winner == null) return;
        for (UUID m : winner.members) {
            Player p = Bukkit.getPlayer(m);
            if (p == null || !alive.contains(m) || !p.getWorld().equals(world)) continue;
            Firework fw = world.spawn(p.getLocation().add(random.nextDouble() * 4 - 2, 1, random.nextDouble() * 4 - 2), Firework.class);
            FireworkMeta meta = fw.getFireworkMeta();
            FireworkEffect.Type[] types = FireworkEffect.Type.values();
            Color[] colors = {Color.RED, Color.ORANGE, Color.YELLOW, Color.LIME, Color.AQUA, Color.FUCHSIA, Color.WHITE};
            meta.addEffect(FireworkEffect.builder().with(types[random.nextInt(types.length)])
                    .withColor(colors[random.nextInt(colors.length)]).withFade(colors[random.nextInt(colors.length)])
                    .flicker(random.nextBoolean()).trail(random.nextBoolean()).build());
            meta.setPower(1);
            fw.setFireworkMeta(meta);
        }
    }

    // =========================================================================================================
    // Scoreboard
    // =========================================================================================================
    private void updateBoards() {
        ScoreboardService sb = H2020.find(ScoreboardService.class);
        if (sb == null) return;
        String title = ChatColor.YELLOW + "" + ChatColor.BOLD + "SKYWARS";
        String mode = insane() ? ChatColor.RED + "Insane" : ChatColor.GREEN + "Normal";
        boolean waiting = preGame();
        SidebarTemplate board = Sidebars.get(waiting ? "skywars-waiting" : "skywars-playing");
        if (board != null) {
            TimedEvent next = nextEvent < events.size() ? events.get(nextEvent) : null;
            String event = next == null || state == GameState.ENDING
                    ? ChatColor.GREEN + "None"
                    : ChatColor.GREEN + next.label + " " + Chat.clock(next.at - elapsed);
            int teamsLeft = 0;
            for (SkywarsTeam t : teams) if (t.alive()) teamsLeft++;
            for (Player p : players()) {
                Map<String, String> vars = new HashMap<String, String>();
                vars.put("date", date);
                vars.put("id", id);
                vars.put("players", String.valueOf(players.size()));
                vars.put("max", String.valueOf(maxPlayers()));
                vars.put("countdown", String.valueOf(Math.max(0, countdown + 1)));
                vars.put("map", this.template.name());
                vars.put("mode", mode);
                vars.put("event", event);
                vars.put("alive", String.valueOf(alive.size()));
                vars.put("teams", String.valueOf(teamsLeft));
                vars.put("kills", String.valueOf(kills(p)));
                Set<String> flags = new HashSet<String>();
                flags.add(waiting ? "waiting" : "playing");
                flags.add(type.teamSize() > 1 ? "teams" : "solo");
                flags.add(insane() ? "insane" : "normal");
                if (waiting) flags.add(state == GameState.STARTING ? "starting" : "idle");
                sb.setSidebar(p, board.renderTitle(p, vars), board.render(p, vars, flags, null));
            }
            return;
        }
        for (Player p : players()) {
            List<String> l = new ArrayList<>();
            l.add(ChatColor.GRAY + date + "  " + ChatColor.DARK_GRAY + id);
            l.add(blank(0));
            if (preGame()) {
                l.add("Players: " + ChatColor.GREEN + players.size() + "/" + maxPlayers());
                l.add(blank(1));
                l.add(state == GameState.STARTING ? "Starting in " + ChatColor.GREEN + Math.max(0, countdown + 1) + "s" : "Waiting...");
                l.add(blank(2));
                l.add("Map: " + ChatColor.GREEN + template.name());
                l.add("Mode: " + mode);
            } else {
                TimedEvent next = nextEvent < events.size() ? events.get(nextEvent) : null;
                l.add("Next Event:");
                l.add(next == null || state == GameState.ENDING ? ChatColor.GREEN + "None"
                        : ChatColor.GREEN + next.label + " " + Chat.clock(next.at - elapsed));
                l.add(blank(1));
                l.add("Players left: " + ChatColor.GREEN + alive.size());
                if (type.teamSize() > 1) {
                    int tl = 0;
                    for (SkywarsTeam t : teams) if (t.alive()) tl++;
                    l.add("Teams left: " + ChatColor.GREEN + tl);
                }
                l.add(blank(2));
                l.add("Kills: " + ChatColor.GREEN + kills(p));
                l.add(blank(3));
                l.add("Map: " + ChatColor.GREEN + template.name());
                l.add("Mode: " + mode);
            }
            l.add(blank(4));
            l.add(ChatColor.YELLOW + "www.hypixel.net");
            sb.setSidebar(p, title, l);
        }
    }

    /** Distinct invisible blank lines (sidebar entries must be unique). */
    private static String blank(int i) { return ChatColor.values()[i].toString() + ChatColor.RESET; }

    // =========================================================================================================
    private void broadcast(String msg) {
        for (Player p : players()) p.sendMessage(msg);
        // mirror game messages to the console like a real server's chat log
        if (msg != null && !ChatColor.stripColor(msg).trim().isEmpty()) Bukkit.getLogger().info("[" + id + "] " + ChatColor.stripColor(msg));
    }

    private static List<Player> online(Set<UUID> ids) {
        List<Player> l = new ArrayList<>();
        for (UUID u : ids) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) l.add(p);
        }
        return l;
    }
}
