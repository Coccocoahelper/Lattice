package net.h2020.replay;

import net.h2020.api.stats.CombatStats;
import net.h2020.api.H2020;
import net.h2020.api.bot.Bot;
import net.h2020.api.bot.BotService;
import net.h2020.api.game.Game;
import net.h2020.api.game.GameManager;
import net.h2020.api.game.GameType;
import net.h2020.api.map.MapService;
import net.h2020.api.map.MapTemplate;
import org.bukkit.Bukkit;
import net.h2020.bots.ai.BotAI;
import net.h2020.bots.BotManager;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * The arena: games played by bots alone, on a loop, so the policies can train themselves without a human in the loop.
 * <p>
 * A run alternates two phases. <b>Training</b>: N games with the live policy files, learning on, at a cycling skill
 * band so the policies do not overfit one kind of opponent. Then a snapshot becomes the challenger. <b>Evaluation</b>:
 * M games alternating the champion and the challenger generation, one per game. The two are then compared offline on
 * the replay holdout (see {@link Gate}) and the better one is kept.
 * <p>
 * The point of the split is that a generation can only be promoted on data it never trained on - so "trains itself"
 * cannot mean "got better at beating itself in the games it just memorised".
 */
public final class Arena {
    public enum Phase { IDLE, TRAIN, EVAL, DONE }

    private static final double[] BANDS = {0.32, 0.5, 0.68, 0.45, 0.58};
    private static JavaPlugin plugin;
    private static Phase phase = Phase.IDLE;
    private static boolean alternator;          // even eval games: champion, odd: challenger

    private static int trainGames = 6, evalGames = 4, totalGames = 40;
    private static int gamesPlayed, gamesInPhase;
    private static Game game;
    private static String gameSlot = "LIVE";
    private static double gameSkill;
    private static long gameStart;
    private static int kills0, deaths0, beds0, void0;
    private static long replay0;
    private static String lastGate = "-";
    private static int botCount = 15;

    private Arena() {}

    public static void install(final JavaPlugin p) {
        plugin = p;
        loadState();
        Bukkit.getScheduler().runTaskTimer(p, new Runnable() {
            @Override public void run() { tick(); }
        }, 200L, 40L);                                        // every 2 s
    }

    public static boolean running() { return phase == Phase.TRAIN || phase == Phase.EVAL; }
    public static Phase phase() { return phase; }

    public static String start(int total, int train, int eval) {
        if (running()) return "already running (" + phase + ")";
        if (Trainer.active()) return "trainer is running - stop it first";
        totalGames = Math.max(2, total);
        trainGames = Math.max(1, train);
        evalGames = Math.max(2, eval);
        gamesPlayed = 0;
        gamesInPhase = 0;
        alternator = false;
        League.ensureChampion();
        League.snapshot(League.challengerDir());
        phase = Phase.TRAIN;
        saveState();
        return "starting: " + trainGames + " training games, then " + evalGames + " evaluation games per generation, "
                + totalGames + " games in total";
    }

    public static String stop() {
        if (!running()) return "not running";
        phase = Phase.DONE;
        if (game != null) { try { game.shutdown(); } catch (Throwable ignored) { } game = null; }
        return "stopped after " + gamesPlayed + " games";
    }

    private static void saveState() {
        try {
            java.io.File f = new java.io.File(plugin.getDataFolder(), "training/arena.yml");
            org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
            y.set("phase", phase.name());
            y.set("games", gamesPlayed);
            y.set("phaseGames", gamesInPhase);
            y.set("total", totalGames);
            y.set("train", trainGames);
            y.set("eval", evalGames);
            y.set("alternator", alternator);
            if (!f.getParentFile().exists()) f.getParentFile().mkdirs();
            y.save(f);
        } catch (Throwable ignored) { }
    }

    private static void loadState() {
        try {
            java.io.File f = new java.io.File(plugin.getDataFolder(), "training/arena.yml");
            if (!f.isFile()) return;
            org.bukkit.configuration.file.YamlConfiguration y =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
            gamesPlayed = y.getInt("games", 0);
            gamesInPhase = y.getInt("phaseGames", 0);
            totalGames = y.getInt("total", 40);
            trainGames = y.getInt("train", 6);
            evalGames = y.getInt("eval", 4);
            alternator = y.getBoolean("alternator", false);
            String ph = y.getString("phase", "IDLE");
            phase = "TRAIN".equals(ph) || "EVAL".equals(ph) ? Phase.valueOf(ph) : Phase.IDLE;
            if (running()) plugin.getLogger().info("[train] resumed " + phase + " at game " + gamesPlayed + "/" + totalGames);
        } catch (Throwable ignored) { }
    }

    public static List<String> report() {
        List<String> out = new ArrayList<String>();
        out.add("§6[train] §ephase §f" + phase + "§7, games §f" + gamesPlayed + "/" + totalGames
                + "§7, this phase §f" + gamesInPhase);
        double[] all = Ledger.totals(null);
        out.add(String.format(java.util.Locale.ROOT,
                "§7online: §f%.0f§7 games, §f%.0f§7 kills, §f%.0f§7 deaths (K/D §f%.2f§7), §f%.1f§7 beds per game,"
                        + " §f%.1f§7 void falls per game, launch §f%.1f§7 ticks",
                all[4], all[0], all[1], all[1] == 0 ? all[0] : all[0] / all[1],
                all[4] == 0 ? 0 : all[2] / all[4], all[4] == 0 ? 0 : all[3] / all[4],
                all[4] == 0 ? 0 : all[5] / all[4]));
        double[] champ = Ledger.totals("CHAMPION"), chal = Ledger.totals("CHALLENGER");
        if (champ[4] > 0 || chal[4] > 0) {
            out.add(String.format(java.util.Locale.ROOT,
                    "§7evaluation: champion §f%.2f§7 K/D over §f%.0f§7 games, challenger §f%.2f§7 over §f%.0f§7",
                    champ[1] == 0 ? 0 : champ[0] / champ[1], champ[4],
                    chal[1] == 0 ? 0 : chal[0] / chal[1], chal[4]));
        }
        out.add("§6[train] §elast gate: §7" + lastGate);
        out.add(String.format(java.util.Locale.ROOT,
                "§7replays: §f%d§7 rows, holdout §f%d§7 rows", Replay.rows(), Replay.holdoutRows()));
        out.add(Hindsight.reportLine());
        out.addAll(League.report());
        return out;
    }

    /** A bot killed a bot: counted for the game being played. */
    public static void noteKill() { if (running()) kills0++; }
    public static void noteDeath() { if (running()) deaths0++; }

    // ----------------------------------------------------------------------------------------------- scheduler

    private static void tick() {
        if (!running()) return;
        if (game == null) { startGame(); return; }
        if (!H2020.find(GameManager.class).games().contains(game) || game.state() == net.h2020.api.game.GameState.ENDING) {
            endGame();
            return;
        }
        if (System.currentTimeMillis() - gameStart > 20L * 60L * 1000L) {
            // never force a teardown from here: WindSpigot can race a world unload against its own world ticker and
            // take the server with it. A game that will not finish is a stop condition for the run, not a crash.
            plugin.getLogger().warning("[train] game " + game.id() + " has run for 20 minutes, stopping the run");
            phase = Phase.DONE;
            game = null;
        }
    }

    private static void startGame() {
        MapService maps = H2020.find(MapService.class);
        GameManager gm = H2020.find(GameManager.class);
        BotService bs = H2020.find(BotService.class);
        if (maps == null || gm == null || bs == null) { phase = Phase.DONE; return; }
        List<MapTemplate> templates = maps.templatesFor(GameType.BEDWARS_4V4V4V4);
        if (templates.isEmpty()) { phase = Phase.DONE; return; }

        // which generation plays this game
        if (phase == Phase.TRAIN) {
            gameSlot = "LIVE";
        } else {
            gameSlot = alternator ? "CHALLENGER" : "CHAMPION";
            alternator = !alternator;
            if (gameSlot.equals("CHAMPION")) League.restore(League.championDir());
            else League.restore(League.challengerDir());
        }
        gameSkill = BANDS[gamesPlayed % BANDS.length];
        ((BotManager) bs).setAverageSkill(gameSkill);

        MapTemplate map = templates.get(gamesPlayed % templates.size());
        Game g = gm.create(GameType.BEDWARS_4V4V4V4, map);
        if (g == null) { phase = Phase.DONE; return; }
        game = g;
        gameStart = System.currentTimeMillis();
        kills0 = 0; deaths0 = 0;
        // the combat counters are cumulative across the whole server run: take a baseline, record the delta
        beds0 = (int) net.h2020.api.stats.CombatStats.total("bed.raid.ok");
        void0 = (int) net.h2020.api.stats.CombatStats.total("fall.void");
        replay0 = net.h2020.api.train.Replay.rows();

        int want = Math.min(botCount, g.type().maxPlayers());
        for (int i = 0; i < want; i++) {
            spawnBot(bs, g);
        }
        plugin.getLogger().info("[train] arena game " + g.id() + " (" + map.name() + ") slot=" + gameSlot
                + " skill=" + String.format(java.util.Locale.ROOT, "%.2f", gameSkill));
    }

    private static void spawnBot(BotService bs, final Game g) {
        try {
            final Bot bot = bs.spawn(bs.randomProfile(), g.world().getSpawnLocation());
            if (bot == null) return;
            Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                @Override public void run() {
                    Player p = bot.player();
                    if (bot.isRemoved() || p == null || !p.isOnline()) return;
                    if (H2020.find(GameManager.class).gameOf(p) != null) return;
                    boolean ok = false;
                    try { ok = g.addPlayer(p); } catch (Throwable ignored) { }
                    if (!ok) bot.remove();
                }
            }, 2L);
        } catch (Throwable t) {
            plugin.getLogger().warning("[train] could not spawn a bot: " + t);
        }
    }

    private static void endGame() {
        Game g = game;
        game = null;
        int beds = (int) Math.max(0, net.h2020.api.stats.CombatStats.total("bed.raid.ok") - beds0);
        int falls = (int) Math.max(0, net.h2020.api.stats.CombatStats.total("fall.void") - void0);
        double launch = net.h2020.bots.ai.motor.Bridger.averageLaunchTicks();
        Ledger.row(gameSlot, gameSkill, g == null ? "-" : g.id(), kills0, deaths0, beds, falls, launch, phase.name());
        plugin.getLogger().info("[train] game " + (g == null ? "-" : g.id()) + " done: " + kills0 + "/" + deaths0
                + " K/D, " + beds + " beds, " + falls + " void falls, " + (net.h2020.api.train.Replay.rows() - replay0)
                + " replay rows");
        gamesPlayed++;
        gamesInPhase++;

        if (phase == Phase.TRAIN && gamesInPhase >= trainGames) {
            League.snapshot(League.challengerDir());
            phase = Phase.EVAL;
            gamesInPhase = 0;
            alternator = false;
            saveState();
            plugin.getLogger().info("[train] training phase done, challenger snapshotted, starting evaluation");
            return;
        }
        if (phase == Phase.EVAL && gamesInPhase >= evalGames) {
            Gate.Result r = Gate.evaluate();
            lastGate = r.line().replaceAll("§.", "");
            phase = Phase.TRAIN;
            gamesInPhase = 0;
            saveState();
            // keep the next generation's starting point fixed: a fresh copy of whatever is live now
            League.snapshot(League.challengerDir());
            if (gamesPlayed >= totalGames) { phase = Phase.DONE; saveState(); plugin.getLogger().info("[train] run finished"); }
        }
    }
}
