package net.h2020.core.game;

import net.h2020.api.H2020;
import net.h2020.api.bot.Bot;
import net.h2020.api.bot.BotService;
import net.h2020.api.game.Game;
import net.h2020.api.game.GameFamily;
import net.h2020.api.game.GameManager;
import net.h2020.api.game.GameProvider;
import net.h2020.api.game.GameState;
import net.h2020.api.game.GameType;
import net.h2020.api.game.events.GameKillEvent;
import net.h2020.api.game.events.GameStateChangeEvent;
import net.h2020.api.map.MapService;
import net.h2020.api.map.MapTemplate;
import net.h2020.core.CoreConfig;
import net.h2020.core.CoreUtil;
import net.h2020.core.HypixelFormat;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Matchmaking and game lifecycle: providers per family, Hypixel-style instance ids, fullest-game-first queueing,
 * lobby routing with a full player reset, bot churn when games end. Bot queue filling lives in BotQueueFiller.
 */
public final class GameManagerImpl implements GameManager, Listener {
    private final JavaPlugin plugin;
    private final CoreConfig config;
    private final Map<GameFamily, GameProvider> providers = new EnumMap<>(GameFamily.class);
    private final Map<String, Game> games = new LinkedHashMap<>();
    /** world name -> game, readable from the async chat thread */
    private final Map<String, Game> byWorld = new ConcurrentHashMap<>();
    private final Set<String> usedIds = new HashSet<>();
    /** bots that were put into each game (by id), for churn when it ends */
    private final Map<String, Set<UUID>> botMembers = new HashMap<>();
    private final Set<UUID> routing = new HashSet<>();
    private LobbyHooks lobby;
    private BotQueueFiller filler;

    public GameManagerImpl(JavaPlugin plugin, CoreConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void setLobbyHooks(LobbyHooks hooks) { this.lobby = hooks; }
    public void setFiller(BotQueueFiller filler) { this.filler = filler; }

    // ---- providers / creation ----

    @Override
    public void registerProvider(GameProvider provider) {
        providers.put(provider.family(), provider);
        plugin.getLogger().info("Game provider registered for " + provider.family());
    }

    public boolean hasProvider(GameFamily f) { return providers.containsKey(f); }

    @Override
    public Game create(net.h2020.api.game.GameMode type, MapTemplate map) {
        GameProvider provider = providers.get(type.family());
        if (provider == null || map == null) return null;
        String id = nextId(type.family());
        Game g;
        try {
            g = provider.create(type, map, id);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "Could not create " + type.id() + " on " + map.name(), t);
            usedIds.remove(id);
            return null;
        }
        if (g == null) { usedIds.remove(id); return null; }
        register(g);
        return g;
    }

    /** New game of this type on a random usable template, or null when none exist. */
    public Game createRandom(net.h2020.api.game.GameMode type) {
        MapService ms = H2020.find(MapService.class);
        if (ms == null) return null;
        List<MapTemplate> maps;
        try { maps = ms.templatesFor(type); } catch (Throwable t) { return null; }
        if (maps == null || maps.isEmpty()) return null;
        List<MapTemplate> shuffled = new ArrayList<>(maps);
        Collections.shuffle(shuffled);
        // A slime copy has to exist before a world can be handed out, and the pool can be empty for a moment after a
        // burst of games. Maps whose copy is already built go first so a join does not wait on a rebuild.
        List<MapTemplate> readyFirst = new ArrayList<>();
        List<MapTemplate> rest = new ArrayList<>();
        for (MapTemplate m : shuffled) {
            if (net.h2020.api.map.SlimeWorlds.ready(m.name())) readyFirst.add(m); else rest.add(m);
        }
        readyFirst.addAll(rest);
        shuffled = readyFirst;
        // avoid the maps currently waiting for this type when possible (Hypixel rotates)
        for (MapTemplate m : shuffled) {
            boolean waiting = false;
            for (Game g : games.values()) if (g.type() == type && m.name().equals(g.mapName()) && joinable(g)) waiting = true;
            if (!waiting) return create(type, m);
        }
        return create(type, shuffled.get(0));
    }

    private void register(Game g) {
        if (games.containsKey(g.id())) return;
        games.put(g.id(), g);
        usedIds.add(g.id());
        if (g.world() != null) byWorld.put(g.world().getName(), g);
    }

    private String nextId(GameFamily family) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 1000; attempt++) {
            String id = family == GameFamily.BEDWARS
                    ? HypixelFormat.bedwarsId(1 + r.nextInt(399), HypixelFormat.randomLetter())
                    : HypixelFormat.skywarsId(1 + r.nextInt(249), HypixelFormat.randomLetter());
            if (usedIds.add(id)) return id;
        }
        String id = (family == GameFamily.BEDWARS ? "mini" : "m") + (400 + usedIds.size()) + "Z";
        usedIds.add(id);
        return id;
    }

    // ---- queries ----

    static boolean joinable(Game g) {
        GameState s = g.state();
        return (s == GameState.WAITING || s == GameState.STARTING) && g.privateCode() == null && g.players().size() < g.playerCap();
    }

    /** The fullest joinable game of a type (Hypixel fills the most populated lobby first). */
    public Game findJoinable(net.h2020.api.game.GameMode type) {
        Game best = null;
        int bestCount = -1;
        for (Game g : games.values()) {
            if (g.type() != type || !joinable(g)) continue;
            int c = g.players().size();
            if (c > bestCount) { best = g; bestCount = c; }
        }
        return best;
    }

    @Override
    public Game gameOf(Player player) {
        for (Game g : games.values()) {
            try { if (g.players().contains(player)) return g; } catch (Throwable ignored) {}
        }
        return null;
    }

    /** Game whose world this is (thread-safe; used by chat). */
    public Game gameByWorld(World w) { return w == null ? null : byWorld.get(w.getName()); }

    public Game byId(String id) {
        for (Game g : games.values()) if (g.id().equalsIgnoreCase(id)) return g;
        return null;
    }

    @Override public Collection<Game> games() { return Collections.unmodifiableCollection(new ArrayList<>(games.values())); }

    /** Players in games of this type. */
    public int playing(net.h2020.api.game.GameMode type) {
        int n = 0;
        for (Game g : games.values()) if (g.type() == type) n += g.players().size();
        return n;
    }

    /** Players in games of this family plus its lobby. */
    public int playing(GameFamily family) {
        int n = 0;
        for (Game g : games.values()) if (g.type().family() == family) n += g.players().size();
        if (lobby != null) {
            World w = lobby.lobbyWorld(family);
            World main = lobby.lobbyWorld(null);
            if (w != null && !w.equals(main)) n += w.getPlayers().size();
        }
        return n;
    }

    // ---- queue ----

    @Override
    public Game createPrivate(net.h2020.api.game.GameMode type, MapTemplate map, String code, int maxPlayers) {
        Game g = create(type, map);
        if (g == null) return null;
        g.makePrivate(code == null ? nextId(type.family()) : code.toLowerCase(), maxPlayers);
        return g;
    }

    @Override
    public Game byCode(String code) {
        if (code == null) return null;
        for (Game g : games.values()) {
            if (code.equalsIgnoreCase(g.privateCode())) {
                GameState s = g.state();
                if (s == GameState.WAITING || s == GameState.STARTING) return g;
            }
        }
        return null;
    }

    @Override
    public Game queue(Player player, net.h2020.api.game.GameMode type) {
        return queueAttempt(player, type, 0);
    }

    /**
     * One queue pass. A human whose game could not be created is retried a second later rather than told to come
     * back later: the usual cause is a map copy that is still being built, and it is ready almost at once.
     */
    private Game queueAttempt(final Player player, final net.h2020.api.game.GameMode type, final int retries) {
        if (player == null || type == null || !player.isOnline()) return null;
        Game current = gameOf(player);
        if (current != null) {
            if (current.type() == type && joinable(current)) return current;
            leaveGame(player, current);
        }
        boolean human = !CoreUtil.isBot(player);
        if (!providers.containsKey(type.family())) {
            if (human) player.sendMessage(ChatColor.RED + "That game is currently unavailable!");
            return null;
        }
        Set<String> tried = new HashSet<>();
        for (int attempt = 0; attempt < 3; attempt++) {
            Game g = findJoinable(type);
            if (g != null && tried.contains(g.id())) g = null;
            // bots only join games that already exist: a requeueing bot must not spin up a new bot-only game
            // (that would keep games running forever with nobody watching; background-games covers that case)
            if (g == null && !human) break;
            if (g == null) g = createRandom(type);
            if (g == null) {
                if (human && retries < 3) {
                    Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                        @Override public void run() {
                            if (player.isOnline() && gameOf(player) == null) queueAttempt(player, type, retries + 1);
                        }
                    }, 20L);
                    return null;
                }
                break;
            }
            tried.add(g.id());
            if (human) player.sendMessage(ChatColor.GREEN + "Sending you to " + g.id() + "!");
            if (lobby != null) lobby.leaving(player);
            resetPlayer(player, org.bukkit.GameMode.ADVENTURE);
            boolean ok;
            try { ok = g.addPlayer(player); } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "addPlayer failed in " + g.id(), t);
                ok = false;
            }
            if (ok) {
                if (!human) trackBot(g, player);
                return g;
            }
        }
        if (human) {
            boolean haveMap = true;
            try {
                net.h2020.api.map.MapService ms = H2020.find(net.h2020.api.map.MapService.class);
                haveMap = ms != null && !ms.templatesFor(type).isEmpty();
            } catch (Throwable ignored) { }
            player.sendMessage(haveMap
                    ? ChatColor.RED + "Could not find a game for " + type.displayName() + "! Please try again later."
                    : ChatColor.RED + "No map is installed for " + type.displayName() + ": put the map folders back in"
                        + " plugins/Lattice/maps/ and the slime files in run/slime_worlds/.");
            if (gameOf(player) == null && lobby != null) sendToLobby(player, type.family());
        }
        return null;
    }

    public void trackBot(Game g, Player bot) {
        Set<UUID> s = botMembers.get(g.id());
        if (s == null) { s = new HashSet<>(); botMembers.put(g.id(), s); }
        s.add(bot.getUniqueId());
    }

    /** Leave a game without the lobby trip (removePlayer, guarded). */
    public void leaveGame(Player p, Game g) {
        if (g == null) return;
        try {
            if (g.state() != GameState.ENDING || g.players().contains(p)) g.removePlayer(p);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "removePlayer failed in " + g.id(), t);
        }
    }

    // ---- lobby routing ----

    @Override
    public void sendToLobby(Player player, GameFamily family) {
        if (player == null || !player.isOnline()) return;
        if (!routing.add(player.getUniqueId())) return; // re-entrant call from a game's removePlayer
        try {
            Game g = gameOf(player);
            if (g != null && g.state() != GameState.ENDING) leaveGame(player, g);
            resetPlayer(player, org.bukkit.GameMode.ADVENTURE);
            World w = lobby != null ? lobby.lobbyWorld(family) : Bukkit.getWorlds().get(0);
            Location spawn = lobby != null ? lobby.spawn(w) : w.getSpawnLocation().add(0.5, 0, 0.5);
            player.teleport(spawn);
            player.setFallDistance(0);
            if (lobby != null) lobby.entered(player, family);
        } finally {
            routing.remove(player.getUniqueId());
        }
    }

    /** Full reset: inventory, armor, effects, health, food, xp, gamemode, flight, fire. */
    public static void resetPlayer(Player p, org.bukkit.GameMode mode) {
        try { p.closeInventory(); } catch (Throwable ignored) {}
        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        p.setItemOnCursor(null);
        for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
        if (p.isDead()) return;
        p.setMaxHealth(20.0);
        p.setHealth(20.0);
        p.setFoodLevel(20);
        p.setSaturation(5f);
        p.setExhaustion(0f);
        p.setExp(0f);
        p.setLevel(0);
        p.setTotalExperience(0);
        if (p.getGameMode() != mode) p.setGameMode(mode);
        p.setFlying(false);
        p.setAllowFlight(false);
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.setWalkSpeed(0.2f);
        p.setFlySpeed(0.1f);
        p.setVelocity(new Vector(0, 0, 0));
        if (p.isInsideVehicle()) p.leaveVehicle();
        p.updateInventory();
    }

    // ---- teardown & churn ----

    @Override
    public void finished(Game game) {
        if (game == null) return;
        games.remove(game.id());
        if (game.world() != null) byWorld.remove(game.world().getName());
        // the plugin can be shutting down while a game finishes: registering a task then throws and takes the
        // teardown with it, which is exactly when world cleanup matters
        try {
            Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                @Override public void run() { usedIds.remove(game.id()); }
            }, 20L * 60 * 5);
        } catch (Throwable ignored) { usedIds.remove(game.id()); }
        if (filler != null) filler.forget(game);
        Set<UUID> bots = botMembers.remove(game.id());
        Set<UUID> involved = new HashSet<>();
        if (bots != null) involved.addAll(bots);
        try { for (Player p : game.players()) involved.add(p.getUniqueId()); } catch (Throwable ignored) {}
        World w = game.world();
        if (w != null) for (Player p : w.getPlayers()) involved.add(p.getUniqueId());
        BotService bs = H2020.find(BotService.class);
        for (UUID id : involved) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) continue;
            boolean inWorld = w != null && w.equals(p.getWorld());
            Game other = gameOf(p);
            if (other != null && other != game) continue; // already moved on
            Bot bot = bs != null ? bs.bot(p) : null;
            if (bot != null || CoreUtil.isBot(p)) {
                churnBot(p, bot, game.type(), inWorld);
            } else if (inWorld) {
                sendToLobby(p, game.type().family());
            }
        }
    }

    /** Requeue / lobby / disconnect with human-like delays. */
    private void churnBot(final Player p, final Bot bot, final net.h2020.api.game.GameMode type, boolean inGameWorld) {
        double roll = ThreadLocalRandom.current().nextDouble();
        if (roll < config.requeueChance) {
            if (inGameWorld || !isInLobby(p)) sendToLobby(p, type.family());
            later(40 + ThreadLocalRandom.current().nextInt(200), new Runnable() {
                @Override public void run() { if (p.isOnline() && gameOf(p) == null) queue(p, type); }
            });
        } else if (roll < config.requeueChance + config.lobbyChance) {
            if (inGameWorld || !isInLobby(p)) sendToLobby(p, type.family());
        } else if (inGameWorld) {
            removeBot(p, bot);
        } else {
            later(20 + ThreadLocalRandom.current().nextInt(200), new Runnable() {
                @Override public void run() { if (p.isOnline() && gameOf(p) == null) removeBot(p, bot); }
            });
        }
    }

    private boolean isInLobby(Player p) {
        if (lobby == null) return false;
        World w = p.getWorld();
        if (w.equals(lobby.lobbyWorld(null))) return true;
        for (GameFamily f : GameFamily.values()) if (w.equals(lobby.lobbyWorld(f))) return true;
        return false;
    }

    private static void removeBot(Player p, Bot bot) {
        if (bot != null && !bot.isRemoved()) bot.remove();
        else if (bot == null) p.kickPlayer("Disconnected");
    }

    private void later(long ticks, Runnable r) { Bukkit.getScheduler().runTaskLater(plugin, r, ticks); }

    // ---- events ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onState(GameStateChangeEvent e) {
        Game g = e.game();
        if (g == null) return;
        if (!games.containsKey(g.id()) && e.to() != GameState.ENDING) register(g);
        if (e.to() == GameState.PLAYING && filler != null) filler.forget(g);
    }

    /** Eliminated bots leave early like real players do (or requeue). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(final GameKillEvent e) {
        final Player victim = e.victim();
        if (victim == null || !e.finalKill() || !CoreUtil.isBot(victim)) return;
        if (ThreadLocalRandom.current().nextDouble() >= config.leaveAfterElimination) return;
        final Game game = e.game();
        later(60 + ThreadLocalRandom.current().nextInt(300), new Runnable() {
            @Override public void run() {
                if (!victim.isOnline() || game == null || gameOf(victim) != game) return;
                if (game.state() == GameState.ENDING) return;
                BotService bs = H2020.find(BotService.class);
                Bot bot = bs != null ? bs.bot(victim) : null;
                Set<UUID> s = botMembers.get(game.id());
                if (s != null) s.remove(victim.getUniqueId());
                leaveGame(victim, game);
                double r = ThreadLocalRandom.current().nextDouble();
                if (r < 0.55) {
                    // "/play again"
                    sendToLobby(victim, game.type().family());
                    later(10 + ThreadLocalRandom.current().nextInt(60), new Runnable() {
                        @Override public void run() { if (victim.isOnline() && gameOf(victim) == null) queue(victim, game.type()); }
                    });
                } else if (r < 0.8) {
                    sendToLobby(victim, game.type().family());
                } else {
                    removeBot(victim, bot);
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Game g = gameOf(p);
        if (g != null) leaveGame(p, g);
        for (Set<UUID> s : botMembers.values()) s.remove(p.getUniqueId());
    }

    /** Shut every running game down (plugin disable). */
    public void shutdownAll() {
        for (Game g : new ArrayList<>(games.values())) {
            try { g.shutdown(); } catch (Throwable t) { plugin.getLogger().log(Level.WARNING, "shutdown failed for " + g.id(), t); }
        }
        games.clear();
        byWorld.clear();
    }

    /** Spawn a dummy lookup used by the filler/background tasks. */
    public BukkitTask schedule(Runnable r, long period) { return Bukkit.getScheduler().runTaskTimer(plugin, r, period, period); }
}
