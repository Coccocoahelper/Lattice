package net.h2020.bots.ai.brain;

import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * SkyWars behaviour counters per map, for measuring decision making: the first plan each bot picks after looting
 * (how many go straight to mid), what the bots were doing when their fights started and which of those fights
 * ended in a kill, time from cage opening to the first fight, and confusion right after the cage opens (stuck
 * episodes, failed routes, seconds spent waiting for a route, chests given up). `/bot swstats [reset]`.
 */
public final class SwStats {
    private SwStats() {}

    /** Log every plan choice / reaction to the console (`/bot swstats debug`). */
    public static volatile boolean debug = Boolean.getBoolean("h2020.swdebug");

    static final class Counters {
        int bots;
        final Map<String, Integer> firstPlan = new TreeMap<>();
        final Map<String, Integer> plans = new TreeMap<>();
        final Map<String, Integer> fights = new TreeMap<>();
        final Map<String, Integer> kills = new TreeMap<>();
        final Map<String, Integer> reactions = new TreeMap<>();
        final List<Double> firstFightSec = new ArrayList<>();
        int earlyStuck, earlyFails, earlyLootFails, earlyDeaths;
        double earlyPlanningSec;
        int earlyBots;
    }

    private static final Map<String, Counters> BY_MAP = new TreeMap<>();

    private static Counters of(World w) {
        return BY_MAP.computeIfAbsent(net.h2020.bots.ai.nav.NavStats.key(w), k -> new Counters());
    }

    private static void inc(Map<String, Integer> m, String k) { m.merge(k, 1, Integer::sum); }

    static synchronized void bot(World w) { of(w).bots++; }

    static synchronized void plan(World w, String bot, String plan, boolean first, String why) {
        Counters c = of(w);
        inc(c.plans, plan);
        if (first) inc(c.firstPlan, plan);
        if (debug) org.bukkit.Bukkit.getLogger().info("[swdebug] " + bot + (first ? " FIRST " : " ") + plan + " " + why);
    }

    static synchronized void reaction(World w, String bot, String what) {
        inc(of(w).reactions, what);
        if (debug) org.bukkit.Bukkit.getLogger().info("[swdebug] " + bot + " reacts: " + what);
    }

    static synchronized void fight(World w, String origin, double sinceOpen, boolean first) {
        Counters c = of(w);
        inc(c.fights, origin);
        if (first) c.firstFightSec.add(sinceOpen);
    }

    static synchronized void kill(World w, String origin) { inc(of(w).kills, origin); }

    static synchronized void early(World w, int stuck, int fails, double planningSec, int lootFails, boolean died) {
        Counters c = of(w);
        c.earlyBots++;
        c.earlyStuck += stuck;
        c.earlyFails += fails;
        c.earlyPlanningSec += planningSec;
        c.earlyLootFails += lootFails;
        if (died) c.earlyDeaths++;
    }

    public static synchronized List<String> report() {
        List<String> out = new ArrayList<>();
        Counters total = new Counters();
        for (Map.Entry<String, Counters> e : BY_MAP.entrySet()) {
            out.add(line(e.getKey(), e.getValue()));
            merge(total, e.getValue());
        }
        out.add(line("TOTAL", total));
        return out;
    }

    private static void merge(Counters t, Counters c) {
        t.bots += c.bots;
        for (Map.Entry<String, Integer> e : c.firstPlan.entrySet()) t.firstPlan.merge(e.getKey(), e.getValue(), Integer::sum);
        for (Map.Entry<String, Integer> e : c.plans.entrySet()) t.plans.merge(e.getKey(), e.getValue(), Integer::sum);
        for (Map.Entry<String, Integer> e : c.fights.entrySet()) t.fights.merge(e.getKey(), e.getValue(), Integer::sum);
        for (Map.Entry<String, Integer> e : c.kills.entrySet()) t.kills.merge(e.getKey(), e.getValue(), Integer::sum);
        for (Map.Entry<String, Integer> e : c.reactions.entrySet()) t.reactions.merge(e.getKey(), e.getValue(), Integer::sum);
        t.firstFightSec.addAll(c.firstFightSec);
        t.earlyStuck += c.earlyStuck; t.earlyFails += c.earlyFails; t.earlyLootFails += c.earlyLootFails;
        t.earlyPlanningSec += c.earlyPlanningSec; t.earlyBots += c.earlyBots; t.earlyDeaths += c.earlyDeaths;
    }

    private static String line(String k, Counters c) {
        int firsts = 0;
        for (int v : c.firstPlan.values()) firsts += v;
        Integer mid = c.firstPlan.get("MID");
        List<Double> ff = new ArrayList<>(c.firstFightSec);
        java.util.Collections.sort(ff);
        String med = ff.isEmpty() ? "-" : String.format("%.0fs", ff.get(ff.size() / 2));
        return String.format("%s: bots %d | first plan %s (straight to mid %.0f%%) | plans %s | fights by origin %s | kills by origin %s"
                        + " | first fight median %s | first 20s: stuck %d, failed routes %d, waiting for a route %.1fs/bot, chests given up %d, deaths %d (%d bots) | reactions %s",
                k, c.bots, c.firstPlan, firsts == 0 ? 0.0 : 100.0 * (mid == null ? 0 : mid) / firsts, c.plans, c.fights, c.kills, med,
                c.earlyStuck, c.earlyFails, c.earlyBots == 0 ? 0 : c.earlyPlanningSec / c.earlyBots, c.earlyLootFails, c.earlyDeaths, c.earlyBots, c.reactions);
    }

    public static synchronized void reset() { BY_MAP.clear(); }
}
