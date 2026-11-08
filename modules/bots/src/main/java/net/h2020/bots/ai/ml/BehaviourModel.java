package net.h2020.bots.ai.ml;

import net.h2020.bots.ai.perception.PlayerStats;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a player is doing, learned from what can be observed: a softmax over the six things a Bed Wars player is
 * usually up to (bridging, placing blocks, gathering diamonds/emeralds, fighting, travelling, idling) from features
 * nobody can hide - moving backwards while looking down, crouching, air under the feet, blocks in hand, recent
 * placements, recent pickups, distance to a generator, taking or dealing damage, speed.
 * <p>
 * Trained from events that give the label away for free: a placed block over the void is bridging, a placed block on
 * solid ground is placing, picking a diamond or an emerald up is gathering, dealing or taking damage is fighting. Once
 * trained, the model recognises the same activities from the partial evidence (the sneak + backwards walk + looking
 * down of somebody bridging, before he places anything).
 * <p>
 * When it cannot tell, a bot may simply ask the player a yes/no question in chat ("you bridging rn? y/n") and use the
 * answer as a label - the one thing a bot cannot see but a teammate can just say.
 */
public final class BehaviourModel {
    public static final int BRIDGING = 0, PLACING = 1, GATHERING = 2, FIGHTING = 3, TRAVELLING = 4, SHOPPING = 5, IDLE = 6;
    public static final int ACTIONS = 7;
    public static final int N = 28;

    private static final double LR = 0.05;
    private static final double[] W = new double[ACTIONS * N];
    private static long samples;
    private static File file;
    private static boolean dirty;

    /** Last few seconds of samples per player: an answer is about the moment the player read the question, not now. */
    private static final Map<UUID, java.util.ArrayDeque<Object[]>> HISTORY = new HashMap<UUID, java.util.ArrayDeque<Object[]>>();
    /** Latest sampled features per player and the last classified activity. */
    private static final Map<UUID, double[]> LAST_X = new HashMap<UUID, double[]>();
    private static final Map<UUID, double[]> LAST_P = new HashMap<UUID, double[]>();
    private static final Map<UUID, Long> LAST_SAMPLE = new HashMap<UUID, Long>();
    /** Which island a player is on, and when he last changed island. */
    private static final Map<UUID, Integer> ISLAND = new HashMap<UUID, Integer>();
    private static final Map<UUID, Long> ISLAND_CHANGED = new HashMap<UUID, Long>();
    /** Open questions: player -> {activity asked about, runner-up activity, deadline ms}. */
    private static final Map<UUID, Object[]> ASKED = new HashMap<UUID, Object[]>();
    private static final Map<UUID, Long> LAST_ASK = new HashMap<UUID, Long>();

    private BehaviourModel() {}

    public static void install(JavaPlugin plugin) {
        if (plugin == null) return;
        file = new File(plugin.getDataFolder(), "behaviour-model-v2.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() { if (dirty) save(); }
        }, 1800L, 1800L);
    }

    // ------------------------------------------------------------------ features

    /** Sample the observable state of a player. Called every few ticks for the players near our bots. */
    public static double[] sample(Player p, long tick) {
        double[] x = new double[N];
        org.bukkit.util.Vector v = p.getVelocity();
        double yawRad = Math.toRadians(p.getLocation().getYaw());
        double fx = -Math.sin(yawRad), fz = Math.cos(yawRad);          // look direction (horizontal)
        double speed = Math.sqrt(v.getX() * v.getX() + v.getZ() * v.getZ());
        double forward = speed < 1e-3 ? 0 : (v.getX() * fx + v.getZ() * fz) / speed;
        x[0] = 1;
        x[1] = Math.max(0, -forward) * Math.min(1, speed / 0.2);        // walking backwards = bridging posture
        x[2] = clamp((p.getLocation().getPitch() - 25.0) / 60.0, 0, 1);  // looking down
        x[3] = p.isSneaking() ? 1 : 0;
        x[4] = overVoid(p) ? 1 : 0;
        x[5] = p.getItemInHand() != null && p.getItemInHand().getType().isBlock() ? 1 : 0;
        x[6] = clamp(PlayerStats.recentPlacements(p) / 5.0, 0, 1);
        x[7] = PlayerStats.recentPickup(p) ? 1 : 0;
        x[8] = clamp(nearestGenerator(p) / 32.0, 0, 1);
        x[9] = clamp(PlayerStats.recentHits(p) / 5.0, 0, 1);
        x[10] = clamp(speed / 0.35, 0, 1);
        x[11] = p.isOnGround() ? 0 : 1;
        // more variables: what is in the bag, and which island he is on / came from
        x[12] = clamp(net.h2020.bots.ai.inv.Inv.count(p, net.h2020.bots.ai.inv.Inv.BLOCK) / 64.0, 0, 1);
        x[13] = net.h2020.bots.ai.inv.Inv.has(p, net.h2020.bots.ai.inv.Inv.SWORD) ? 1 : 0;
        x[14] = (net.h2020.bots.ai.inv.Inv.has(p, net.h2020.bots.ai.inv.Inv.PEARL)
                || net.h2020.bots.ai.inv.Inv.has(p, net.h2020.bots.ai.inv.Inv.GAPPLE)) ? 1 : 0;
        x[15] = (net.h2020.bots.ai.inv.Inv.has(p, net.h2020.bots.ai.inv.Inv.PICKAXE)
                || net.h2020.bots.ai.inv.Inv.has(p, net.h2020.bots.ai.inv.Inv.SHEARS)) ? 1 : 0;
        int island = net.h2020.api.game.GeneratorRegistry.islandIndex(p.getWorld(), p.getLocation());
        Long changed = ISLAND_CHANGED.get(p.getUniqueId());
        Integer last = ISLAND.get(p.getUniqueId());
        if (last == null || last != island) { ISLAND.put(p.getUniqueId(), island); ISLAND_CHANGED.put(p.getUniqueId(), tick); }
        x[16] = clamp((tick - (ISLAND_CHANGED.getOrDefault(p.getUniqueId(), tick))) / 1200.0, 0, 1);
        x[17] = clamp(island / 8.0, 0, 1);
        // currencies in the bag: what somebody is saving for is what he is about to do
        x[18] = clamp(net.h2020.bots.ai.inv.Inv.count(p, org.bukkit.Material.DIAMOND) / 8.0, 0, 1);
        x[19] = clamp(net.h2020.bots.ai.inv.Inv.count(p, org.bukkit.Material.EMERALD) / 6.0, 0, 1);
        x[20] = clamp(net.h2020.bots.ai.inv.Inv.count(p, org.bukkit.Material.GOLD_INGOT) / 16.0, 0, 1);
        x[21] = clamp(net.h2020.bots.ai.inv.Inv.count(p, org.bukkit.Material.IRON_INGOT) / 32.0, 0, 1);
        // more classical signals: what is in the hand, what is in the bag, and where the player is standing
        x[22] = net.h2020.bots.ai.inv.Inv.holding(p, net.h2020.bots.ai.inv.Inv.BLOCK) ? 1 : 0;
        x[23] = net.h2020.bots.ai.inv.Inv.has(p, net.h2020.bots.ai.inv.Inv.BOW) ? 1 : 0;
        x[24] = net.h2020.bots.ai.inv.Inv.has(p, net.h2020.bots.ai.inv.Inv.PEARL) ? 1 : 0;
        x[25] = p.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY) ? 1 : 0;
        Double gen = net.h2020.api.game.GeneratorRegistry.nearest(p.getWorld(),
                net.h2020.api.game.GeneratorRegistry.TEAM_GEN, p.getLocation());
        x[26] = gen != null && gen < 12 ? 1 : 0;
        Double em = net.h2020.api.game.GeneratorRegistry.nearest(p.getWorld(),
                net.h2020.api.game.GeneratorRegistry.EMERALD_GEN, p.getLocation());
        x[27] = em != null && em < 24 ? 1 : 0;
        LAST_X.put(p.getUniqueId(), x);
        LAST_SAMPLE.put(p.getUniqueId(), tick);
        java.util.ArrayDeque<Object[]> h = HISTORY.get(p.getUniqueId());
        if (h == null) { h = new java.util.ArrayDeque<Object[]>(); HISTORY.put(p.getUniqueId(), h); }
        h.addLast(new Object[]{System.currentTimeMillis(), x});
        while (h.size() > 24) h.removeFirst();
        LAST_P.put(p.getUniqueId(), probabilities(x));
        return x;
    }

    /**
     * The features as they were about `msAgo` milliseconds ago. A question is read, thought about and then answered,
     * so the label belongs to the state a second or two back, not to whatever the player is doing while clicking.
     */
    public static double[] featuresBefore(Player p, long msAgo) {
        if (p == null) return null;
        java.util.ArrayDeque<Object[]> h = HISTORY.get(p.getUniqueId());
        if (h == null || h.isEmpty()) return featuresOf(p);
        long target = System.currentTimeMillis() - msAgo;
        double[] best = null;
        long bestGap = Long.MAX_VALUE;
        for (Object[] e : h) {
            long at = (Long) e[0];
            long gap = Math.abs(at - target);
            if (gap < bestGap) { bestGap = gap; best = (double[]) e[1]; }
        }
        return best != null ? best : featuresOf(p);
    }

    /** A classical check decided the activity: record it as the read without training on it. */
    public static void noteClass(Player p, int activity) {
        if (p != null && net.h2020.bots.ai.BotAI.isBotPlayer(p) && !MlSettings.trainFromBots()) return;
        pendingSource = "noteClass";
        if (p == null) return;
        double[] x = LAST_X.get(p.getUniqueId());
        if (x == null) return;
        double[] pr = new double[ACTIONS];
        java.util.Arrays.fill(pr, 0.02);
        pr[activity] = 0.9;
        LAST_P.put(p.getUniqueId(), pr);
    }

    /** The last sampled feature vector of a player (sampled again if stale). */
    public static double[] featuresOf(Player p) {
        if (p == null) return null;
        double[] x = LAST_X.get(p.getUniqueId());
        Long at = LAST_SAMPLE.get(p.getUniqueId());
        if (x == null || at == null || System.currentTimeMillis() - at > 400) x = sample(p, System.currentTimeMillis());
        return x;
    }

    private static boolean overVoid(Player p) {
        Location l = p.getLocation();
        for (int dy = 1; dy <= 24; dy++) {
            Material m = l.getWorld().getBlockAt(l.getBlockX(), l.getBlockY() - dy, l.getBlockZ()).getType();
            if (m != Material.AIR) return false;
        }
        return true;
    }

    /** Rough distance to the nearest diamond/emerald-like generator: mid or an island top. Kept cheap on purpose. */
    private static double nearestGenerator(Player p) {
        // (the game knows its generators; this is the fallback when nothing was registered for the world)
        Double d = net.h2020.api.game.GeneratorRegistry.nearest(p.getWorld(), p.getLocation());
        return d == null ? 32 : d;
    }

    // ------------------------------------------------------------------ learning

    /** An event that gives the activity away. */
    public static void observeEvent(Player p, int activity, long tick) {
        if (p == null) return;
        if (net.h2020.bots.ai.BotAI.isBotPlayer(p) && !MlSettings.trainFromBots()) return;
        pendingSource = "event";
        double[] x = LAST_X.get(p.getUniqueId());
        if (x == null || tick - LAST_SAMPLE.getOrDefault(p.getUniqueId(), -100L) > 40) x = sample(p, tick);
        learn(x, activity);
    }

    /** A soft negative update against one class (used when a player denies a confident guess). */
    private static void reinforce(double[] x, int activity, double weight) {
        if (x == null || activity < 0 || activity >= ACTIONS) return;
        double[] p = probabilities(x);
        double lr = LR / (1.0 + samples / 600.0);
        for (int i = 0; i < N; i++) W[activity * N + i] += lr * weight * p[activity] * x[i];
    }

    /** Where samples came from (debug: names the leaking source when bot data sneaks in). */
    private static final Map<String, Long> SOURCES = new HashMap<String, Long>();

    private static void src(String k) { SOURCES.put(k, SOURCES.getOrDefault(k, 0L) + 1); }

    public static String sourcesLine() {
        StringBuilder sb = new StringBuilder("§7sample sources:");
        for (Map.Entry<String, Long> e : SOURCES.entrySet()) sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
        return sb.toString();
    }

    /** Samples per activity class, used to weight rare classes up so "idle" and "defending" cannot swamp the rest. */
    private static final double[] CLASS = new double[ACTIONS];

    private static String pendingSource = "?";

    private static void learn(double[] x, int activity) {
        if (x == null || activity < 0 || activity >= ACTIONS) return;
        src(pendingSource);
        double[] p = probabilities(x);
        double total = 0;
        for (double c : CLASS) total += c;
        total += 1;
        CLASS[activity]++;
        // balanced sampling: a class with a tenth of the samples counts ten times as much (clamped, so a single rare
        // example cannot rewrite the model either)
        double w = (total / (ACTIONS * Math.max(1.0, CLASS[activity])));
        w = Math.max(0.3, Math.min(4.0, w));
        double lr = (LR / (1.0 + samples / 600.0)) * w;
        for (int a = 0; a < ACTIONS; a++) {
            double ind = a == activity ? 1 : 0;
            double g = ind - p[a];
            for (int i = 0; i < N; i++) W[a * N + i] += lr * g * x[i];
        }
        samples++;
        dirty = true;
    }

    /** Learn from an explicit snapshot (a bot labelling itself, or a delayed player state). */
    public static void learnSnapshot(double[] x, int activity) {
        if (x == null) return;
        pendingSource = "snapshot";
        learn(x, activity);
    }

    /** Where the samples came from, for /bot ml. */
    public static String provenance() { return sourcesLine(); }

    /** A multiple-choice answer from the server questions (an activity label). */
    public static void learnFromAnswer(Player p, int activity) {
        if (net.h2020.bots.ai.BotAI.isBotPlayer(p) && !MlSettings.trainFromBots()) return;
        pendingSource = "answer";
        double[] x = featuresBefore(p, 1000);
        if (x == null) x = sample(p, System.currentTimeMillis());
        learn(x, activity);
        LAST_P.put(p.getUniqueId(), probabilities(x));
    }

    /** A yes/no answer to a question the model asked: yes = that activity, no = the runner-up. */
    public static boolean answer(Player p, boolean yes) {
        if (p != null && net.h2020.bots.ai.BotAI.isBotPlayer(p) && !MlSettings.trainFromBots()) return false;
        Object[] q = ASKED.remove(p.getUniqueId());
        if (q == null) return false;
        int asked = (Integer) q[0], other = (Integer) q[1];
        double[] x = LAST_X.get(p.getUniqueId());
        if (x == null) return false;
        learn(x, yes ? asked : other);
        if (!yes) reinforce(x, asked, -0.45);     // the guess was wrong: push the asked class down as well
        return true;
    }

    // ------------------------------------------------------------------ prediction

    public static double[] probabilities(double[] x) {
        double[] z = new double[ACTIONS];
        double max = -1e9;
        for (int a = 0; a < ACTIONS; a++) { z[a] = dot(a, x); max = Math.max(max, z[a]); }
        double sum = 0;
        for (int a = 0; a < ACTIONS; a++) { z[a] = Math.exp(z[a] - max); sum += z[a]; }
        for (int a = 0; a < ACTIONS; a++) z[a] /= sum;
        return z;
    }

    public static int activity(Player p) {
        double[] p2 = LAST_P.get(p.getUniqueId());
        if (p2 == null) return IDLE;
        int best = 0;
        for (int i = 1; i < ACTIONS; i++) if (p2[i] > p2[best]) best = i;
        return best;
    }

    public static double confidence(Player p) {
        double[] p2 = LAST_P.get(p.getUniqueId());
        if (p2 == null) return 0;
        int best = 0;
        for (int i = 1; i < ACTIONS; i++) if (p2[i] > p2[best]) best = i;
        return p2[best];
    }

    public static String name(int activity) {
        switch (activity) {
            case BRIDGING: return "bridging";
            case PLACING: return "placing";
            case GATHERING: return "gathering";
            case FIGHTING: return "fighting";
            case TRAVELLING: return "travelling";
            case SHOPPING: return "shopping";
            default: return "idle";
        }
    }

    // ------------------------------------------------------------------ questions

    /**
     * A question worth asking this player, or null. Only when the model is genuinely unsure between two activities and
     * the player has not been asked recently.
     */
    public static String questionFor(Player p, long tick) {
        double[] p2 = LAST_P.get(p.getUniqueId());
        if (p2 == null) return null;
        // one open question at a time (a second one would have nothing to attach the answer to), but no rate limit
        // and no confidence gate: a confident guess confirmed by the player is worth as much as an unsure one resolved
        Object[] open = ASKED.get(p.getUniqueId());
        if (open != null && (Long) open[2] > System.currentTimeMillis()) return null;
        int best = 0, second = -1;
        for (int i = 0; i < ACTIONS; i++) {
            if (p2[i] > p2[best]) { second = best; best = i; }
            else if (second < 0 || p2[i] > p2[second]) second = i;
        }
        if (second < 0) second = (best + 1) % ACTIONS;
        ASKED.put(p.getUniqueId(), new Object[]{best, second, System.currentTimeMillis() + 6000});
        LAST_ASK.put(p.getUniqueId(), System.currentTimeMillis());
        String[] qs = {"u " + name(best) + " rn? y/n", "are u " + name(best) + "? (y/n)", name(best) + "? y/n",
                name(best) + "?? y/n", "quick: " + name(best) + "? y/n"};
        return qs[(int) (Math.random() * qs.length)];
    }

    // ------------------------------------------------------------------ io

    /** How many samples each activity class has (for /bot ml). */
    public static double[] classCounts() { return CLASS.clone(); }

    public static List<String> report() {
        List<String> out = new ArrayList<String>();
        StringBuilder cls = new StringBuilder("§7activity samples per class:");
        boolean any = false;
        for (int a = 0; a < ACTIONS; a++) if (CLASS[a] > 0) { cls.append(' ').append(name(a)).append('=').append((int) CLASS[a]); any = true; }
        if (any) out.add(cls.toString());
        out.add(String.format("§6[ml] §ebehaviour: §f%d§7 activity samples from players, §f%d§7 questions open",
                samples, ASKED.size()));
        StringBuilder sb = new StringBuilder("§7strongest cues:");
        String[] names = {"bias", "walkBack", "lookDown", "sneak", "overVoid", "blockInHand", "recentPlace",
                "recentPickup", "genDistance", "recentHits", "speed", "airborne",
                "blocksInBag", "hasSword", "hasPearlOrGapple", "hasTool", "islandAge", "islandIndex",
                "diamonds", "emeralds", "gold", "iron", "blocksInHand", "hasBow", "hasPearl", "invisible",
                "nearBase", "atMid"};
        for (int a = 0; a < ACTIONS; a++) {
            int best = -1;
            double bv = 0;
            for (int i = 1; i < N; i++) if (Math.abs(W[a * N + i]) > Math.abs(bv)) { bv = W[a * N + i]; best = i; }
            if (best < 0) continue;
            sb.append(String.format(" %s:%s%+.2f", name(a), names[best], bv));
        }
        out.add(sb.toString());
        return out;
    }

    public static void reset() {
        for (int i = 0; i < W.length; i++) W[i] = 0;
        samples = 0;
        LAST_X.clear(); LAST_P.clear(); LAST_SAMPLE.clear(); ASKED.clear(); LAST_ASK.clear();
        dirty = true;
        save();
    }

    private static double dot(int a, double[] x) {
        double z = 0;
        int base = a * N;
        for (int i = 0; i < N; i++) z += W[base + i] * x[i];
        return z;
    }

    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }

    private static void load() {
        if (file == null || !file.exists()) return;
        try {
            org.bukkit.configuration.file.YamlConfiguration y =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            List<Double> w = y.getDoubleList("weights");
            for (int i = 0; i < Math.min(W.length, w.size()); i++) W[i] = w.get(i);
            samples = y.getLong("samples", 0);
        } catch (Throwable ignored) { }
    }

    public static void save() {
        if (file == null) return;
        try {
            org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
            List<Double> w = new ArrayList<Double>(W.length);
            for (double v : W) w.add(v);
            y.set("weights", w);
            y.set("samples", samples);
            if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
            y.save(file);
            dirty = false;
        } catch (Throwable ignored) { }
    }
}
