package net.h2020.bots.ai;

import net.h2020.api.stats.CombatStats;
import net.h2020.api.H2020;
import net.h2020.api.bot.Bot;
import net.h2020.api.bot.BotBrain;
import net.h2020.api.bot.BotService;
import net.h2020.api.game.BedwarsView;
import net.h2020.api.game.Game;
import net.h2020.api.game.GameFamily;
import net.h2020.api.game.GameManager;
import net.h2020.api.game.SkywarsView;
import net.h2020.bots.ai.brain.BaseBrain;
import net.h2020.bots.ai.brain.BedwarsBrain;
import net.h2020.bots.ai.brain.GameBrain;
import net.h2020.bots.ai.brain.LobbyBrain;
import net.h2020.bots.ai.brain.SkywarsBrain;
import net.h2020.bots.ai.nav.PathService;
import net.h2020.bots.ai.perception.SoundBus;
import net.h2020.bots.ai.world.Raycast;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Entry point of the bot AI (decision layer). Called once by BotsModule.enable() after BotService is registered.
 * Assigns a brain to every bot according to where it is (lobby, Bed Wars game, SkyWars game), runs the global
 * per-tick schedulers (path search budget, raycast budget, sound bus), and dispatches game events to brains.
 */
public final class BotAI {
    private static JavaPlugin plugin;
    private static BukkitTask task;
    private static AIListener listener;
    private static final Map<UUID, Persona> PERSONAS = new ConcurrentHashMap<>();
    private static final Map<UUID, BaseBrain> BRAINS = new ConcurrentHashMap<>();
    private static long ticks;

    private BotAI() {}

    public static void install(JavaPlugin p) {
        plugin = p;
        FightStats.install(p);
        net.h2020.bots.ai.ml.FightModel.install(p);
        net.h2020.bots.ai.ml.PlacementPolicy.install(p);
        net.h2020.bots.ai.ml.AimPolicy.install(p);
        net.h2020.bots.ai.ml.FightFlow.install(p);
        net.h2020.bots.ai.ml.DuelPolicy.install(p);
        net.h2020.bots.ai.ml.NextOpponent.install(p);
        net.h2020.bots.ai.ml.MacroPolicy.install(p);
        net.h2020.bots.ai.ml.BehaviourModel.install(p);
        net.h2020.bots.ai.ml.QuestionBook.install(p);
        net.h2020.bots.ai.ml.ServerQuestions.install(p);
        net.h2020.bots.ai.ml.IntentModel.install(p);
        net.h2020.bots.ai.ml.ClassicalChecks.install(p);
        net.h2020.bots.ai.ml.MlSettings.install(p);
        net.h2020.api.train.Replay.install(p);
        // models keep their own slower timers; this one is the guarantee that a crash costs seconds, not a session
        net.h2020.bots.ai.ml.HumanPlacements.install(p);
        net.h2020.bots.ai.ml.CombatArbiter.install(p);
        net.h2020.bots.ai.ml.BotLabeler.install(p);
        net.h2020.bots.ai.ml.ShopModel.install(p);
        net.h2020.bots.ai.ml.RoleModel.install(p);
        net.h2020.api.stats.CombatStats.install(p);
        ConfigurationSection cfg = p.getConfig() == null ? null : p.getConfig().getConfigurationSection("bots-ai");
        if (cfg != null) {
            PathService.setBudgetMicros(cfg.getLong("path-budget-micros", 4000));
            Raycast.setPerTick(cfg.getInt("raycasts-per-tick", 240));
        }
        listener = new AIListener();
        Bukkit.getPluginManager().registerEvents(listener, p);
        task = Bukkit.getScheduler().runTaskTimer(p, new Runnable() {
            @Override public void run() { tickAll(); }
        }, 1L, 1L);
    }

    public static void shutdown() {
        net.h2020.api.train.Replay.shutdown();
        if (task != null) { task.cancel(); task = null; }
        if (listener != null) { HandlerList.unregisterAll(listener); listener = null; }
        BotService svc = H2020.find(BotService.class);
        if (svc != null) {
            for (Bot b : new ArrayList<>(svc.bots())) {
                try { if (b.brain() instanceof BaseBrain) b.setBrain(null); } catch (Throwable ignored) {}
            }
        }
        PathService.clear();
        SoundBus.clear();
        BRAINS.clear();
    }

    public static Logger log() { return plugin != null ? plugin.getLogger() : Logger.getLogger("H2020"); }

    public static JavaPlugin plugin() { return plugin; }

    private static void tickAll() {
        ticks++;
        Raycast.resetBudget();
        SoundBus.tick();
        net.h2020.bots.ai.perception.ParticleBus.tick();
        net.h2020.bots.ai.ml.HumanFights.tick();
        if (ticks % 10 == 0) sampleHumans();
        net.h2020.bots.ai.perception.LagView.tick();
        try { PathService.tick(); } catch (Throwable t) { log().warning("[bots-ai] path service: " + t); }
        if (ticks % 20 == 0) reconcileAll();
        fallDiag();
        idleDiag();
        resistance();
    }

    private static final java.util.Map<java.util.UUID, double[]> IDLE = new java.util.HashMap<java.util.UUID, double[]>();
    private static final java.util.Map<String, Integer> IDLE_COUNT = new java.util.HashMap<String, Integer>();

    /** A bot that has not moved for five seconds is not playing: say what it thinks it is doing. */
    private static void idleDiag() {
        if (ticks % 20 != 0) return;      // one-second samples: this is how a human judges "standing still"
        for (BaseBrain b : BRAINS.values()) {
            org.bukkit.entity.Player p = b.player();
            if (p == null || !p.isOnline()) continue;
            net.h2020.api.game.GameManager gm = H2020.find(net.h2020.api.game.GameManager.class);
            net.h2020.api.game.Game g = gm == null ? null : gm.gameOf(p);
            if (g == null || g.state() != net.h2020.api.game.GameState.PLAYING) continue;
            double[] prev = IDLE.get(p.getUniqueId());
            org.bukkit.Location now = p.getLocation();
            if (prev == null) { IDLE.put(p.getUniqueId(), new double[]{now.getX(), now.getY(), now.getZ()}); continue; }
            double moved = Math.hypot(now.getX() - prev[0], now.getZ() - prev[2]) + Math.abs(now.getY() - prev[1]);
            String task = b.taskName();
            if (moved < 0.5) {
                String src = net.h2020.bots.ai.combat.ZestTap.grounded || b.taskName().startsWith("rush") ? "rush"
                        : b.taskName();
                if (b.context() != null && b.context().combat.engaged()) src = "combat";
                else if (b.context() != null && b.context().nav.bridging()) src = "bridge";
                net.h2020.api.stats.CombatStats.inc("idle.src." + src);
                String key = p.getName() + "|" + task;
                int count = IDLE_COUNT.containsKey(key) ? IDLE_COUNT.get(key) : 0;
                IDLE_COUNT.put(key, count + 1);
                if (count % 3 == 0) {
                    net.h2020.api.stats.CombatStats.inc("diag.idle." + task);
                    log().info("[idle] " + p.getName() + " task=" + task + " at " + Math.round(now.getX()) + ","
                            + Math.round(now.getY()) + "," + Math.round(now.getZ()) + " sneak=" + p.isSneaking()
                            + " hand=" + (p.getItemInHand() == null ? "-" : p.getItemInHand().getType()));
                }
            }
            IDLE.put(p.getUniqueId(), new double[]{now.getX(), now.getY(), now.getZ()});
        }
    }

    /** Demo switch: keep every bot under Resistance so combos can be watched instead of ending in three hits. */
    public static boolean resistanceOn() { return net.h2020.api.core.DemoFlags.resistanceOn; }
    /** Demo: nobody retreats, so a fight runs long enough to show a full combo. */
    public static boolean sparMode() { return net.h2020.api.core.DemoFlags.sparMode; }
    public static int resistanceAmp() { return net.h2020.api.core.DemoFlags.resistanceAmp; }

    private static void resistance() {
        if (!resistanceOn()) return;
        for (BaseBrain b : BRAINS.values()) {
            org.bukkit.entity.Player p = b.player();
            if (p == null || !p.isOnline() || p.isDead()) continue;
            if (!p.hasPotionEffect(org.bukkit.potion.PotionEffectType.DAMAGE_RESISTANCE)) {
                p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.DAMAGE_RESISTANCE, 20 * 600, resistanceAmp(), true), true);
            }
        }
    }

    private static final java.util.Map<java.util.UUID, Double> LAST_Y = new java.util.HashMap<java.util.UUID, Double>();
    private static final java.util.Map<java.util.UUID, Long> FALL_AT = new java.util.HashMap<java.util.UUID, Long>();
    private static final java.util.Map<java.util.UUID, java.util.Deque<String>> HISTORY = new java.util.HashMap<java.util.UUID, java.util.Deque<String>>();

    /** Log what a bot was doing the moment it started falling: the task and the state that let it walk off. */
    private static void fallDiag() {
        for (BaseBrain b : BRAINS.values()) {
            org.bukkit.entity.Player p = b.player();
            if (p == null || !p.isOnline()) continue;
            double y = p.getLocation().getY();
            java.util.Deque<String> buf = HISTORY.computeIfAbsent(p.getUniqueId(), k -> new java.util.ArrayDeque<String>());
            buf.addLast(Math.round(p.getLocation().getY()) + "@" + Math.round(p.getLocation().getX()) + ","
                    + Math.round(p.getLocation().getZ()) + " yaw" + Math.round(p.getLocation().getYaw())
                    + " pit" + Math.round(p.getLocation().getPitch())
                    + (p.isSneaking() ? " snk" : "") + (p.isSprinting() ? " spr" : "")
                    + " " + (p.getItemInHand() == null ? "-" : p.getItemInHand().getType().name()));
            while (buf.size() > 12) buf.removeFirst();
            Double last = LAST_Y.put(p.getUniqueId(), y);
            if (last == null) continue;
            // only a real in-game fall: the pre-game cage sits at y~62 and the lobby at 140, so both sides are bounded
            net.h2020.api.game.GameManager gm = H2020.find(net.h2020.api.game.GameManager.class);
            net.h2020.api.game.Game g = gm == null ? null : gm.gameOf(p);
            if (g == null || g.state() != net.h2020.api.game.GameState.PLAYING) continue;
            if (last - y < 3.0 || y > 58) continue;
            Long at = FALL_AT.get(p.getUniqueId());
            if (at != null && System.currentTimeMillis() - at < 4000) continue;
            FALL_AT.put(p.getUniqueId(), System.currentTimeMillis());
            String task = "?";
            try { task = b.taskName(); } catch (Throwable ignored) { }
            // the moment before the fall is what explains it: last 12 samples of position, look and inputs
            java.util.Deque<String> hist = HISTORY.get(p.getUniqueId());
            StringBuilder trail = new StringBuilder();
            if (hist != null) for (String h : hist) trail.append(" | ").append(h);
            org.bukkit.inventory.ItemStack hand = p.getItemInHand();
            net.h2020.api.stats.CombatStats.inc("diag.fall." + task);
            log().info("[fall] " + p.getName() + " task=" + task + " y=" + Math.round(y)
                    + " trail=" + trail
                    + " pitch=" + Math.round(p.getLocation().getPitch())
                    + " sneak=" + p.isSneaking() + " sprint=" + p.isSprinting()
                    + " hand=" + (hand == null ? "none" : hand.getType()) + " hp=" + Math.round(p.getHealth()));
        }
    }

    /** Keep a fresh observable-feature sample of every real player around, for the behaviour model. */
    private static void sampleHumans() {
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            if (p == null || !p.isOnline()) continue;
            if (isBotPlayer(p)) continue;
            try { net.h2020.bots.ai.ml.BehaviourModel.sample(p, ticks); } catch (Throwable ignored) { }
        }
    }

    /** Stable personality for a bot. */
    public static Persona persona(Bot bot) {
        UUID id = bot.profile().uuid;
        Persona p = PERSONAS.get(id);
        if (p == null) { p = new Persona(bot.profile()); PERSONAS.put(id, p); }
        return p;
    }

    /** Bots get a fresh UUID per spawn; drop the persona when the bot leaves so the map does not grow forever. */
    static void forgetPersona(UUID id) { PERSONAS.remove(id); }

    public static void register(Player p, BaseBrain b) { BRAINS.put(p.getUniqueId(), b); }

    public static void unregister(Player p, BaseBrain b) { BRAINS.remove(p.getUniqueId(), b); }

    public static BaseBrain brainOf(Player p) { return p == null ? null : BRAINS.get(p.getUniqueId()); }

    /** Is this player one of our bots (a virtual client rather than a person)? */
    public static boolean isBotPlayer(Player p) {
        if (p == null) return false;
        // CoreUtil falls back to "has no real Netty channel" when the bot service is not reachable, which is what the
        // ML gates need: a bot that slips through here becomes training data for the human models
        return net.h2020.api.core.Players.isBot(p);
    }

    public static Collection<BaseBrain> brains() { return BRAINS.values(); }

    // ------------------------------------------------------------------------------------------- brain assignment

    static void reconcileAll() {
        BotService svc = H2020.find(BotService.class);
        if (svc == null) return;
        for (Bot b : new ArrayList<>(svc.bots())) {
            try { reconcile(b); } catch (Throwable t) { log().warning("[bots-ai] reconcile " + t); }
        }
    }

    static void reconcile(Player p) {
        BotService svc = H2020.find(BotService.class);
        if (svc == null || p == null) return;
        Bot b = svc.bot(p);
        if (b != null) reconcile(b);
    }

    static void reconcile(Bot b) {
        if (b.isRemoved() || b.player() == null || !b.player().isOnline()) return;
        BotBrain cur = b.brain();
        if (cur != null && !(cur instanceof BaseBrain)) return; // someone else (e.g. a command) drives this bot
        GameManager gm = H2020.find(GameManager.class);
        Game g = gm == null ? null : gm.gameOf(b.player());
        if (g != null) {
            if (cur instanceof GameBrain && ((GameBrain) cur).game() == g) return;
            BotBrain nb = null;
            if (g.type().family() == GameFamily.BEDWARS && g instanceof BedwarsView) nb = new BedwarsBrain((BedwarsView) g);
            else if (g.type().family() == GameFamily.SKYWARS && g instanceof SkywarsView) nb = new SkywarsBrain((SkywarsView) g);
            if (nb != null) { b.setBrain(nb); return; }
            if (cur instanceof LobbyBrain) return;
            b.setBrain(new LobbyBrain());
            return;
        }
        // a plugin-registered brain wins over the built-ins (a practice server, another minigame, an event world)
        net.h2020.api.bot.BotBrain custom = net.h2020.api.bot.BrainSelector.select(b.player().getWorld());
        if (custom != null) {
            if (cur != custom) b.setBrain(custom);
            return;
        }
        if (net.h2020.api.core.Worlds.isArena(b.player().getWorld())) {
            if (!(cur instanceof net.h2020.bots.ai.brain.ArenaBrain)) b.setBrain(new net.h2020.bots.ai.brain.ArenaBrain());
            return;
        }
        if (cur instanceof LobbyBrain) return;
        b.setBrain(new LobbyBrain());
    }

    /** Reconcile on the next tick (after the game finished moving the player). */
    static void reconcileSoon(final Player p) {
        if (plugin == null) return;
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override public void run() { reconcile(p); }
        }, 2L);
    }

    static void reconcileGame(final Game g) {
        if (plugin == null) return;
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() {
                List<Player> ps = new ArrayList<>(g.players());
                for (Player p : ps) reconcile(p);
            }
        });
    }
}
