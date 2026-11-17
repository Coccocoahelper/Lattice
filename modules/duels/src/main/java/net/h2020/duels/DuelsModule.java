package net.h2020.duels;

import net.h2020.api.H2020;
import net.h2020.api.Module;
import net.h2020.api.game.Game;
import net.h2020.api.game.GameFamily;
import net.h2020.api.game.GameManager;
import net.h2020.api.game.GameMode;
import net.h2020.api.game.GameModes;
import net.h2020.api.game.GameProvider;
import net.h2020.api.map.MapService;
import net.h2020.api.map.MapTemplate;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Duels as a family: eight modes, one arena per match, two players.
 *
 * <p>Everything that makes it a family rather than a special case is here - the provider, the mode registration, the
 * template registration so the queue can find a map, and the lookup a listener uses to go from a body to its duel.
 * The rules live in {@link DuelsGame} and the kits in {@link DuelsKit}; this class is wiring.
 */
public final class DuelsModule implements Module, GameProvider {

    private JavaPlugin plugin;
    private DuelsListener listener;
    private final List<DuelsGame> games = new CopyOnWriteArrayList<DuelsGame>();
    private final Map<UUID, DuelsGame> byPlayer = new ConcurrentHashMap<UUID, DuelsGame>();
    private final Map<String, DuelsMap> maps = new ConcurrentHashMap<String, DuelsMap>();
    private final Map<String, DuelsMap> mapOfGame = new ConcurrentHashMap<String, DuelsMap>();
    private boolean registered;

    @Override public String name() { return "duels"; }

    @Override
    public void enable(JavaPlugin plugin) {
        this.plugin = plugin;
        listener = new DuelsListener(this);
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        for (DuelsMode m : DuelsMode.ALL) {
            GameModes.register(m);
            DuelsMap dm = new DuelsMap(m);
            maps.put(m.id(), dm);
            MapService ms = H2020.find(MapService.class);
            if (ms != null) ms.registerTemplate(dm);
        }
        if (!tryRegister()) {
            org.bukkit.scheduler.BukkitRunnable r = new org.bukkit.scheduler.BukkitRunnable() {
                int tries;
                @Override public void run() {
                    if (tryRegister() || ++tries > 60) {
                        if (!registered) plugin().getLogger().warning("[duels] GameManager not available");
                        cancel();
                    }
                }
            };
            r.runTaskTimer(plugin, 20L, 20L);
        }
    }

    private boolean tryRegister() {
        if (registered) return true;
        GameManager gm = H2020.find(GameManager.class);
        if (gm == null) return false;
        gm.registerProvider(this);
        // templates can only be registered once maps are up, so retry them here as well
        MapService ms = H2020.find(MapService.class);
        if (ms != null) for (DuelsMap m : maps.values()) ms.registerTemplate(m);
        registered = true;
        return true;
    }

    @Override
    public void disable() {
        for (DuelsGame g : new ArrayList<DuelsGame>(games)) {
            try { g.shutdown(); } catch (Throwable ignored) { }
        }
        games.clear();
        byPlayer.clear();
        MapService ms = H2020.find(MapService.class);
        if (ms != null) for (DuelsMap m : maps.values()) { try { ms.unregisterTemplate(m); } catch (Throwable ignored) { } }
        if (listener != null) HandlerList.unregisterAll(listener);
    }

    // ---------------------------------------------------------------- GameProvider

    @Override public GameFamily family() { return GameFamily.DUELS; }

    @Override
    public Game create(GameMode type, MapTemplate map, String gameId) {
        if (type.family() != GameFamily.DUELS) throw new IllegalArgumentException("not a duels mode: " + type);
        DuelsMode mode = DuelsMode.byId(type.id());
        if (mode == null) throw new IllegalArgumentException("unknown duels mode: " + type.id());
        // A fresh map per game, always built for THIS mode. templatesFor() matches a virtual template on family and
        // team count alone, so every duels template answers to every duels mode - and reusing the one the queue handed
        // over gave a Classic duel the Sumo platform, which is six blocks wide: both players spawned over the void at
        // z=16 and fell to y=35 (measured).
        DuelsMap dm = new DuelsMap(mode);
        DuelsGame g = new DuelsGame(this, mode, dm, gameId);
        games.add(g);
        mapOfGame.put(gameId, dm);
        return g;
    }

    // ---------------------------------------------------------------- registry

    public JavaPlugin plugin() { return plugin; }

    public List<DuelsGame> games() { return Collections.unmodifiableList(games); }

    public void unregister(DuelsGame g) {
        games.remove(g);
        mapOfGame.remove(g.id());
        for (Map.Entry<UUID, DuelsGame> e : byPlayer.entrySet()) if (e.getValue() == g) byPlayer.remove(e.getKey());
    }

    public void markStarted(DuelsGame g) {
        for (Player p : g.players()) byPlayer.put(p.getUniqueId(), g);
    }

    public DuelsGame gameOf(Player p) {
        if (p == null) return null;
        DuelsGame g = byPlayer.get(p.getUniqueId());
        if (g != null) return g;
        for (DuelsGame x : games) if (x.players().contains(p)) { byPlayer.put(p.getUniqueId(), x); return x; }
        return null;
    }

    public DuelsGame gameIn(World w) {
        for (DuelsGame g : games) if (g.world().equals(w)) return g;
        return null;
    }

    /** The mode a name or id stands for, for the command and the menu. */
    public static DuelsMode mode(String token) { return DuelsMode.byName(token); }

    /** Every mode, in menu order. */
    public static List<DuelsMode> modes() {
        List<DuelsMode> l = new ArrayList<DuelsMode>();
        for (DuelsMode m : DuelsMode.ALL) l.add(m);
        return l;
    }
}
