package net.h2020.core.game;

import net.h2020.api.H2020;
import net.h2020.api.bot.Bot;
import net.h2020.api.bot.BotProfile;
import net.h2020.api.bot.BotService;
import net.h2020.api.bot.CheatProfile;
import net.h2020.api.game.Game;
import net.h2020.api.game.GameState;
import net.h2020.api.game.GameType;
import net.h2020.api.map.MapKeys;
import net.h2020.api.map.MapService;
import net.h2020.api.map.MapTemplate;
import net.h2020.core.CoreConfig;
import net.h2020.core.CoreUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Trickles bots into waiting games like a real queue: a per-game target fill time (20-60 s by default), exponential
 * gaps between joins, occasional bursts of 2-4 (parties / a lobby emptying), getting faster as the game nears full.
 * Also keeps `background-games` bot-only games running so the network feels alive.
 */
public final class BotQueueFiller implements Runnable {
    private static final long PERIOD_TICKS = 2;

    private final JavaPlugin plugin;
    private final CoreConfig config;
    private final GameManagerImpl manager;
    private final Map<String, FillState> states = new HashMap<>();
    /** bots spawned but not yet added (added one tick later) */
    private final Map<String, Integer> pending = new HashMap<>();
    private long tick = 0;

    private static final class FillState {
        final double targetSeconds;
        long nextAtMs;
        int burstLeft;
        FillState(double targetSeconds, long nextAtMs) { this.targetSeconds = targetSeconds; this.nextAtMs = nextAtMs; }
    }

    public BotQueueFiller(JavaPlugin plugin, CoreConfig config, GameManagerImpl manager) {
        this.plugin = plugin;
        this.config = config;
        this.manager = manager;
    }

    public void start() { Bukkit.getScheduler().runTaskTimer(plugin, this, 40L, PERIOD_TICKS); }

    void forget(Game g) { states.remove(g.id()); }

    private int pending(Game g) { Integer i = pending.get(g.id()); return i == null ? 0 : i; }

    @Override
    public void run() {
        tick += PERIOD_TICKS;
        BotService bs = H2020.find(BotService.class);
        if (bs == null) return;
        long now = System.currentTimeMillis();
        if (config.botFill) {
            for (Game g : manager.games()) {
                GameState s = g.state();
                if (s != GameState.WAITING && s != GameState.STARTING) continue;
                if (g.privateCode() != null) continue;
                int size = g.players().size() + pending(g), max = g.type().maxPlayers();
                if (size >= max) continue;
                FillState st = states.get(g.id());
                if (st == null) {
                    double target = rand(config.fillMinSeconds, config.fillMaxSeconds) / config.fillSpeed;
                    st = new FillState(target, now + (long) (rand(0.5, 2.5) * 1000 / config.fillSpeed));
                    states.put(g.id(), st);
                }
                if (now < st.nextAtMs) continue;
                if (net.h2020.api.core.ReplayState.isHost(g.id())) continue;
                spawnInto(bs, g);
                st.nextAtMs = now + nextGapMs(st, size + 1, max);
            }
        }
        if (tick % 200 == 0) maintainBackground();
    }

    private long nextGapMs(FillState st, int size, int max) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (st.burstLeft > 0) {
            st.burstLeft--;
            return (long) (rand(150, 700) / config.fillSpeed);
        }
        if (r.nextDouble() < 0.18 && max - size >= 2) st.burstLeft = 1 + r.nextInt(3);
        double mean = st.targetSeconds / max;
        double ratio = size / (double) max;
        double factor = 1.15 - 0.6 * ratio; // faster near full
        double gap = -Math.log(1 - r.nextDouble()) * mean * factor;
        gap = Math.max(0.25 / config.fillSpeed, Math.min(gap, mean * 3.5));
        return (long) (gap * 1000);
    }

    /** Log a fresh bot into the game's waiting spawn and add it next tick. */
    public Bot spawnInto(BotService bs, final Game g) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        BotProfile profile;
        try {
            if (config.cheaterPercent > 0 && r.nextDouble() * 100 < config.cheaterPercent && !config.cheatTypes.isEmpty()) {
                CheatProfile cp = config.cheatTypes.get(r.nextInt(config.cheatTypes.size()));
                profile = bs.randomProfile(cp);
            } else {
                profile = bs.randomProfile();
            }
            final Bot bot = bs.spawn(profile, waitingSpawn(g));
            if (bot == null) return null;
            pending.put(g.id(), pending(g) + 1);
            Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                @Override public void run() {
                    int left = pending(g) - 1;
                    if (left <= 0) pending.remove(g.id()); else pending.put(g.id(), left);
                    Player p = bot.player();
                    if (bot.isRemoved() || p == null || !p.isOnline()) return;
                    if (manager.gameOf(p) != null) return;
                    boolean ok = GameManagerImpl.joinable(g) && manager.games().contains(g);
                    if (ok) {
                        try { ok = g.addPlayer(p); } catch (Throwable t) {
                            plugin.getLogger().log(Level.WARNING, "Bot addPlayer failed in " + g.id(), t);
                            ok = false;
                        }
                    }
                    if (ok) manager.trackBot(g, p);
                    else {
                        bot.remove();
                        // the game refused although core thinks it has room (e.g. a map with fewer islands than the
                        // mode's max): stop filling it instead of spawning and removing bots forever
                        FillState st = states.get(g.id());
                        if (st != null && GameManagerImpl.joinable(g)) st.nextAtMs = Long.MAX_VALUE;
                    }
                }
            }, 1L);
            return bot;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Queue filler could not spawn a bot for " + g.id(), t);
            return null;
        }
    }

    /** Instantly fill a game with bots (used by /h2020 start / fill). Returns how many were spawned. */
    public int fillNow(Game g, int count) {
        BotService bs = H2020.find(BotService.class);
        if (bs == null) return 0;
        int free = g.type().maxPlayers() - g.players().size() - pending(g);
        int n = Math.min(free, count);
        for (int i = 0; i < n; i++) spawnInto(bs, g);
        return n;
    }

    /** The template's waiting-lobby point in the game world (fallback: spectator spawn / world spawn). */
    public static Location waitingSpawn(Game g) {
        World w = g.world();
        MapService ms = H2020.find(MapService.class);
        if (ms != null && w != null) {
            try {
                MapTemplate t = ms.byName(g.mapName());
                if (t != null) {
                    List<Vector> pts = t.points(MapKeys.WAITING_LOBBY);
                    if (pts != null && !pts.isEmpty()) {
                        Vector v = pts.get(ThreadLocalRandom.current().nextInt(pts.size()));
                        Location l = new Location(w, v.getX(), v.getY(), v.getZ(), t.yaw(MapKeys.WAITING_LOBBY, 0), 0);
                        double ox = rand(-2.5, 2.5), oz = rand(-2.5, 2.5);
                        Location jitter = l.clone().add(ox, 0, oz);
                        if (jitter.getBlock().getType().isSolid() || !jitter.clone().add(0, -1, 0).getBlock().getType().isSolid()) return l;
                        return jitter;
                    }
                }
            } catch (Throwable ignored) {}
        }
        try {
            Location s = g.spectatorSpawn();
            if (s != null) return s;
        } catch (Throwable ignored) {}
        return w != null ? w.getSpawnLocation() : Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    /** Keep N bot-only games alive. */
    private void maintainBackground() {
        if (config.backgroundGames <= 0) return;
        int botOnly = 0;
        for (Game g : manager.games()) {
            if (g.state() == GameState.ENDING) continue;
            boolean human = false;
            for (Player p : g.players()) if (!CoreUtil.isBot(p)) { human = true; break; }
            if (!human) botOnly++;
        }
        if (botOnly >= config.backgroundGames) return;
        List<GameType> types = config.backgroundTypes;
        GameType type = types.get(ThreadLocalRandom.current().nextInt(types.size()));
        if (!manager.hasProvider(type.family)) return;
        Game g = manager.createRandom(type);
        if (g != null) plugin.getLogger().info("Background game " + g.id() + " (" + type.id() + ") created");
    }

    private static double rand(double a, double b) { return a + ThreadLocalRandom.current().nextDouble() * (b - a); }
}
