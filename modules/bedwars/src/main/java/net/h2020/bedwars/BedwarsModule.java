package net.h2020.bedwars;

import net.h2020.api.H2020;
import net.h2020.api.Module;
import net.h2020.api.game.BedwarsShopItem;
import net.h2020.api.game.Game;
import net.h2020.api.game.GameFamily;
import net.h2020.api.game.GameManager;
import net.h2020.api.game.GameProvider;
import net.h2020.api.game.GameType;
import net.h2020.api.map.MapTemplate;
import net.h2020.bedwars.shop.ShopCatalog;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Bed Wars (Hypixel, summer 2020). Registers a {@link GameProvider} for {@link GameFamily#BEDWARS}; every match is a
 * {@link BedwarsGame} in its own world instance. One listener and one tick task serve all games (dispatch by world).
 */
public final class BedwarsModule implements Module, GameProvider {
    private JavaPlugin plugin;
    private BedwarsSettings settings;
    private GameListener listener;
    private BukkitTask ticker, registrar;
    private boolean registered;

    private final Map<World, BedwarsGame> games = new ConcurrentHashMap<>();
    /** player -> game (concurrent: read from the async chat thread). */
    private final Map<UUID, BedwarsGame> byPlayer = new ConcurrentHashMap<>();
    /** Per-player Quick Buy layout (kept for the session, like Hypixel's per-account layout). */
    private final Map<UUID, BedwarsShopItem[]> quickBuy = new ConcurrentHashMap<>();

    @Override public String name() { return "bedwars"; }

    @Override
    public void enable(JavaPlugin plugin) {
        // shops/bedwars.yml is generated from the code catalogue, then applied over it: prices and enabled lines
        // are editable without a rebuild
        java.io.File shops = new java.io.File(plugin.getDataFolder(), "shops/bedwars.yml");
        net.h2020.bedwars.shop.ShopCatalog.export(shops);
        net.h2020.bedwars.shop.ShopCatalog.load(shops);
        this.plugin = plugin;
        this.settings = BedwarsSettings.load(plugin);
        H2020.register(net.h2020.api.game.BedwarsTools.class, new BedwarsAdminTools(this));
        listener = new GameListener(this);
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() {
                for (BedwarsGame g : new ArrayList<>(games.values())) g.tick();
            }
        }, 1L, 1L);
        PluginCommand shout = plugin.getCommand("shout");
        if (shout != null) shout.setExecutor(new ShoutCommand());
        if (!tryRegister()) {
            // core registers GameManager first, but stay robust if it shows up late
            registrar = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                int attempts;
                @Override public void run() {
                    if (tryRegister() || ++attempts > 60) {
                        if (!registered) plugin.getLogger().warning("[bedwars] GameManager not available; Bed Wars games can't be queued");
                        registrar.cancel();
                    }
                }
            }, 20L, 20L);
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
        for (BedwarsGame g : new ArrayList<>(games.values())) {
            try { g.shutdown(); } catch (Throwable t) { plugin.getLogger().log(Level.WARNING, "[bedwars] shutdown failed", t); }
        }
        games.clear();
        byPlayer.clear();
        if (ticker != null) ticker.cancel();
        if (registrar != null) registrar.cancel();
        if (listener != null) HandlerList.unregisterAll(listener);
    }

    // =================================================================== GameProvider

    @Override public GameFamily family() { return GameFamily.BEDWARS; }

    @Override
    public Game create(net.h2020.api.game.GameMode type, MapTemplate map, String gameId) {
        if (type.family() != GameFamily.BEDWARS) throw new IllegalArgumentException("Not a Bed Wars mode: " + type);
        BedwarsGame g = new BedwarsGame(this, type, map, gameId);
        games.put(g.world(), g);
        return g;
    }

    // =================================================================== internal API

    JavaPlugin plugin() { return plugin; }
    BedwarsSettings settings() { return settings; }

    BedwarsGame gameAt(World w) { return w == null ? null : games.get(w); }

    BedwarsGame gameOf(Player p) { return byPlayer.get(p.getUniqueId()); }

    void index(Player p, BedwarsGame g) { byPlayer.put(p.getUniqueId(), g); }

    void unindex(Player p, BedwarsGame g) { byPlayer.remove(p.getUniqueId(), g); }

    void unregister(BedwarsGame g) {
        games.remove(g.world());
        byPlayer.values().removeIf(x -> x == g);
    }

    /** A player can only be in one Bed Wars game at a time. */
    void leaveOtherGames(Player p, BedwarsGame except) {
        BedwarsGame cur = byPlayer.get(p.getUniqueId());
        if (cur != null && cur != except) cur.removePlayer(p);
    }

    public BedwarsShopItem[] quickBuy(UUID id) {
        BedwarsShopItem[] qb = quickBuy.get(id);
        if (qb == null) {
            qb = ShopCatalog.defaultQuickBuy();
            quickBuy.put(id, qb);
        }
        return qb;
    }

    void forgetQuickBuy(UUID id) { quickBuy.remove(id); }

    /** /shout <message> (team modes, 60s cooldown). */
    private final class ShoutCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player)) {
                net.h2020.api.core.Messages.send(sender, "bedwars.bedwarsmodule.players-only");
                return true;
            }
            Player p = (Player) sender;
            BedwarsGame g = gameOf(p);
            if (g == null) {
                net.h2020.api.core.Messages.send(p, "bedwars.bedwarsmodule.you-can-only-use");
                return true;
            }
            StringBuilder sb = new StringBuilder();
            for (String a : args) { if (sb.length() > 0) sb.append(' '); sb.append(a); }
            g.shout(p, sb.toString());
            return true;
        }
    }
}
