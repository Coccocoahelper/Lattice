package net.h2020.replay;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.FileReader;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;

/**
 * One line per arena game: which generation played it, at what skill band, and how it went (kills, deaths, bed
 * breaks, void falls, bridge launch time). The ledger is the human-readable history of a self-play run; the replay
 * log is the machine-readable one.
 */
public final class Ledger {
    private static File file;

    private Ledger() {}

    public static void install(JavaPlugin p) {
        if (p == null) return;
        File dir = new File(p.getDataFolder(), "training");
        if (!dir.isDirectory()) dir.mkdirs();
        file = new File(dir, "ledger.jsonl");
    }

    public static void row(String slot, double skill, String gameId, int kills, int deaths, int beds,
                           int voidFalls, double launchTicks, String phase) {
        if (file == null) return;
        String line = String.format(java.util.Locale.ROOT,
                "{\"t\":%d,\"slot\":\"%s\",\"phase\":\"%s\",\"skill\":%.2f,\"game\":\"%s\",\"kills\":%d,\"deaths\":%d,"
                        + "\"beds\":%d,\"void\":%d,\"launch\":%.1f}",
                System.currentTimeMillis(), slot, phase, skill, gameId, kills, deaths, beds, voidFalls, launchTicks);
        try {
            BufferedWriter w = new BufferedWriter(new FileWriter(file, true));
            try { w.write(line); w.write('\n'); } finally { w.close(); }
        } catch (Throwable ignored) { }
    }

    /** Totals per slot over the whole run: {kills, deaths, beds, void, games, launchSum}. */
    public static double[] totals(String slot) {
        double[] t = new double[6];
        if (file == null || !file.isFile()) return t;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(file));
            String line;
            while ((line = r.readLine()) != null) {
                if (slot != null && !line.contains("\"slot\":\"" + slot + "\"")) continue;
                t[0] += num(line, "kills");
                t[1] += num(line, "deaths");
                t[2] += num(line, "beds");
                t[3] += num(line, "void");
                t[4] += 1;
                t[5] += num(line, "launch");
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
        }
        return t;
    }

    private static double num(String line, String key) {
        String k = "\"" + key + "\":";
        int i = line.indexOf(k);
        if (i < 0) return 0;
        int j = i + k.length();
        int end = j;
        while (end < line.length() && "-0123456789.".indexOf(line.charAt(end)) >= 0) end++;
        try { return Double.parseDouble(line.substring(j, end)); } catch (Throwable t) { return 0; }
    }
}
