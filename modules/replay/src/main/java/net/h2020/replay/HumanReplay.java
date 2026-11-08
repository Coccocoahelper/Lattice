package net.h2020.replay;

import net.h2020.bots.ai.ml.BehaviourModel;
import net.h2020.bots.ai.ml.FightModel;
import net.h2020.bots.ai.ml.HumanPlacements;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Training on one person's recorded game.
 * <p>
 * A replay of a human match is the best labelled data this server produces: the frames say exactly what he did and
 * what happened to him, with no guessing. This walks one player's rows out of a recording and turns them into the
 * three kinds of sample the human-calibrated models want:
 * <ul>
 * <li><b>fights</b> - his deaths and his kills, built from the frame either side of the moment (health, armour tier
 * from the recorded item ids, weapon value, gap);</li>
 * <li><b>placements</b> - every block that went down next to him, classified by where it went relative to him and the
 * nearest opponent, fed to the human placement model;</li>
 * <li><b>activity</b> - every tenth frame, labelled from what he was doing (bridging, fighting, travelling, idle) and
 * fed to the behaviour model as a labelled snapshot.</li>
 * </ul>
 * Only the named player's own perspective is learned, so a recording of one good player is one player's worth of data,
 * not eight.
 */
public final class HumanReplay {
    private HumanReplay() {}

    public static final class Result {
        public int fightSamples, placementSamples, activitySamples, frames;
        public String player = "";
        public String line() {
            return String.format(java.util.Locale.ROOT,
                    "§6[train] §e%s§7: §f%d§7 frames, §f%d§7 fight samples, §f%d§7 placements, §f%d§7 activity samples",
                    player, frames, fightSamples, placementSamples, activitySamples);
        }
    }

    public static Result train(String gameId, String playerName) {
        Result r = new Result();
        r.player = playerName;
        File f = new File(Recorder.dir(), gameId + ".jsonl");
        if (!f.isFile()) return r;

        List<long[]> times = new ArrayList<long[]>();
        List<Map<String, double[]>> frames = new ArrayList<Map<String, double[]>>();
        List<double[]> blockEvents = new ArrayList<double[]>();           // x,y,z,id,data,oldId
        List<Long> blockTimes = new ArrayList<Long>();
        BufferedReader rd = null;
        try {
            rd = new BufferedReader(new FileReader(f));
            String line;
            while ((line = rd.readLine()) != null) {
                if (line.startsWith("{\"h\":")) continue;
                if (line.contains("\"b\":[")) {
                    String body = between(line, "\"b\":[", "]");
                    if (body == null) continue;
                    String[] c = body.split(",");
                    if (c.length < 5) continue;
                    double[] ev = new double[6];
                    for (int k = 0; k < 5 && k < c.length; k++) ev[k] = parse(c[k], 0);
                    ev[5] = c.length > 5 ? parse(c[5], 0) : 0;
                    blockEvents.add(ev);
                    blockTimes.add((long) num(line, "t", 0));
                    continue;
                }
                if (!line.startsWith("{\"t\":") || !line.contains("\"p\":[")) continue;
                String body = line.substring(line.indexOf("\"p\":[") + 5);
                if (body.endsWith("]}")) body = body.substring(0, body.length() - 1);
                String[] rows = body.contains("],[") ? body.split(java.util.regex.Pattern.quote("],[")) : new String[]{body};
                Map<String, double[]> fr = new HashMap<String, double[]>();
                for (String row : rows) {
                    String[] c = row.replace("[", "").replace("]", "").split(",");
                    if (c.length < 10) continue;
                    String nm = c[0].replace("\"", "");
                    double[] v = new double[12];
                    v[0] = parse(c[2], 0); v[1] = parse(c[3], 0); v[2] = parse(c[4], 0);   // x,y,z
                    v[3] = parse(c[5], 0); v[4] = parse(c[6], 0);                          // yaw,pitch
                    v[5] = parse(c[7], 20);                                                // hp
                    v[6] = parse(c[8], 0);                                                 // sneak
                    v[7] = parse(c[9], 0);                                                 // held
                    v[8] = c.length > 10 ? parse(c[10], 0) : 0;                            // helmet
                    v[9] = c.length > 11 ? parse(c[11], 0) : 0;
                    v[10] = c.length > 12 ? parse(c[12], 0) : 0;
                    v[11] = c.length > 13 ? parse(c[13], 0) : 0;                           // boots
                    fr.put(nm, v);
                }
                frames.add(fr);
                times.add(new long[]{(long) num(line, "t", 0)});
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (rd != null) rd.close(); } catch (Throwable ignored) { }
        }
        r.frames = frames.size();
        if (frames.isEmpty()) return r;

        // ---- fights: an engagement is a sustained exchange with one opponent inside reach, not only a kill.
        // (A recording of a good player is full of fights that end with someone retreating; a kill-only detector sees
        // one fight in eight minutes, which is what happened the first time this was written.)
        Map<String, Object[]> active = new HashMap<String, Object[]>();     // enemy -> {startFeatures, myHp0, theirHp0, myDmg, theirDmg, lastSeenFrame}
        for (int i = 1; i < frames.size(); i++) {
            Map<String, double[]> prev = frames.get(i - 1), cur = frames.get(i);
            double[] mePrev = prev.get(playerName), meNow = cur.get(playerName);
            if (meNow == null || mePrev == null) continue;

            // close engagements that are no longer in reach
            List<String> done = new ArrayList<String>();
            for (Map.Entry<String, Object[]> e : active.entrySet()) {
                double[] them = cur.get(e.getKey());
                Object[] st = e.getValue();
                boolean gone = them == null || dist(meNow, them) > 8.0 || i - (Integer) st[5] > 120;
                if (gone) done.add(e.getKey());
            }
            for (String nm : done) closeEngagement(active.remove(nm), r);

            // a lull in the damage ends the engagement, so a long fight with a pause is two exchanges
            List<String> idle = new ArrayList<String>();
            for (Map.Entry<String, Object[]> e : active.entrySet()) {
                Object[] st = e.getValue();
                if ((Double) st[3] + (Double) st[4] == (Double) st[6] && i - (Integer) st[5] > 30) idle.add(e.getKey());
            }
            for (String nm : idle) closeEngagement(active.remove(nm), r);

            // update the ones still going, and open new ones
            for (Map.Entry<String, double[]> e : cur.entrySet()) {
                String nm = e.getKey();
                if (nm.equals(playerName)) continue;
                double[] them = e.getValue();
                if (them[5] <= 0) continue;
                double d = dist(meNow, them);
                if (d > 5.0) continue;
                Object[] st = active.get(nm);
                if (st == null) {
                    st = new Object[]{fight(meNow, them), meNow[5], them[5], 0.0, 0.0, i, 0.0};
                    active.put(nm, st);
                }
                st[6] = (Double) st[3] + (Double) st[4];
                double[] themPrev = prev.get(nm);
                if (themPrev != null && themPrev[5] > them[5]) st[4] = (Double) st[4] + (themPrev[5] - them[5]);  // damage we dealt
                st[5] = i;
            }
            // damage taken: compare with our own previous health
            if (meNow[5] < mePrev[5]) {
                for (Object[] st : active.values()) st[3] = (Double) st[3] + (mePrev[5] - meNow[5]);
            }
        }
        for (Object[] st : active.values()) closeEngagement(st, r);
        // ---- placements: blocks that went down next to him, classified like a human's own build
        for (int b = 0; b < blockEvents.size(); b++) {
            double[] ev = blockEvents.get(b);
            int idx = indexAt(times, blockTimes.get(b));
            if (idx < 0) continue;
            Map<String, double[]> fr = frames.get(idx);
            double[] me = fr.get(playerName);
            if (me == null) continue;
            double[] at = new double[]{ev[0], ev[1], ev[2]};
            if (dist(me, at) > 3.5) continue;                                  // not his block
            double[] enemy = nearestOther(fr, me, playerName, 8.0);
            if (enemy == null) continue;
            int cls = classify(me, enemy, at);
            HumanPlacements.learn(features(me, enemy), cls);
            r.placementSamples++;
        }

        // ---- activity: every tenth frame, labelled from what he was doing
        for (int i = 10; i < frames.size(); i += 10) {
            Map<String, double[]> fr = frames.get(i);
            double[] me = fr.get(playerName);
            double[] prev = frames.get(i - 10).get(playerName);
            if (me == null || prev == null) continue;
            double speed = dist(prev, me) / 0.5;                                // blocks per second
            boolean hurt = me[5] < prev[5];
            double[] enemy = nearestOther(fr, me, playerName, 8.0);
            int act;
            if (hurt || (enemy != null && dist(me, enemy) < 4.0)) act = BehaviourModel.FIGHTING;
            else if (me[7] > 0 && speed < 0.6 && me[1] < 100) act = BehaviourModel.PLACING;   // holding blocks, barely moving
            else if (speed > 2.2) act = BehaviourModel.TRAVELLING;
            else if (speed > 0.2) act = BehaviourModel.BRIDGING;
            else act = BehaviourModel.IDLE;
            BehaviourModel.learnSnapshot(activityFeatures(me, prev, speed, enemy), act);
            r.activitySamples++;
        }
        return r;
    }

    /** Turn one finished engagement into a labelled sample, if enough damage was actually traded. */
    private static void closeEngagement(Object[] st, Result r) {
        if (st == null) return;
        double dealt = (Double) st[3], taken = (Double) st[4];
        if (dealt + taken < 2.0) return;                       // a single poke is not a fight
        FightModel.learn((double[]) st[0], dealt >= taken, 0.35);   // damage-delta label: a guess, not an outcome     // he won the exchange if he out-damaged them
        r.fightSamples++;
    }

    /** Fight vector in the fight model's layout, built from recorded frames. */
    private static double[] fight(double[] me, double[] enemy) {
        double[] x = new double[FightModel.N];
        x[0] = 1;
        x[1] = Math.max(0, Math.min(1, me[5] / 20.0));
        x[2] = Math.max(0, Math.min(1, enemy[5] / 20.0));
        x[3] = Math.max(-1, Math.min(1, (me[5] - enemy[5]) / 20.0));
        double myArm = armor(me), theirArm = armor(enemy);
        x[4] = Math.max(-1, Math.min(1, ((myArm + weapon(me)) - (theirArm + weapon(enemy))) / 8.0));
        x[5] = Math.max(0, Math.min(1, (10 - dist(me, enemy)) / 10.0));
        x[15] = Math.max(0, Math.min(1, theirArm / 10.0));
        x[16] = Math.max(0, Math.min(1, myArm / 10.0));
        x[17] = Math.max(-1, Math.min(1, (weapon(me) - weapon(enemy)) / 8.0));
        return x;
    }

    /** Human placement model vector. */
    private static double[] features(double[] me, double[] enemy) {
        double[] x = new double[HumanPlacements.N];
        x[0] = 1;
        x[1] = Math.max(0, Math.min(1, me[5] / 20.0));
        x[2] = Math.max(0, Math.min(1, enemy[5] / 20.0));
        x[3] = Math.min(1, dist(me, enemy) / 8.0);
        x[4] = Math.max(-1, Math.min(1, (me[1] - enemy[1]) / 4.0));
        x[5] = 0.5;
        x[11] = Math.min(1, 0.3);
        return x;
    }

    /** Behaviour model vector, built from two consecutive frames. */
    private static double[] activityFeatures(double[] me, double[] prev, double speed, double[] enemy) {
        double[] x = new double[BehaviourModel.N];
        x[0] = 1;
        double dx = me[0] - prev[0], dz = me[2] - prev[2];
        double len = Math.max(1e-3, Math.sqrt(dx * dx + dz * dz));
        double yaw = Math.toRadians(me[3]);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double forward = len < 1e-3 ? 0 : (dx * fx + dz * fz) / len;
        x[1] = Math.max(0, -forward) * Math.min(1, speed / 4.0);        // walking backwards = bridging posture
        x[2] = Math.max(0, Math.min(1, (me[4] - 25) / 60.0));            // looking down
        x[3] = me[6] != 0 ? 1 : 0;                                        // sneaking
        x[8] = Math.min(1, speed / 5.0);
        x[9] = me[7] > 0 ? 1 : 0;                                         // block in hand
        x[22] = me[7] > 0 ? 1 : 0;
        return x;
    }

    private static int classify(double[] me, double[] enemy, double[] block) {
        int feet = (int) Math.floor(me[1]);
        int by = (int) Math.floor(block[1]);
        if (by <= feet - 2) return HumanPlacements.FLOOR;
        if (by == feet - 1) {
            double dx = block[0] - me[0], dz = block[2] - me[2];
            return (dx * dx + dz * dz) < 1.44 ? HumanPlacements.PILLAR : HumanPlacements.FLOOR;
        }
        if (by >= feet + 1) return HumanPlacements.HEAD;
        double ex = block[0] - enemy[0], ez = block[2] - enemy[2];
        return (ex * ex + ez * ez) < 6.25 ? HumanPlacements.STOMP : HumanPlacements.WALL;
    }

    private static double[] nearestOther(Map<String, double[]> frame, double[] me, String skip, double max) {
        double[] best = null;
        double bd = max;
        for (Map.Entry<String, double[]> e : frame.entrySet()) {
            if (e.getKey().equals(skip)) continue;
            double[] v = e.getValue();
            if (v[5] <= 0) continue;
            double d = dist(me, v);
            if (d < bd) { bd = d; best = v; }
        }
        return best;
    }

    private static double armor(double[] p) {
        double s = 0;
        for (int i = 8; i <= 11; i++) {
            int id = (int) p[i];
            if (id >= 310 && id <= 317) s += 4;
            else if (id >= 306 && id <= 309) s += 3;
            else if (id >= 302 && id <= 305) s += 2;
            else if (id >= 298 && id <= 317) s += 1;
        }
        return s;
    }

    private static double weapon(double[] p) {
        int id = (int) p[7] / 100;                     // packed id*100+data
        if (id == 276) return 5;
        if (id == 267) return 4;
        if (id == 272) return 3;
        if (id == 268) return 2;
        if (id == 258 || id == 279) return 3.5;
        return 1;
    }

    private static double dist(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static int indexAt(List<long[]> times, long t) {
        int lo = 0, hi = times.size() - 1, best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            if (times.get(mid)[0] <= t) { best = mid; lo = mid + 1; } else hi = mid - 1;
        }
        return best;
    }

    private static String between(String line, String open, String close) {
        int i = line.indexOf(open);
        if (i < 0) return null;
        int j = i + open.length();
        int end = line.indexOf(close, j);
        if (end <= j) return null;
        return line.substring(j, end);
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

    private static double parse(String v, double def) {
        try { return Double.parseDouble(v.trim()); } catch (Throwable t) { return def; }
    }
}
