package net.h2020.practice;

import net.h2020.api.H2020;
import net.h2020.api.Module;
import net.h2020.api.bot.Bot;
import net.h2020.api.bot.BotProfile;
import net.h2020.api.bot.BotService;
import net.h2020.api.game.Game;
import net.h2020.api.game.GameFamily;
import net.h2020.api.game.GameManager;
import net.h2020.api.game.GameMode;
import net.h2020.api.game.GameModes;
import net.h2020.api.game.GameProvider;
import net.h2020.api.game.GameState;
import net.h2020.api.map.MapTemplate;
import net.h2020.practice.drill.BowDrill;
import net.h2020.practice.drill.BridgeDrill;
import net.h2020.practice.drill.Drill;
import net.h2020.practice.drill.FireballDrill;
import net.h2020.practice.drill.GameSenseDrill;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bed Wars practice: one server, five drills, no match to win.
 *
 * <p>Registered as a family and a set of modes, so a drill is created exactly like a game and the rest of the server
 * keeps working around it - the player is in a game, the lobby router leaves them alone, and {@code /hub} gets them out.
 * The arena is generated per session and deleted afterwards: a drill is a shape of blocks, not a map to maintain.
 *
 * <p>Bots can be dropped into any drill with {@code /practice <drill> bots <n>}. They are ordinary bots on the same
 * arena with the same kit, so the same scoring applies and their numbers are comparable with a player's.
 */
public final class PracticeModule implements Module, GameProvider, Listener, CommandExecutor {

    private JavaPlugin plugin;
    private BukkitTask ticker;
    private final Map<World, PracticeGame> games = new ConcurrentHashMap<World, PracticeGame>();
    private final Map<UUID, PracticeGame> byPlayer = new ConcurrentHashMap<UUID, PracticeGame>();

    @Override public String name() { return "practice"; }

    @Override
    public void enable(JavaPlugin plugin) {
        this.plugin = plugin;
        for (PracticeMode m : PracticeMode.ALL) GameModes.register(m);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() {
                for (PracticeGame g : new ArrayList<PracticeGame>(games.values())) {
                    try { g.tick(); } catch (Throwable t) { plugin.getLogger().warning("practice tick: " + t); }
                }
            }
        }, 1L, 1L);
        org.bukkit.command.PluginCommand cmd = plugin.getCommand("practice");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(new Tab());
        }
        tryRegister();
    }

    @Override
    public void disable() {
        if (ticker != null) ticker.cancel();
        for (PracticeGame g : new ArrayList<PracticeGame>(games.values())) {
            try { g.shutdown(); } catch (Throwable ignored) { }
        }
        games.clear();
        byPlayer.clear();
    }

    /** The core registers GameManager during its own enable; retry for a moment if this module wins the race. */
    private void tryRegister() {
        GameManager gm = H2020.find(GameManager.class);
        if (gm != null) { gm.registerProvider(this); return; }
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override public void run() { tryRegister(); }
        }, 20L);
    }

    // ---- GameProvider ----

    @Override public GameFamily family() { return GameFamily.PRACTICE; }

    @Override
    public Game create(GameMode type, MapTemplate map, String gameId) {
        PracticeMode pm = asPractice(type);
        Drill drill = drillFor(type);
        if (pm == null || drill == null) return null;
        PracticeArena arena = PracticeArena.create(drill.id());
        PracticeGame g = new PracticeGame(pm, gameId, drill, arena);
        games.put(arena.world(), g);
        return g;
    }

    private static PracticeMode asPractice(GameMode m) {
        for (PracticeMode p : PracticeMode.ALL) if (p.id().equals(m.id())) return p;
        return null;
    }

    private static Drill drillFor(GameMode m) {
        String id = m.id();
        if (id.equals(PracticeMode.FIREBALL.id())) return new FireballDrill();
        if (id.equals(PracticeMode.BOW.id())) return new BowDrill();
        if (id.equals(PracticeMode.SENSE.id())) return new GameSenseDrill();
        if (id.equals(PracticeMode.BRIDGE.id())) return new BridgeDrill(BridgeDrill.Variant.STRAIGHT);
        if (id.equals(PracticeMode.BRIDGE_DIAGONAL.id())) return new BridgeDrill(BridgeDrill.Variant.DIAGONAL);
        return null;
    }

    // ---- command ----

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player p = sender instanceof Player ? (Player) sender : null;
        if (p == null && args.length > 0 && args[0].equalsIgnoreCase("debug")) { debug(sender); return true; }
        if (p == null) {
            // console: only the drills that can run without a player, i.e. with bots in them. This is how a drill is
            // exercised headlessly, and how a bot run is measured without a human standing in the arena.
            if (args.length < 3) {
                sender.sendMessage("Console: /practice <drill> bots <n>");
                return true;
            }
            PracticeMode m = modeFor(args[0].toLowerCase(java.util.Locale.ROOT));
            if (m == null) { sender.sendMessage("Unknown drill " + args[0]); return true; }
            int n = 0;
            for (int i = 1; i + 1 < args.length; i++)
                if (args[i].equalsIgnoreCase("bots")) {
                    try { n = Math.max(1, Math.min(8, Integer.parseInt(args[i + 1]))); } catch (Exception ignored) { }
                }
            PracticeGame g = start(m);
            if (g == null) { sender.sendMessage("Could not start the drill (bots module missing?)"); return true; }
            track(g);
            spawnBots(g, n);
            sender.sendMessage("Started " + m.displayName() + " with " + n + " bot(s) as " + g.id());
            return true;
        }

        if (args.length == 0) { menu(p); return true; }

        String sub = args[0].toLowerCase(java.util.Locale.ROOT);
        if (sub.equals("leave") || sub.equals("quit") || sub.equals("lobby")) {
            PracticeGame g = byPlayer.get(p.getUniqueId());
            if (g == null) { p.sendMessage(ChatColor.GRAY + "You are not in a drill."); return true; }
            g.removePlayer(p);
            GameManager gm = H2020.find(GameManager.class);
            if (gm != null) gm.sendToLobby(p, null);
            return true;
        }
        if (sub.equals("stats")) { stats(p); return true; }
        if (sub.equals("debug")) { debug(sender); return true; }
        if (sub.equals("answer")) {
            PracticeGame g = byPlayer.get(p.getUniqueId());
            if (g == null) { p.sendMessage(ChatColor.GRAY + "You are not in a drill."); return true; }
            if (!(g.drill() instanceof GameSenseDrill)) { p.sendMessage(ChatColor.GRAY + "Not the game sense drill."); return true; }
            int n;
            try { n = Integer.parseInt(args[1]); } catch (Exception e) { p.sendMessage(ChatColor.RED + "/practice answer <1-3>"); return true; }
            ((GameSenseDrill) g.drill()).answer(g, p, n);
            return true;
        }

        PracticeMode mode = modeFor(sub);
        if (mode == null) {
            p.sendMessage(ChatColor.RED + "Unknown drill " + args[0] + ". Try fireball, bow, gamesense, bridge, bridge-diagonal.");
            return true;
        }

        int bots = 0;
        for (int i = 1; i + 1 < args.length; i++) {
            if (args[i].equalsIgnoreCase("bots")) {
                try { bots = Math.max(0, Math.min(8, Integer.parseInt(args[i + 1]))); } catch (Exception ignored) { }
            }
        }

        PracticeGame existing = byPlayer.get(p.getUniqueId());
        if (existing != null) existing.removePlayer(p);

        PracticeGame g = start(mode);
        if (g == null) {
            p.sendMessage(ChatColor.RED + "Could not start the drill - see the console.");
            return true;
        }
        g.addPlayer(p);
        track(g);
        if (bots > 0) spawnBots(g, bots);
        return true;
    }

    private PracticeMode modeFor(String sub) {
        switch (sub) {
            case "fireball": case "fireballs": case "deflect": return PracticeMode.FIREBALL;
            case "bow": case "aim": case "archery": return PracticeMode.BOW;
            case "gamesense": case "sense": case "scenarios": return PracticeMode.SENSE;
            case "bridge": case "bridging": return PracticeMode.BRIDGE;
            case "bridge-diagonal": case "diagonal": case "diag": return PracticeMode.BRIDGE_DIAGONAL;
            default: return null;
        }
    }

    /** Create the game through the manager, so it is registered like any other match (lobby routing depends on it). */
    private PracticeGame start(PracticeMode mode) {
        GameManager gm = H2020.find(GameManager.class);
        if (gm == null) return null;
        Game g = gm.create(mode, PracticeMap.of(mode));
        if (!(g instanceof PracticeGame)) return null;
        return (PracticeGame) g;
    }

    private void track(PracticeGame g) {
        games.put(g.world(), g);
        for (Player p : g.players()) byPlayer.put(p.getUniqueId(), g);
        // players are added after creation, so watch joins through the tick loop as well
        Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() {
                if (g.state() == GameState.ENDING) return;
                for (Player p : g.players()) byPlayer.put(p.getUniqueId(), g);
            }
        }, 20L, 40L);
    }

    /** Bots on the same arena with the same kit: this is the testing ground. */
    private void spawnBots(PracticeGame g, int n) {
        BotService bs = H2020.find(BotService.class);
        if (bs == null) {
            for (Player p : g.players()) p.sendMessage(ChatColor.GRAY + "Bots are not available on this server.");
            return;
        }
        Location at = g.drill().spawn();
        for (int i = 0; i < n; i++) {
            try {
                BotProfile prof = bs.randomProfile();
                Bot bot = bs.spawn(prof, at);
                if (bot == null || bot.player() == null) continue;
                g.addPlayer(bot.player());
                byPlayer.put(bot.player().getUniqueId(), g);
            } catch (Throwable t) {
                plugin.getLogger().warning("practice bot: " + t);
            }
        }
    }

    private void menu(Player p) {
        p.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "Bed Wars practice");
        p.sendMessage(ChatColor.GRAY + "/practice fireball " + ChatColor.DARK_GRAY + "- deflect incoming fireballs");
        p.sendMessage(ChatColor.GRAY + "/practice bow " + ChatColor.DARK_GRAY + "- targets at 10 to 34 blocks");
        p.sendMessage(ChatColor.GRAY + "/practice gamesense " + ChatColor.DARK_GRAY + "- situations, graded");
        p.sendMessage(ChatColor.GRAY + "/practice bridge " + ChatColor.DARK_GRAY + "- straight, growing gap");
        p.sendMessage(ChatColor.GRAY + "/practice bridge-diagonal " + ChatColor.DARK_GRAY + "- the same on the diagonal");
        p.sendMessage(ChatColor.DARK_GRAY + "add " + ChatColor.WHITE + "bots <n>" + ChatColor.DARK_GRAY
                + " to any of them to bring bots in; " + ChatColor.WHITE + "/practice leave" + ChatColor.DARK_GRAY + " to stop");
    }

    /** Everything a drill can be checked on from the console: the world, who is in it, and what is ticking in it. */
    private void debug(CommandSender to) {
        if (games.isEmpty()) { to.sendMessage("no practice sessions running"); return; }
        for (PracticeGame g : games.values()) {
            to.sendMessage(g.id() + " " + g.drill().display() + " state=" + g.state()
                    + " world=" + g.world().getName() + " players=" + g.players().size());
            java.util.Map<String, Integer> counts = new java.util.TreeMap<String, Integer>();
            for (Entity e : g.world().getEntities()) {
                String n = e.getType().name();
                counts.put(n, counts.containsKey(n) ? counts.get(n) + 1 : 1);
            }
            to.sendMessage("  entities: " + counts);
            for (Player p : g.players()) for (String line : g.drill().stats(g, p)) to.sendMessage("  [" + p.getName() + "] " + line);
            for (Player p : g.players()) to.sendMessage("  " + p.getName() + " at "
                    + p.getLocation().getBlockX() + "," + p.getLocation().getBlockY() + ","
                    + p.getLocation().getBlockZ() + "  " + g.drill().hud(g, p));
        }
    }

    private void stats(Player p) {
        PracticeGame g = byPlayer.get(p.getUniqueId());
        if (g == null) { p.sendMessage(ChatColor.GRAY + "You are not in a drill."); return; }
        p.sendMessage(ChatColor.AQUA + g.drill().display());
        for (String line : g.drill().stats(g, p)) p.sendMessage(ChatColor.GRAY + "  " + line);
    }

    // ---- listeners ----

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAttack(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player)) return;
        Player p = (Player) e.getDamager();
        PracticeGame g = byPlayer.get(p.getUniqueId());
        if (g == null || !g.world().equals(p.getWorld())) return;
        g.handleAttack(p, e.getEntity());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        PracticeGame g = byPlayer.get(p.getUniqueId());
        if (g == null || !g.world().equals(p.getWorld())) return;
        if (g.handleDamage(p, e)) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        PracticeGame g = byPlayer.get(e.getEntity().getUniqueId());
        if (g == null) return;
        e.setDeathMessage(null);
        e.getDrops().clear();
        g.handleDeath(e.getEntity());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        PracticeGame g = byPlayer.remove(e.getPlayer().getUniqueId());
        if (g != null) g.removePlayer(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        PracticeGame g = byPlayer.get(e.getPlayer().getUniqueId());
        if (g == null || !g.world().equals(e.getBlock().getWorld())) return;
        g.drill().onPlace(g, e.getPlayer(), e.getBlock());
    }

    @EventHandler
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        PracticeGame g = byPlayer.get(p.getUniqueId());
        if (g == null || !g.world().equals(p.getWorld())) return;
        if (g.drill() instanceof BowDrill) ((BowDrill) g.drill()).onShoot(g, p);
    }

    @EventHandler
    public void onArrow(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof Arrow)) return;
        Arrow arrow = (Arrow) e.getEntity();
        if (!(arrow.getShooter() instanceof Player)) return;
        Player p = (Player) arrow.getShooter();
        PracticeGame g = byPlayer.get(p.getUniqueId());
        if (g == null || !g.world().equals(arrow.getWorld())) return;
        if (!(g.drill() instanceof BowDrill)) return;
        // the armour stand the arrow passed through is the target; 1.8 reports the block, so find the nearest stand
        Entity hit = nearestTarget(arrow.getLocation());
        if (hit != null) ((BowDrill) g.drill()).onArrowHit(g, p, arrow, hit);
    }

    private Entity nearestTarget(Location at) {
        for (Entity e : at.getWorld().getNearbyEntities(at, 2.0, 2.5, 2.0)) {
            if (e instanceof org.bukkit.entity.ArmorStand) return e;
        }
        return null;
    }

    /** Tab completion: the drill names, then bots/leave/stats. */
    private static final class Tab implements org.bukkit.command.TabCompleter {
        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            List<String> out = new ArrayList<String>();
            if (args.length == 1) {
                for (String s : new String[]{"fireball", "bow", "gamesense", "bridge", "bridge-diagonal", "leave", "stats"}) {
                    if (s.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))) out.add(s);
                }
            } else if (args.length == 2) {
                out.add("bots");
            } else if (args.length == 3 && args[1].equalsIgnoreCase("bots")) {
                for (String s : new String[]{"0", "1", "2", "3", "4"}) out.add(s);
            }
            return out;
        }
    }

    /** Every game needs a template; a drill generates its own, so this is only the identity the manager needs. */
    private static final class PracticeMap implements MapTemplate {
        private final PracticeMode mode;
        private PracticeMap(PracticeMode mode) { this.mode = mode; }
        static PracticeMap of(PracticeMode mode) { return new PracticeMap(mode); }

        @Override public String name() { return mode.displayName(); }
        @Override public GameFamily family() { return GameFamily.PRACTICE; }
        @Override public List<Integer> supportedTeamCounts() { return java.util.Collections.singletonList(1); }
        @Override public List<org.bukkit.util.Vector> points(String key) { return java.util.Collections.emptyList(); }
        @Override public org.bukkit.util.Vector point(String key) { return null; }
        @Override public float yaw(String key, int index) { return 0f; }
        @Override public Map<String, List<org.bukkit.util.Vector>> allPoints() { return java.util.Collections.emptyMap(); }
        @Override public int buildLimitY() { return 256; }
        @Override public org.bukkit.util.Vector boundsMin() { return new org.bukkit.util.Vector(-64, 0, -64); }
        @Override public org.bukkit.util.Vector boundsMax() { return new org.bukkit.util.Vector(64, 256, 64); }
        @Override public World createInstance(String instanceWorldName) {
            throw new UnsupportedOperationException("a practice drill builds its own arena");
        }
        @Override public void destroyInstance(World world) { }
    }
}
