package net.h2020.replay;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Every decision worth learning from, written down: the state vector, the action taken, and how it turned out.
 * <p>
 * This is what makes self-play auditable. Two files come out of it: the training stream (everything) and a holdout
 * (one line in five, never trained on), which is what a new generation is scored against before it is allowed to
 * replace the old one. Records are small JSON objects written by a background thread, so the game tick never waits on
 * the disk.
 * <p>
 * Kinds: {@code mech} (a combat mechanic: model kind, state, action, good/bad), {@code stance} (duel window, state,
 * stance, reward), {@code fight} (fight model features + win), and {@code human.*} rows which are evaluation-only.
 */
public final class Replay {
    public static final int HOLDOUT_EVERY = 5;
    public static final long HOLDOUT_CAP = 200_000L;

    private static final BlockingQueue<String> Q = new LinkedBlockingQueue<String>(200_000);
    private static final DecimalFormat DF = new DecimalFormat("0.####", new DecimalFormatSymbols(Locale.ROOT));
    private static final AtomicLong rows = new AtomicLong();
    private static final AtomicLong holdout = new AtomicLong();
    private static File dir;
    private static File holdoutFile;
    private static volatile boolean running;
    private static long seq;
    private static Thread worker;

    private Replay() {}

    public static void install(final JavaPlugin plugin) {
        if (plugin == null) return;
        dir = new File(plugin.getDataFolder(), "replays");
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        holdoutFile = new File(dir, "holdout.jsonl");
        holdout.set(count(holdoutFile));
        rows.set(count(new File(dir, "rows.txt")));
        running = true;
        worker = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "h2020-replay");
        worker.setDaemon(true);
        worker.start();
    }

    public static void shutdown() {
        running = false;
        if (worker != null) worker.interrupt();
    }

    /** One record. {@code json} must be a comma separated body, e.g. {@code "x":[..],"a":1,"y":0}. */
    public static void log(String kind, String json) {
        if (dir == null || json == null) return;
        String line = "{\"t\":" + System.currentTimeMillis() + ",\"k\":\"" + kind + "\"," + json + "}";
        if (!Q.offer(line)) { /* the disk is behind and the buffer is full: drop rather than stall the tick */ }
    }

    public static String vec(double[] x) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < x.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(DF.format(x[i]));
        }
        return sb.append(']').toString();
    }

    private static void loop() {
        BufferedWriter out = null;
        long hour = -1;
        try {
            while (running) {
                String line = Q.poll(2, java.util.concurrent.TimeUnit.SECONDS);
                if (line == null) {
                    if (out != null) { out.flush(); }
                    continue;
                }
                long now = System.currentTimeMillis();
                long h = now / 3_600_000L;
                if (out == null || h != hour) {
                    if (out != null) out.close();
                    hour = h;
                    out = new BufferedWriter(new FileWriter(new File(dir, "train-" + h + ".jsonl"), true));
                }
                out.write(line);
                out.write('\n');
                seq++;
                rows.incrementAndGet();
                boolean toHoldout = seq % HOLDOUT_EVERY == 0 && holdout.get() < HOLDOUT_CAP;
                if (toHoldout) {
                    appendHoldout(line);
                    holdout.incrementAndGet();
                }
                if (seq % 200 == 0) {
                    out.flush();
                    persistRows();
                }
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable t) {
            // a broken replay log must never take the server down
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) { }
        }
    }

    /** Keep the row counter across restarts (it is only a report number, but a wrong one is worse than none). */
    private static void persistRows() {
        try {
            java.nio.file.Files.write(new File(dir, "rows.txt").toPath(),
                    (rows.get() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) { }
    }

    private static void appendHoldout(String line) {
        try {
            BufferedWriter w = new BufferedWriter(new FileWriter(holdoutFile, true));
            try { w.write(line); w.write('\n'); } finally { w.close(); }
        } catch (Throwable ignored) { }
    }

    public static long rows() { return rows.get(); }
    public static long holdoutRows() { return holdout.get(); }

    public static File dir() { return dir; }
    public static File holdoutFile() { return holdoutFile; }

    public static int count(File f) {
        if (f == null || !f.isFile()) return 0;
        int n = 0;
        try {
            BufferedReader r = new BufferedReader(new FileReader(f));
            try { while (r.readLine() != null) n++; } finally { r.close(); }
        } catch (Throwable ignored) { }
        return n;
    }
}
