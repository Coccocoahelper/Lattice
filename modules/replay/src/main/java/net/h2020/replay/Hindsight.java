package net.h2020.replay;

import net.h2020.bots.ai.ml.CombatArbiter;
import net.h2020.bots.ai.ml.DuelPolicy;
import net.h2020.bots.ai.ml.FightModel;
import net.h2020.bots.ai.ml.MlSettings;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hindsight: training on stored experience instead of only on the tick that produced it.
 * <p>
 * Two kinds of stored experience feed this:
 * <ul>
 * <li><b>Decisions</b> - every mechanic and stance choice ever made, with the outcome it produced. A hindsight pass
 * replays a slice of them at a quarter of the online learning rate, which is the difference between a policy that
 * only learns from its last fight and one that keeps learning from ten thousand of them.</li>
 * <li><b>Finished games</b> - the recorded frames of a match that is over. A death in the frames gives a fight sample
 * with a label that could not be known while it was happening (who actually won), built from what the recording
 * knows: health, armour and weapons on both sides, gap, height difference.</li>
 * </ul>
 * Nothing here touches how bots read people: game-derived fight samples only go into the fight model when bot
 * training is explicitly enabled, and the human-calibrated models are never fed bot frames.
 */
public final class Hindsight {
    /** Hindsight runs at a quarter of the online learning rate. */
    public static final double SCALE = 0.25;

    private static JavaPlugin plugin;
    private static long decisionRows, gameSamples, passes;

    private Hindsight() {}

    public static void install(final JavaPlugin p) {
        plugin = p;
        Bukkit.getScheduler().runTaskTimer(p, new Runnable() {
            @Override public void run() {
                if (!Arena.running()) pass(4000);          // between arena games, never during one
            }
        }, 2400L, 2400L);                                   // every two minutes
    }

    public static String reportLine() {
        return String.format(java.util.Locale.ROOT,
                "§6[hindsight] §e%d§7 passes, §f%d§7 decision rows replayed, §f%d§7 fight samples from finished games",
                passes, decisionRows, gameSamples);
    }

    /** Replay the newest decision rows (+ optionally one finished game) at the hindsight learning rate. */
    public static void pass(int maxRows) { pass(maxRows, false); }

    /** @param anyState also read recordings that are still being written (manual passes only). */
    public static void pass(int maxRows, boolean anyState) {
        passes++;
        decisionRows += decisions(maxRows);
        if (MlSettings.trainFromBots()) gameSamples += games(anyState ? 3 : 1, anyState);
    }

    // ---------------------------------------------------------------------------------------------- decisions

    private static int decisions(int maxRows) {
        File dir = Replay.dir();
        if (dir == null) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        List<File> logs = new ArrayList<File>();
        for (File f : files) if (f.getName().startsWith("train-") && f.getName().endsWith(".jsonl")) logs.add(f);
        if (logs.isEmpty()) return 0;
        logs.sort(new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
        });
        int done = 0;
        for (File f : logs) {
            BufferedReader r = null;
            try {
                r = new BufferedReader(new FileReader(f));
                String line;
                while ((line = r.readLine()) != null && done < maxRows) {
                    if (line.contains("\"k\":\"mech\"")) {
                        int kind = (int) num(line, "m", -1);
                        double[] x = vec(line);
                        int a = (int) num(line, "a", 0);
                        int y = (int) num(line, "y", 0);
                        if (kind < 0 || x == null) continue;
                        CombatArbiter.learnQuiet(kind, x, a == 1, y == 1, SCALE);
                        done++;
                    } else if (line.contains("\"k\":\"stance\"")) {
                        double[] x = vec(line);
                        int a = (int) num(line, "a", 0);
                        double rw = num(line, "r", 0);
                        if (x == null) continue;
                        DuelPolicy.recordQuiet(x, a, rw, SCALE);
                        done++;
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                try { if (r != null) r.close(); } catch (Throwable ignored) { }
            }
            if (done >= maxRows) break;
        }
        return done;
    }

    // ---------------------------------------------------------------------------------------------- game frames

    /**
     * A finished game's frames, turned into fight samples: find deaths, attribute the kill to the nearest other
     * player in the second before it, and label the pair (the survivor won, the victim lost).
     */
    private static int games(int maxGames, boolean anyState) {
        File dir = Recorder.dir();
        if (dir == null) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        List<File> recs = new ArrayList<File>();
        for (File f : files) if (f.getName().endsWith(".jsonl")) recs.add(f);
        recs.sort(new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
        });
        int made = 0, done = 0;
        for (File f : recs) {
            if (done >= maxGames) break;
            if (!anyState && !finished(f)) continue;      // a recording still being written has no ending yet
            done++;
            made += oneGame(f);
        }
        return made;
    }

    /** True when the recording was closed properly (the writer adds an end marker). */
    private static boolean finished(File f) {
        String last = null;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) last = line;
        } catch (Throwable ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
        }
        return last != null && last.startsWith("{\"end\":");
    }

    private static int oneGame(File f) {
        Map<String, Double> lastHp = new HashMap<String, Double>();
        Map<String, double[]> lastPos = new HashMap<String, double[]>();
        Map<String, int[]> lastGear = new HashMap<String, int[]>();
        int samples = 0;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("{\"t\":")) continue;
                String body = line.substring(line.indexOf("\"p\":[") + 5);
                if (body.endsWith("]}")) body = body.substring(0, body.length() - 1);
                String[] rows = body.contains("],[") ? body.split(java.util.regex.Pattern.quote("],[")) : new String[]{body};
                Map<String, Double> hpNow = new HashMap<String, Double>();
                Map<String, double[]> posNow = new HashMap<String, double[]>();
                Map<String, int[]> gearNow = new HashMap<String, int[]>();
                for (String row : rows) {
                    String[] c = row.replace("[", "").replace("]", "").split(",");
                    if (c.length < 10) continue;
                    String nm = c[0].replace("\"", "");
                    hpNow.put(nm, parse(c[7], 20));
                    posNow.put(nm, new double[]{parse(c[2], 0), parse(c[3], 0), parse(c[4], 0)});
                    int held = c.length > 9 ? (int) parse(c[9], 0) : 0;
                    int helm = c.length > 10 ? (int) parse(c[10], 0) : 0;
                    int chest = c.length > 11 ? (int) parse(c[11], 0) : 0;
                    int legs = c.length > 12 ? (int) parse(c[12], 0) : 0;
                    int boots = c.length > 13 ? (int) parse(c[13], 0) : 0;
                    gearNow.put(nm, new int[]{held, helm, chest, legs, boots});
                }
                // deaths: health reaching zero, or the player vanishing from the frames (bots leave the game on death)
                Map<String, Double> died = new HashMap<String, Double>();
                for (Map.Entry<String, Double> e : hpNow.entrySet()) {
                    Double before = lastHp.get(e.getKey());
                    if (before != null && before > 0 && e.getValue() <= 0) died.put(e.getKey(), before);
                }
                for (Map.Entry<String, Double> e : lastHp.entrySet()) {
                    if (e.getValue() <= 0) continue;
                    if (!hpNow.containsKey(e.getKey())) died.put(e.getKey(), e.getValue());
                }
                // a respawn is a death too: full health again after a long teleport back to spawn
                for (Map.Entry<String, Double> e : hpNow.entrySet()) {
                    Double before = lastHp.get(e.getKey());
                    if (before == null || before >= 20 || e.getValue() < 20) continue;
                    double[] a = lastPos.get(e.getKey()), b = posNow.get(e.getKey());
                    if (a != null && b != null && dist(a, b) > 15) died.put(e.getKey(), before);
                }
                for (Map.Entry<String, Double> e : died.entrySet()) {
                    String victim = e.getKey();
                    Double before = e.getValue();
                    if (before == null || before <= 0) continue;
                    double[] vp = lastPos.get(victim);
                    if (vp == null) continue;
                    String killer = null;
                    double best = 6.0;
                    for (Map.Entry<String, double[]> k : posNow.entrySet()) {
                        if (k.getKey().equals(victim)) continue;
                        Double khp = hpNow.get(k.getKey());
                        if (khp == null || khp <= 0) continue;
                        double d = dist(vp, k.getValue());
                        if (d < best) { best = d; killer = k.getKey(); }
                    }
                    if (killer == null) continue;
                    double[] kp = posNow.get(killer);
                    int[] vg = lastGear.get(victim), kg = lastGear.get(killer);
                    FightModel.learn(frameFeatures(before, hpNow.get(killer), vg, kg, vp, kp), false, 0.3);
                    FightModel.learn(frameFeatures(hpNow.get(killer), before, kg, vg, kp, vp), true, 0.3);
                    samples += 2;
                }
                lastHp = hpNow;
                lastPos = posNow;
                lastGear = gearNow;
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
        }
        return samples;
    }

    /**
     * A fight vector built from a recording (same layout as FightModel.featuresRaw, with the fields a recording
     * cannot know left neutral).
     */
    private static double[] frameFeatures(double myHp, double theirHp, int[] myGear, int[] theirGear,
                                         double[] myPos, double[] theirPos) {
        double[] x = new double[FightModel.N];
        x[0] = 1;
        x[1] = Math.max(0, Math.min(1, myHp / 20.0));
        x[2] = Math.max(0, Math.min(1, theirHp / 20.0));
        x[3] = Math.max(-1, Math.min(1, (myHp - theirHp) / 20.0));
        double myArm = armor(myGear), theirArm = armor(theirGear);
        double myWep = weapon(myGear), theirWep = weapon(theirGear);
        x[4] = Math.max(-1, Math.min(1, ((myArm + myWep) - (theirArm + theirWep)) / 8.0));
        double d = dist(myPos, theirPos);
        x[5] = Math.max(0, Math.min(1, (10 - d) / 10.0));
        x[15] = Math.max(0, Math.min(1, theirArm / 10.0));
        x[16] = Math.max(0, Math.min(1, myArm / 10.0));
        x[17] = Math.max(-1, Math.min(1, (myWep - theirWep) / 8.0));
        return x;
    }

    /** Rough armour score from recorded item ids (leather 1, gold 1.5, chain 2, iron 3, diamond 4 per piece). */
    private static double armor(int[] gear) {
        if (gear == null) return 0;
        double s = 0;
        for (int i = 1; i <= 4; i++) s += pieceTier(gear[i]);
        return s;
    }

    private static double pieceTier(int id) {
        if (id <= 0) return 0;
        if (id >= 310 && id <= 317) return 4;      // diamond
        if (id >= 306 && id <= 309) return 3;      // iron
        if (id >= 302 && id <= 305) return 2;      // chain
        if (id >= 314 && id <= 317) return 1.5;    // gold
        if (id >= 298 && id <= 301) return 1;      // leather
        return 0.5;
    }

    /** Rough weapon value from the held item id. */
    private static double weapon(int[] gear) {
        if (gear == null) return 1;
        int id = gear[0];
        if (id == 276) return 5;      // diamond sword
        if (id == 267) return 4;      // iron sword
        if (id == 272) return 3;      // stone sword
        if (id == 283) return 2.5;    // gold sword
        if (id == 268) return 2;      // wood sword
        if (id == 258) return 3.5;    // iron axe
        if (id == 279) return 4.5;    // diamond axe
        return 1;
    }

    private static double dist(double[] a, double[] b) {
        if (a == null || b == null) return 99;
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double num(String line, String key, double def) {
        String k = "\"" + key + "\":";
        int i = line.indexOf(k);
        if (i < 0) return def;
        int j = i + k.length();
        int end = j;
        while (end < line.length() && "-0123456789.".indexOf(line.charAt(end)) >= 0) end++;
        try { return Double.parseDouble(line.substring(j, end)); } catch (Throwable t) { return def; }
    }

    private static double[] vec(String line) {
        int i = line.indexOf("\"x\":[");
        if (i < 0) return null;
        int j = i + 5;
        int end = line.indexOf(']', j);
        if (end < 0) return null;
        String[] parts = line.substring(j, end).split(",");
        double[] x = new double[parts.length];
        try {
            for (int k = 0; k < parts.length; k++) x[k] = Double.parseDouble(parts[k].trim());
        } catch (Throwable t) {
            return null;
        }
        return x;
    }

    private static double parse(String v, double def) {
        try { return Double.parseDouble(v.trim()); } catch (Throwable t) { return def; }
    }
}
