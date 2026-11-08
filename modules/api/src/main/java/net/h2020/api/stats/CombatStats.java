package net.h2020.api.stats;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Always-on, cheap PvP telemetry for bots (/bot pvp stats): how often each fight behaviour / item is used per
 * bot-minute of combat, clutch outcomes, knockback taken, and Watchdog flags per bot-minute. Counters are kept per
 * feature set ({@link #v2} on/off) so the old and the new fight behaviour can be compared on the same server run
 * (/bot pvp v1|v2). Main thread only.
 */
public final class CombatStats {
    /** The 2020-style fight behaviour (footwork, clutches, items). /bot pvp v1 switches back to the old behaviour. */
    public static volatile boolean v2 = true;
    /** /bot pvp debug: log clutch decisions to the console. */
    public static volatile boolean debug = false;

    public static void log(String s) { if (debug) org.bukkit.Bukkit.getLogger().info("[pvpdebug] " + s); }

    private static final Map<String, double[]> C = new TreeMap<>();
    private static final long[] combatTicks = new long[2], gameTicks = new long[2];
    private static long since = System.currentTimeMillis();

    /** v1, v2, or ab (alternate every 2 minutes, for a fair side-by-side on the same games). Persisted. */
    private static String mode = "v2";
    private static java.io.File file;

    private CombatStats() {}

    public static String mode() { return mode; }

    public static void setMode(String m) {
        mode = m;
        if (m.equals("v1")) v2 = false;
        else if (m.equals("v2")) v2 = true;
        else abTick();
    }

    private static void abTick() {
        if (mode.equals("ab")) v2 = (System.currentTimeMillis() / 120000L) % 2 == 0;
    }

    /** Load the saved counters/mode (they survive restarts) and keep saving them once a minute. */
    public static void install(org.bukkit.plugin.java.JavaPlugin p) {
        file = new java.io.File(p.getDataFolder(), "pvp-stats.yml");
        if (file.exists()) {
            try {
                org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
                setMode(y.getString("mode", "v2"));
                since = y.getLong("since", since);
                for (int s = 0; s < 2; s++) {
                    gameTicks[s] = y.getLong("game." + s);
                    combatTicks[s] = y.getLong("combat." + s);
                }
                org.bukkit.configuration.ConfigurationSection c = y.getConfigurationSection("c");
                if (c != null) for (String k : c.getKeys(false)) {
                    java.util.List<Double> l = c.getDoubleList(k);
                    if (l.size() == 2) C.put(k.replace('~', '.'), new double[]{l.get(0), l.get(1)});
                }
            } catch (Throwable t) { p.getLogger().warning("[pvp] stats load: " + t); }
        }
        org.bukkit.Bukkit.getScheduler().runTaskTimer(p, new Runnable() {
            int n;
            public void run() { abTick(); if (++n % 60 == 0) save(); }
        }, 20L, 20L);
    }

    public static void save() {
        if (file == null) return;
        if (mode.equals("v2") && C.isEmpty()) { if (file.exists()) file.delete(); return; }
        try {
            org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
            y.set("mode", mode);
            y.set("since", since);
            for (int s = 0; s < 2; s++) { y.set("game." + s, gameTicks[s]); y.set("combat." + s, combatTicks[s]); }
            for (Map.Entry<String, double[]> e : C.entrySet())
                y.set("c." + e.getKey().replace('.', '~'), java.util.Arrays.asList(e.getValue()[0], e.getValue()[1]));
            y.save(file);
        } catch (Throwable ignored) {}
    }

    private static int seg() { return v2 ? 1 : 0; }

    public static void inc(String key) { add(key, 1); }

    /** Current value of one counter (both feature sets), for the training ledger. */
    public static double total(String key) {
        double[] v = C.get(key);
        return v == null ? 0 : v[0] + v[1];
    }

    /** Every counter with a non-zero value, for debugging (/bot counters). */
    public static List<String> dump() {
        List<String> out = new ArrayList<String>();
        out.add("§8=== counters");   // marker: one dump per marker, so a reader can take the last block
        for (Map.Entry<String, double[]> e : C.entrySet()) {
            double v = e.getValue()[0] + e.getValue()[1];
            if (v != 0) out.add("§7" + e.getKey() + "§f=" + (long) v);
        }
        return out;
    }

    public static void add(String key, double n) {
        double[] v = C.get(key);
        if (v == null) C.put(key, v = new double[2]);
        v[seg()] += n;
    }

    /** One bot tick inside a game (alive, able to act); engaged = in a fight. */
    public static void tick(boolean engaged) {
        gameTicks[seg()]++;
        if (engaged) combatTicks[seg()]++;
    }

    public static void reset() {
        C.clear();
        combatTicks[0] = combatTicks[1] = gameTicks[0] = gameTicks[1] = 0;
        since = System.currentTimeMillis();
        if (file != null && file.exists()) file.delete();
        save(); // keeps the mode across restarts
    }

    private static double get(String k, int s) { double[] v = C.get(k); return v == null ? 0 : v[s]; }

    public static List<String> report() {
        List<String> out = new ArrayList<>();
        out.add(String.format("§6[pvp] §e%s§7 active (mode %s); %.1f min sampled", v2 ? "v2" : "v1", mode, (System.currentTimeMillis() - since) / 60000.0));
        for (int s = 0; s < 2; s++) {
            double cm = combatTicks[s] / 1200.0, gm = gameTicks[s] / 1200.0;
            if (gm <= 0) continue;
            out.add(String.format("§e%s§7: %.1f bot-min in games, %.1f bot-min fighting", s == 0 ? "v1" : "v2", gm, cm));
            StringBuilder use = new StringBuilder();
            for (Map.Entry<String, double[]> e : C.entrySet()) {
                String k = e.getKey();
                double v = e.getValue()[s];
                if (v == 0 || k.startsWith("flag.") || k.startsWith("kb.") || k.startsWith("clutch.") || k.startsWith("fall.")) continue;
                use.append(String.format(" %s=%.0f(%.2f/m)", k, v, cm > 0 ? v / cm : 0));
            }
            if (use.length() > 0) out.add("§7 use/fight-min:" + use);
            // clutches
            StringBuilder cl = new StringBuilder();
            for (String t : new String[]{"block", "water", "pearl"}) {
                double a = get("clutch." + t + ".try", s), ok = get("clutch." + t + ".ok", s);
                if (a > 0) cl.append(String.format(" %s %d/%d (%.0f%%)", t, (int) ok, (int) a, 100 * ok / a));
            }
            double falls = get("fall.void", s), saved = get("fall.saved", s), died = get("fall.died", s);
            out.add(String.format("§7 void falls %d (%d after a knock): saved %d, died %d%s; clutch blocks placed %d", (int) falls,
                    (int) get("fall.void.knocked", s), (int) saved, (int) died, cl.length() > 0 ? " | clutch" + cl : "", (int) get("clutch.block.place", s)));
            // knockback taken (horizontal blocks pushed away 10 ticks after a melee hit)
            double n = get("kb.n", s), sum = get("kb.sum", s), jn = get("kb.jr.n", s), js = get("kb.jr.sum", s);
            if (n > 0) out.add(String.format("§7 KB taken: %.2f blocks/hit over %d hits%s", sum / n, (int) n,
                    jn > 0 ? String.format(" (jump-reset %.2f over %d, others %.2f)", js / jn, (int) jn, n - jn > 0 ? (sum - js) / (n - jn) : 0) : ""));
            // watchdog
            double fAll = get("flag.all", s), fFight = get("flag.fight", s);
            StringBuilder fs = new StringBuilder();
            for (Map.Entry<String, double[]> e : C.entrySet()) {
                if (!e.getKey().startsWith("flag.c.") || e.getValue()[s] == 0) continue;
                fs.append(' ').append(e.getKey().substring(7)).append('=').append((int) e.getValue()[s]);
            }
            StringBuilder ff = new StringBuilder();
            for (Map.Entry<String, double[]> e : C.entrySet()) {
                if (!e.getKey().startsWith("flag.f.") || e.getValue()[s] == 0) continue;
                ff.append(' ').append(e.getKey().substring(7)).append('=').append((int) e.getValue()[s]);
            }
            out.add(String.format("§7 Watchdog: %.3f flags/bot-min (%d), in fights %.3f/fight-min (%d)%s%s",
                    fAll / gm, (int) fAll, cm > 0 ? fFight / cm : 0, (int) fFight, fs.length() > 0 ? " | all:" + fs : "",
                    ff.length() > 0 ? " | fights:" + ff : ""));
        }
        return out;
    }
}
