package net.h2020.replay;

import net.h2020.api.H2020;
import net.h2020.api.bot.Bot;
import net.h2020.api.bot.BotService;
import net.h2020.api.game.BedwarsTools;
import net.h2020.api.game.Game;
import net.h2020.api.game.GameManager;
import net.h2020.api.game.GameState;
import net.h2020.api.game.GameType;
import net.h2020.api.map.MapService;
import net.h2020.api.map.MapTemplate;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A concurrency governor for bot-only Bed Wars games: it keeps as many games alive as the server can sustain and
 * measures the resulting throughput. Unlike {@link Arena}, a slow game is force-finished instead of stopping the run.
 * <p>
 * The trainer never unloads a world itself. It only calls {@link Game#shutdown()} and lets the game do its own
 * teardown, because WindSpigot can race a world unload against its own world ticker.
 */
public final class Trainer {
    private static final int MAX_GAMES_CAP = 24;
    private static final int DURATION_WINDOW = 20;

    private static JavaPlugin plugin;
    private static boolean installed;
    private static boolean running;

    /** How many games the governor currently wants alive, and the ceiling start() was given. */
    private static int target;
    private static int maxTarget;
    private static int maxGames;

    /** Created by this trainer and not yet finished. */
    private static final Set<String> tracked = new LinkedHashSet<String>();
    private static final Map<String, Long> startTimes = new HashMap<String, Long>();

    private static long gamesStarted;
    private static long gamesFinished;
    private static long gamesForced;
    private static long runStartedAt;

    /** Rolling durations (seconds) of the last 20 games that ended, for the median. */
    private static final double[] lastGameSeconds = new double[DURATION_WINDOW];
    private static int durationCount;
    private static int durationNext;

    private static long replay0;
    private static long tracked0;
    private static long armed0;
    private static long hit0;
    private static long taken0;
    private static long beds0;
    private static int botsPerGame = 16;
    /** Absolute ceiling for a game length, so a caller cannot ask for an endless game. */
    private static int maxGameSeconds = 240;
    /** Length of one sprint game; start() derives it from the trainer, tick() enforces it. */
    /**
     * Default game cap, in seconds. Measured on this host at 4 concurrent games, TPS held at 20.0:
     * 60 s -> 201 games/hour, 180 s -> 48/hour, 300 s -> ~24/hour. Throughput is set almost entirely by this cap,
     * because bot-only games do not reach a natural victory before it. It defaults to the fastest measured setting;
     * pass a larger number when you want longer fights rather than more of them.
     */
    private static int gameSeconds = 60;
    private static double minTps = 18.0;
    private static int mapCursor;

    private static long pass;
    private static long lastAdaptPass;
    private static boolean lastCreateOk;
    private static double lastTps = 20.0;
    private static long lastCreateWarn;

    private Trainer() {}

    public static void install(final JavaPlugin p) {
        if (p == null) return;
        plugin = p;
        if (installed) return;
        try {
            Bukkit.getScheduler().runTaskTimer(p, new Runnable() {
                @Override public void run() { tick(); }
            }, 1L, 5L);
            installed = true;
        } catch (Throwable t) {
            p.getLogger().warning("[trainer] could not install the 5-tick governor: " + t);
        }
    }

    public static boolean running() { return running; }

    /** True while a sprint run owns the games, so the arena will refuse to start on top of it. */
    public static boolean active() { return running; }

    public static String start(int maxGamesWanted) {
        return start(maxGamesWanted, 90);
    }

    public static String start(int maxGamesWanted, int gameSecondsWanted) {
        if (running) return "already running (target " + target + "/" + maxGames + ")";
        if (Arena.running()) return "arena is running - stop it first";
        if (!ensureInstalled()) return "replay plugin not available";

        BedwarsTools tools = H2020.find(BedwarsTools.class);
        if (tools == null) return "bedwars module not available";
        int cap = Math.max(1, Math.min(MAX_GAMES_CAP, maxGamesWanted));
        gameSeconds = Math.max(10, Math.min(maxGameSeconds, gameSecondsWanted));
        try {
            tools.applySprint(true);
        } catch (Throwable t) {
            return "could not enable the sprint profile: " + t;
        }

        tracked.clear();
        startTimes.clear();
        gamesStarted = 0;
        gamesFinished = 0;
        gamesForced = 0;
        runStartedAt = System.currentTimeMillis();
        replay0 = net.h2020.api.train.Replay.rows();
        tracked0 = (long) net.h2020.api.stats.CombatStats.total("ml.place.tracked");
        armed0 = (long) net.h2020.api.stats.CombatStats.total("ml.place.armed");
        hit0 = (long) net.h2020.api.stats.CombatStats.total("ml.place.outcome.hit");
        taken0 = (long) net.h2020.api.stats.CombatStats.total("ml.place.outcome.taken");
        beds0 = (long) net.h2020.api.stats.CombatStats.total("bed.raid.ok");
        durationCount = 0;
        durationNext = 0;
        Arrays.fill(lastGameSeconds, 0.0);
        mapCursor = 0;
        pass = 0;
        lastAdaptPass = 0;
        lastCreateOk = true;
        lastTps = 20.0;
        lastCreateWarn = 0L;
        target = 1;
        maxTarget = cap;
        maxGames = cap;
        running = true;
        return "sprint trainer started: target 1, max " + cap + " games, game cap " + gameSeconds
                + "s, 5-tick governor";
    }

    public static String stop() {
        if (!running) return "trainer not running";
        long elapsed = Math.max(0L, System.currentTimeMillis() - runStartedAt);
        long started = gamesStarted, finished = gamesFinished, forced = gamesForced;

        BedwarsTools tools = H2020.find(BedwarsTools.class);
        if (tools != null) {
            try { tools.applySprint(false); } catch (Throwable t) {
                if (plugin != null) plugin.getLogger().warning("[trainer] could not disable the sprint profile: " + t);
            }
        }
        GameManager gm = H2020.find(GameManager.class);
        for (String id : new ArrayList<String>(tracked)) {
            Game g = null;
            if (gm != null) {
                for (Game any : gm.games()) {
                    if (any.id().equals(id)) { g = any; break; }
                }
            }
            if (g == null) continue;
            try { g.shutdown(); } catch (Throwable t) {
                if (plugin != null) plugin.getLogger().warning("[trainer] shutdown of " + id + " failed: " + t);
            }
        }
        tracked.clear();
        startTimes.clear();
        running = false;

        // all state cleared: a second start() after this one must begin from a clean slate
        target = 0;
        maxTarget = 0;
        maxGames = 0;
        gamesStarted = 0;
        gamesFinished = 0;
        gamesForced = 0;
        runStartedAt = 0L;
        replay0 = 0L;
        tracked0 = 0L;
        armed0 = 0L;
        hit0 = 0L;
        taken0 = 0L;
        beds0 = 0L;
        durationCount = 0;
        durationNext = 0;
        Arrays.fill(lastGameSeconds, 0.0);
        mapCursor = 0;
        pass = 0;
        lastAdaptPass = 0;
        lastCreateOk = false;
        lastTps = 20.0;
        lastCreateWarn = 0L;
        return "sprint trainer stopped after " + clock(elapsed) + ": " + started + " started, " + finished
                + " finished, " + forced + " force-finished";
    }

    public static List<String> report() {
        List<String> out = new ArrayList<String>();
        if (!running) {
            out.add("§7trainer not running");
            return out;
        }
        long elapsed = Math.max(0L, System.currentTimeMillis() - runStartedAt);
        long denom = Math.max(1L, elapsed);
        // Every game that ended counts: under the sprint cap almost all of them are force-finished, so counting
        // only natural finishes would make the throughput figure read zero while the trainer is saturated.
        long gamesPerHour = (gamesFinished + gamesForced) * 3600000L / denom;
        long tracked = Math.max(0L, (long) net.h2020.api.stats.CombatStats.total("ml.place.tracked") - tracked0);
        long armed = Math.max(0L, (long) net.h2020.api.stats.CombatStats.total("ml.place.armed") - armed0);
        long hit = Math.max(0L, (long) net.h2020.api.stats.CombatStats.total("ml.place.outcome.hit") - hit0);
        long taken = Math.max(0L, (long) net.h2020.api.stats.CombatStats.total("ml.place.outcome.taken") - taken0);
        long beds = Math.max(0L, (long) net.h2020.api.stats.CombatStats.total("bed.raid.ok") - beds0);
        long trackedPerHour = tracked * 3600000L / denom;
        long armedPerHour = armed * 3600000L / denom;
        long rows = Math.max(0L, net.h2020.api.train.Replay.rows() - replay0);
        out.add("§6[trainer] §erun §f" + clock(elapsed) + "§7, target §f" + target + "§7/§f" + maxGames
                + "§7 games, TPS §f" + String.format(Locale.ROOT, "%.1f", lastTps));
        out.add("§7games: §f" + gamesStarted + "§7 started, §f" + gamesFinished + "§7 finished, §f"
                + gamesForced + "§7 force-finished");
        out.add("§7throughput: §f" + gamesPerHour + "§7 games/hour, median game §f"
                + String.format(Locale.ROOT, "%.1f", medianSeconds()) + "s");
        out.add("§7learning: §f" + trackedPerHour + "§7 placements tracked/hour, §f" + armed
                + "§7 armed, §f" + hit + "§7 outcome-hit, §f" + taken + "§7 outcome-taken, §f" + beds + "§7 beds");
        // A counter that has not moved since start is the number that actually needs explaining, so say it plainly
        if (armed == 0 && tracked > 0) {
            out.add("§7armed is 0: §ethe duel placement policy made no decision in these games, so nothing was"
                    + " attributable. That happens when no bot gets into a melee exchange long enough for BlockTactics"
                    + " to run - check bot behaviour, not this counter.");
        }
        out.add("§7replay rows: §f" + rows + "§7 (human-only stream)");
        return out;
    }

    // ------------------------------------------------------------------------------------------------ scheduler

    private static void tick() {
        if (!running) return;
        pass++;
        lastTps = tps();

        GameManager gm = H2020.find(GameManager.class);
        if (gm == null) return;

        Map<String, Game> live = new HashMap<String, Game>();
        for (Game g : gm.games()) {
            if (tracked.contains(g.id())) live.put(g.id(), g);
        }

        long now = System.currentTimeMillis();
        for (String id : new ArrayList<String>(tracked)) {
            Game g = live.get(id);
            if (g == null) {
                finish(id, false);
                continue;
            }
            if (g.state() == GameState.ENDING) {
                finish(id, false);
                continue;
            }
            Long at = startTimes.get(id);
            long age = at == null ? 0L : now - at.longValue();
            if (age > gameSeconds * 1000L) {
                if (plugin != null) plugin.getLogger().warning("[trainer] game " + id + " stuck for " + (age / 1000L)
                        + "s, force-finishing it");
                try {
                    g.shutdown();
                } catch (Throwable t) {
                    if (plugin != null) plugin.getLogger().warning("[trainer] shutdown of stuck game " + id + " failed: " + t);
                }
                finish(id, true);
            }
        }

        // A training run has no game budget: every finished or force-finished game is replaced, so the server stays
        // saturated for as long as it is asked to. maxGames is the concurrency ceiling only.
        while (running && tracked.size() < target && lastTps >= minTps) {
            if (!startOne()) break;   // do not spin on the same failure every pass
        }
        adapt();
    }

    /** Grow while TPS has headroom, shrink when it does not. At most one step every 20 passes. */
    private static void adapt() {
        if (pass - lastAdaptPass < 20L) return;
        if (lastTps < minTps) {
            int before = target;
            target = Math.max(1, target - 1);
            lastAdaptPass = pass;
            if (plugin != null) {
                plugin.getLogger().warning("[trainer] TPS " + String.format(Locale.ROOT, "%.1f", lastTps)
                        + " below " + String.format(Locale.ROOT, "%.1f", minTps) + ", target " + before + " -> " + target);
            }
        } else if (lastTps >= minTps + 1.0 && lastCreateOk && target < maxTarget) {
            target++;
            lastAdaptPass = pass;
        }
    }

    private static void finish(String id, boolean forced) {
        Long at = startTimes.remove(id);
        tracked.remove(id);
        if (at != null) recordDuration((System.currentTimeMillis() - at.longValue()) / 1000.0);
        if (forced) gamesForced++;
        else gamesFinished++;
    }

    private static void recordDuration(double seconds) {
        lastGameSeconds[durationNext] = Math.max(0.0, seconds);
        durationNext = (durationNext + 1) % DURATION_WINDOW;
        if (durationCount < DURATION_WINDOW) durationCount++;
    }

    private static double medianSeconds() {
        if (durationCount <= 0) return 0.0;
        double[] copy = new double[durationCount];
        System.arraycopy(lastGameSeconds, 0, copy, 0, durationCount);
        Arrays.sort(copy);
        int mid = durationCount / 2;
        return durationCount % 2 == 1 ? copy[mid] : (copy[mid - 1] + copy[mid]) / 2.0;
    }

    private static String clock(long millis) {
        long total = Math.max(0L, millis) / 1000L;
        return String.format(Locale.ROOT, "%02d:%02d", total / 60L, total % 60L);
    }

    // ------------------------------------------------------------------------------------------------ starting

    private static boolean startOne() {
        MapService maps;
        GameManager gm;
        BotService bs;
        try {
            maps = H2020.find(MapService.class);
            gm = H2020.find(GameManager.class);
            bs = H2020.find(BotService.class);
        } catch (Throwable t) {
            warnStart("service lookup failed: " + t);
            lastCreateOk = false;
            return false;
        }
        if (maps == null || gm == null || bs == null) {
            warnStart("maps, games or bots service is not available");
            lastCreateOk = false;
            return false;
        }

        List<MapTemplate> templates;
        try {
            templates = maps.templatesFor(GameType.BEDWARS_4V4V4V4);
        } catch (Throwable t) {
            warnStart("could not list Bed Wars maps: " + t);
            lastCreateOk = false;
            return false;
        }
        if (templates == null || templates.isEmpty()) {
            warnStart("no Bed Wars 4v4v4v4 maps are available");
            lastCreateOk = false;
            return false;
        }

        MapTemplate map = templates.get(Math.abs(mapCursor) % templates.size());
        mapCursor++;
        Game g;
        try {
            g = gm.create(GameType.BEDWARS_4V4V4V4, map);
        } catch (Throwable t) {
            warnStart("could not create a game on " + map.name() + ": " + t);
            lastCreateOk = false;
            return false;
        }
        if (g == null) {
            warnStart("GameManager.create returned null for " + map.name());
            lastCreateOk = false;
            return false;
        }

        tracked.add(g.id());
        startTimes.put(g.id(), System.currentTimeMillis());
        gamesStarted++;
        lastCreateOk = true;

        int want = Math.min(botsPerGame, g.type().maxPlayers());
        for (int i = 0; i < want; i++) spawnBot(bs, g);
        if (plugin != null) plugin.getLogger().info("[trainer] game " + g.id() + " (" + map.name() + "), "
                + want + " bots, target " + target + "/" + maxGames);
        return true;
    }

    /** Mirrors Arena's bot spawn, including the two-tick retry after the profile has logged in. */
    private static void spawnBot(BotService bs, final Game g) {
        if (plugin == null) return;
        try {
            final Bot bot = bs.spawn(bs.randomProfile(), g.world().getSpawnLocation());
            if (bot == null) return;
            Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                @Override public void run() {
                    Player p = bot.player();
                    if (bot.isRemoved() || p == null || !p.isOnline()) return;
                    GameManager gm = H2020.find(GameManager.class);
                    if (gm != null && gm.gameOf(p) != null) return;
                    boolean ok = false;
                    try { ok = g.addPlayer(p); } catch (Throwable ignored) { }
                    if (!ok) bot.remove();
                }
            }, 2L);
        } catch (Throwable t) {
            if (plugin != null) plugin.getLogger().warning("[trainer] could not spawn a bot: " + t);
        }
    }

    private static void warnStart(String why) {
        long now = System.currentTimeMillis();
        if (plugin != null && now - lastCreateWarn > 30000L) {
            lastCreateWarn = now;
            plugin.getLogger().warning("[trainer] cannot start a game: " + why);
        }
    }

    private static boolean ensureInstalled() {
        if (installed) return true;
        JavaPlugin p = null;
        try {
            org.bukkit.plugin.Plugin raw = Bukkit.getPluginManager().getPlugin("lattice-replay");
            if (raw instanceof JavaPlugin) p = (JavaPlugin) raw;
        } catch (Throwable ignored) { }
        if (p == null) {
            try { p = H2020.plugin(); } catch (Throwable ignored) { }
        }
        if (p == null) return false;
        install(p);
        return installed;
    }

    // ------------------------------------------------------------------------------------------------ TPS

    /**
     * Server TPS without depending on a particular server build: the Spigot snapshot API we compile against has the
     * Spigot object but not its getTPS() method, so both paths are reflective. Never throws, never returns nonsense.
     */
    private static double tps() {
        try {
            Object spigot = Bukkit.spigot();
            Object value = spigot.getClass().getMethod("getTPS").invoke(spigot);
            Double d = firstTps(value);
            if (d != null) return d.doubleValue();
        } catch (Throwable ignored) { }
        try {
            Object server = Bukkit.getServer();
            Object value = server.getClass().getMethod("getTPS").invoke(server);
            Double d = firstTps(value);
            if (d != null) return d.doubleValue();
        } catch (Throwable ignored) { }
        return 20.0;
    }

    private static Double firstTps(Object value) {
        double v;
        if (value instanceof double[]) {
            double[] all = (double[]) value;
            if (all.length == 0) return null;
            v = all[0];
        } else if (value instanceof Number) {
            v = ((Number) value).doubleValue();
        } else {
            return null;
        }
        if (Double.isNaN(v) || Double.isInfinite(v)) return null;
        return Double.valueOf(Math.max(0.0, Math.min(20.0, v)));
    }
}
