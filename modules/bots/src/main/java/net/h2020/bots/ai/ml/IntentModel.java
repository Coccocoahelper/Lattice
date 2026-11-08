package net.h2020.bots.ai.ml;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * What a player wants to do next, learned from the server's multiple-choice question and predicted from behaviour for
 * everybody who was never asked: rush a base, defend the bed, diamonds, emeralds at mid, shop/upgrades, or nothing.
 * <p>
 * Features are the observable state the behaviour model already samples (posture, speed, blocks in hand, recent
 * placements/pickups, damage, distance to a generator), so an enemy who just picked emeralds up and is walking home
 * can be read as "shopping" without ever answering anything.
 */
public final class IntentModel {
    public static final int RUSH = 0, DEFEND = 1, IRON = 2, DIAMONDS = 3, EMERALDS = 4, SHOP = 5, UPGRADE = 6,
            HEAL = 7, DEPOSIT = 8, HUNT = 9, IDLE = 10;
    public static final int ACTIONS = 11;
    public static final int N = BehaviourModel.N;

    private static final double LR = 0.06;
    private static final double[] W = new double[ACTIONS * N];
    private static long answers;
    /** Per player: the team they said they are rushing (null when unknown). */
    private static final java.util.Map<java.util.UUID, String> RUSH_TEAM = new java.util.HashMap<java.util.UUID, String>();
    /** Per player: a team the checks think they are heading at / looking at (never overwrites what they said). */
    private static final java.util.Map<java.util.UUID, String> OBS_RUSH = new java.util.HashMap<java.util.UUID, String>();
    private static final java.util.Map<java.util.UUID, String> OBS_BRIDGE = new java.util.HashMap<java.util.UUID, String>();

    /** Per player: the team whose base they said they are bridging to (null when unknown). */
    private static final java.util.Map<java.util.UUID, String> BRIDGE_TEAM = new java.util.HashMap<java.util.UUID, String>();
    private static File file;
    private static boolean dirty;

    private IntentModel() {}

    public static void install(JavaPlugin p) {
        if (p == null) return;
        file = new File(p.getDataFolder(), "intent-model-v2.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(p, new Runnable() {
            @Override public void run() { if (dirty) save(); }
        }, 1900L, 1900L);
    }

    public static String name(int a) {
        switch (a) {
            case RUSH: return "rushing";
            case DEFEND: return "defending";
            case IRON: return "getting iron";
            case DIAMONDS: return "diamonds";
            case EMERALDS: return "emeralds";
            case SHOP: return "shopping";
            case UPGRADE: return "upgrading";
            case HEAL: return "healing up";
            case DEPOSIT: return "depositing";
            case HUNT: return "hunting";
            default: return "idling";
        }
    }

    /** The player picked "rush a base" and then named the team. */
    public static void setRushTeam(Player p, String team) {
        if (p == null) return;
        if (team == null) RUSH_TEAM.remove(p.getUniqueId());
        else RUSH_TEAM.put(p.getUniqueId(), team.toUpperCase());
    }

    /** The player picked "bridge→their base" and then named the team. */
    public static void setBridgeTeam(Player p, String team) {
        if (p == null) return;
        if (team == null) BRIDGE_TEAM.remove(p.getUniqueId());
        else BRIDGE_TEAM.put(p.getUniqueId(), team.toUpperCase());
    }

    /** The team whose base that player said they are bridging to (null when never said). */
    public static String bridgeTeam(Player p) {
        return p == null ? null : BRIDGE_TEAM.get(p.getUniqueId());
    }

    /** A team the checks read them as heading at / looking at. */
    public static void setObservedRush(Player p, String team) { if (p != null) OBS_RUSH.put(p.getUniqueId(), team); }

    public static String observedRush(Player p) { return p == null ? null : OBS_RUSH.get(p.getUniqueId()); }

    public static void setObservedBridge(Player p, String team) { if (p != null) OBS_BRIDGE.put(p.getUniqueId(), team); }

    public static String observedBridge(Player p) { return p == null ? null : OBS_BRIDGE.get(p.getUniqueId()); }

    /** The team that player said they are rushing (null when never said). */
    public static String rushTeam(Player p) {
        return p == null ? null : RUSH_TEAM.get(p.getUniqueId());
    }

    public static void learnFromAnswer(Player p, int intent) {
        if (net.h2020.bots.ai.BotAI.isBotPlayer(p) && !MlSettings.trainFromBots()) return;
        double[] x = BehaviourModel.featuresBefore(p, 1000);
        if (x == null || intent < 0 || intent >= ACTIONS) return;
        double[] probs = probabilities(x);
        double lr = LR / (1.0 + answers / 400.0);
        for (int a = 0; a < ACTIONS; a++) {
            double ind = a == intent ? 1 : 0;
            double g = ind - probs[a];
            for (int i = 0; i < N; i++) W[a * N + i] += lr * g * x[i];
        }
        answers++;
        dirty = true;
    }

    /** Learn from a snapshot taken earlier (e.g. the state 1.5 s before a shop was opened). */
    private static final double[] CLASS = new double[ACTIONS];

    public static void learnSnapshot(double[] x, int intent) {
        if (x == null || intent < 0 || intent >= ACTIONS) return;
        double[] probs = probabilities(x);
        double total = 1;
        for (double c : CLASS) total += c;
        CLASS[intent]++;
        double w = total / (ACTIONS * Math.max(1.0, CLASS[intent]));
        w = Math.max(0.3, Math.min(4.0, w));
        double lr = (LR / (1.0 + answers / 400.0)) * w;
        for (int a = 0; a < ACTIONS; a++) {
            double ind = a == intent ? 1 : 0;
            double g = ind - probs[a];
            for (int i = 0; i < N; i++) W[a * N + i] += lr * g * x[i];
        }
        answers++;
        dirty = true;
    }

    public static double[] classCounts() { return CLASS.clone(); }

    public static double[] probabilities(double[] x) {
        double[] z = new double[ACTIONS];
        double max = -1e9;
        for (int a = 0; a < ACTIONS; a++) { z[a] = dot(a, x); max = Math.max(max, z[a]); }
        double sum = 0;
        for (int a = 0; a < ACTIONS; a++) { z[a] = Math.exp(z[a] - max); sum += z[a]; }
        for (int a = 0; a < ACTIONS; a++) z[a] /= sum;
        return z;
    }

    /** The most likely next thing this player will do. */
    public static int predict(Player p) {
        double[] x = BehaviourModel.featuresOf(p);
        if (x == null) return IDLE;
        double[] pr = probabilities(x);
        int best = 0;
        for (int a = 1; a < ACTIONS; a++) if (pr[a] > pr[best]) best = a;
        return best;
    }

    public static double confidence(Player p) {
        double[] x = BehaviourModel.featuresOf(p);
        if (x == null) return 0;
        double[] pr = probabilities(x);
        double best = 0;
        for (double v : pr) best = Math.max(best, v);
        return best;
    }

    private static double dot(int a, double[] x) {
        double z = 0;
        int base = a * N;
        for (int i = 0; i < N; i++) z += W[base + i] * x[i];
        return z;
    }

    public static List<String> report() {
        List<String> out = new ArrayList<String>();
        StringBuilder cls = new StringBuilder("§7intent samples per class:");
        boolean anyCls = false;
        for (int a = 0; a < ACTIONS; a++) if (CLASS[a] > 0) { cls.append(' ').append(name(a)).append('=').append((int) CLASS[a]); anyCls = true; }
        if (anyCls) out.add(cls.toString());
        out.add(String.format("§6[ml] §eintent: §f%d§7 answers; strongest cue per intent", answers));
        StringBuilder sb = new StringBuilder("§7");
        String[] cues = {"walkBack", "lookDown", "sneak", "overVoid", "blockInHand", "recentPlace", "recentPickup",
                "genDistance", "recentHits", "speed", "airborne", "blocks", "sword", "pearl", "tool", "islandAge", "island"};
        for (int a = 0; a < ACTIONS; a++) {
            int best = -1;
            double bv = 0;
            for (int i = 1; i < N; i++) if (Math.abs(W[a * N + i]) > Math.abs(bv)) { bv = W[a * N + i]; best = i; }
            if (best < 0) continue;
            sb.append(String.format(" %s:%s%+.2f", name(a), cues[Math.min(best, cues.length - 1)], bv));
        }
        out.add(sb.toString());
        StringBuilder tg = new StringBuilder("§7said they are rushing:");
        boolean any = false;
        for (java.util.Map.Entry<java.util.UUID, String> e : RUSH_TEAM.entrySet()) {
            org.bukkit.entity.Player p = org.bukkit.Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            tg.append(' ').append(p.getName()).append("->").append(e.getValue());
            any = true;
        }
        if (any) out.add(tg.toString());
        StringBuilder bt = new StringBuilder("§7said they are bridging to:");
        boolean anyB = false;
        for (java.util.Map.Entry<java.util.UUID, String> e : BRIDGE_TEAM.entrySet()) {
            org.bukkit.entity.Player p = org.bukkit.Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            bt.append(' ').append(p.getName()).append("->").append(e.getValue());
            anyB = true;
        }
        if (anyB) out.add(bt.toString());
        StringBuilder ob = new StringBuilder("§7checks read them as heading at:");
        boolean anyO = false;
        for (java.util.Map.Entry<java.util.UUID, String> e : OBS_RUSH.entrySet()) {
            org.bukkit.entity.Player p = org.bukkit.Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            ob.append(' ').append(p.getName()).append("->").append(e.getValue());
            anyO = true;
        }
        if (anyO) out.add(ob.toString());
        return out;
    }

    public static void reset() {
        for (int i = 0; i < W.length; i++) W[i] = 0;
        answers = 0;
        dirty = true;
        save();
    }

    private static void load() {
        if (file == null || !file.exists()) return;
        try {
            org.bukkit.configuration.file.YamlConfiguration y =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            List<Double> w = y.getDoubleList("weights");
            for (int i = 0; i < Math.min(W.length, w.size()); i++) W[i] = w.get(i);
            answers = y.getLong("answers", 0);
        } catch (Throwable ignored) { }
    }

    public static void save() {
        if (file == null) return;
        try {
            org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
            List<Double> w = new ArrayList<Double>(W.length);
            for (double v : W) w.add(v);
            y.set("weights", w);
            y.set("answers", answers);
            if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
            y.save(file);
            dirty = false;
        } catch (Throwable ignored) { }
    }
}
