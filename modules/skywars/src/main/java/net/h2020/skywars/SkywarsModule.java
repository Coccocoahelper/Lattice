package net.h2020.skywars;

import net.h2020.api.H2020;
import net.h2020.api.Module;
import net.h2020.api.game.Game;
import net.h2020.api.game.GameFamily;
import net.h2020.api.game.GameManager;
import net.h2020.api.game.GameProvider;
import net.h2020.api.game.GameType;
import net.h2020.api.map.MapTemplate;
import net.h2020.skywars.game.SkywarsGame;
import net.h2020.skywars.game.SkywarsListener;
import net.h2020.skywars.kit.Kit;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

/**
 * Hypixel SkyWars (summer 2020): solo/teams, Normal/Insane. Registers a SKYWARS GameProvider with core's
 * GameManager; each game is a SkywarsGame (implements SkywarsView) in its own map instance world.
 *
 * Optional config overrides (section "skywars"): min-players (int, or per mode under min-players.<mode id>).
 */
public final class SkywarsModule implements Module, GameProvider {
    private JavaPlugin plugin;
    private SkywarsListener listener;
    private final List<SkywarsGame> games = new CopyOnWriteArrayList<>();
    private final Map<UUID, Kit> kits = new ConcurrentHashMap<>();
    private boolean registered;

    @Override public String name() { return "skywars"; }

    @Override
    public void enable(JavaPlugin plugin) {
        this.plugin = plugin;
        listener = new SkywarsListener(this);
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        if (!tryRegister()) {
            // core may register GameManager late; keep trying for a while
            new BukkitRunnable() {
                int tries;
                @Override public void run() {
                    if (tryRegister() || ++tries > 60) {
                        if (!registered) plugin().getLogger().warning("[skywars] GameManager not available; SkyWars provider not registered");
                        cancel();
                    }
                }
            }.runTaskTimer(plugin, 20L, 20L);
        }
    }

    private boolean tryRegister() {
        if (registered) return true;
        GameManager gm = H2020.find(GameManager.class);
        if (gm == null) return false;
        gm.registerProvider(this);
        registered = true;
        return true;
    }

    @Override
    public void disable() {
        for (SkywarsGame g : new ArrayList<>(games)) {
            try { g.shutdown(); } catch (Throwable t) { plugin.getLogger().log(Level.WARNING, "SkyWars shutdown " + g.id(), t); }
        }
        games.clear();
        if (listener != null) HandlerList.unregisterAll(listener);
    }

    // ------------------------------------------------------------------ GameProvider
    @Override public GameFamily family() { return GameFamily.SKYWARS; }

    @Override
    public Game create(net.h2020.api.game.GameMode type, MapTemplate map, String gameId) {
        if (type.family() != GameFamily.SKYWARS) throw new IllegalArgumentException("Not a SkyWars mode: " + type);
        SkywarsGame g = new SkywarsGame(this, type, map, gameId);
        games.add(g);
        return g;
    }

    // ------------------------------------------------------------------ registry
    public JavaPlugin plugin() { return plugin; }

    public Collection<SkywarsGame> games() { return Collections.unmodifiableList(games); }

    public void unregister(SkywarsGame g) { games.remove(g); }

    public SkywarsGame gameOf(Player p) {
        for (SkywarsGame g : games) if (g.contains(p)) return g;
        return null;
    }

    public SkywarsGame gameIn(World w) {
        for (SkywarsGame g : games) if (g.world().equals(w)) return g;
        return null;
    }

    // ------------------------------------------------------------------ kits (remembered per player for the session)
    public Kit selectedKit(Player p) {
        Kit k = kits.get(p.getUniqueId());
        return k == null ? Kit.DEFAULT : k;
    }

    public void selectKit(Player p, Kit k) { kits.put(p.getUniqueId(), k); }

    /** Bots never return with the same UUID, so their kit choice is dropped on quit. */
    public void forgetKit(Player p) { kits.remove(p.getUniqueId()); }

    // ------------------------------------------------------------------ config
    public int minPlayers(net.h2020.api.game.GameMode type, int def) {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("skywars");
        if (s == null) return def;
        if (s.isInt("min-players." + type.id())) return s.getInt("min-players." + type.id());
        if (s.isInt("min-players")) return s.getInt("min-players");
        return def;
    }
}
