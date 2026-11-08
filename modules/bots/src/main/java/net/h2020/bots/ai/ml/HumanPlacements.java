package net.h2020.bots.ai.ml;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * How people use blocks in fights, learned from people.
 * <p>
 * Every block a human places while an enemy is on him is one observation: the state at that moment (own health, the
 * enemy's health, the gap, the height difference, blocks in the bag, whether the enemy is closing, whether there is a
 * wall or an edge behind, whether our bed is alive) is the input, and what the block <i>is</i> - under the feet, a
 * pillar under himself, a wall in the enemy's face, a block over the head, an extension in front - is the label.
 * <p>
 * That gives the bots a prior that comes from people rather than from their own bandit: when the placement policy
 * ranks its options, the placement a human in that exact situation picks gets a bonus, and the bot's own learning only
 * has to adjust around it.
 */
public final class HumanPlacements {
    public static final int OTHER = 0, STOMP = 1, PILLAR = 2, WALL = 3, HEAD = 4, FLOOR = 5;
    public static final int ACTIONS = 6;
    public static final int N = 12;

    private static final double LR = 0.07;
    private static final double[] W = new double[ACTIONS * N];
    private static final double[] CLASS = new double[ACTIONS];
    private static long samples;
    private static File file;
    private static boolean dirty;

    private HumanPlacements() {}

    public static void install(JavaPlugin p) {
        if (p == null) return;
        file = new File(p.getDataFolder(), "human-placements.yml");
        load();
        org.bukkit.Bukkit.getScheduler().runTaskTimer(p, new Runnable() {
            @Override public void run() { if (dirty) save(); }
        }, 2400L, 2400L);
    }

    public static String name(int a) {
        switch (a) {
            case STOMP: return "stomp";
            case PILLAR: return "pillar";
            case WALL: return "wall";
            case HEAD: return "head";
            case FLOOR: return "floor";
            default: return "other";
        }
    }

    /** The state a placement is decided in, from the placer's point of view. */
    public static double[] state(Player me, Player enemy) {
        double[] x = new double[N];
        Location m = me.getLocation();
        Location e = enemy == null ? null : enemy.getLocation();
        x[0] = 1;
        x[1] = Math.max(0, me.getHealth()) / 20.0;
        x[2] = enemy == null ? 0.5 : Math.max(0, enemy.getHealth()) / 20.0;
        double d = e == null ? 8 : m.distance(e);
        x[3] = Math.min(1, d / 8.0);
        x[4] = e == null ? 0 : Math.max(-1, Math.min(1, (m.getY() - e.getY()) / 4.0));
        x[5] = Math.min(1, net.h2020.bots.ai.inv.Inv.count(me, net.h2020.bots.ai.inv.Inv.BLOCK) / 64.0);
        x[6] = e == null ? 0 : closingSpeed(enemy, me);
        x[7] = wallBehind(me, e) ? 1 : 0;
        x[8] = edgeBehind(me, e) ? 1 : 0;
        x[9] = me.getLocation().getWorld().getTime() == 0 ? 0 : 0.5;   // placeholder, overridden by caller if known
        x[10] = e == null ? 0 : (lookingAt(enemy, me) ? 1 : 0);
        x[11] = Math.min(1, horizontalSpeed(me) / 0.3);
        return x;
    }

    /** Was the enemy moving towards the placer (blocks per tick, clamped)? */
    private static double closingSpeed(Player enemy, Player me) {
        org.bukkit.util.Vector v = enemy.getVelocity();
        double dx = me.getLocation().getX() - enemy.getLocation().getX();
        double dz = me.getLocation().getZ() - enemy.getLocation().getZ();
        double len = Math.max(1e-3, Math.sqrt(dx * dx + dz * dz));
        double towards = (v.getX() * dx + v.getZ() * dz) / len;
        return Math.max(0, Math.min(1, towards / 0.3));
    }

    private static double horizontalSpeed(Player p) {
        org.bukkit.util.Vector v = p.getVelocity();
        return Math.sqrt(v.getX() * v.getX() + v.getZ() * v.getZ());
    }

    private static boolean lookingAt(Player p, Player target) {
        org.bukkit.util.Vector look = p.getLocation().getDirection();
        double dx = target.getLocation().getX() - p.getLocation().getX();
        double dz = target.getLocation().getZ() - p.getLocation().getZ();
        double len = Math.max(1e-3, Math.sqrt(dx * dx + dz * dz));
        return (look.getX() * dx + look.getZ() * dz) / len > 0.55;
    }

    private static boolean wallBehind(Player me, Location enemy) {
        if (enemy == null) return false;
        double dx = me.getLocation().getX() - enemy.getX();
        double dz = me.getLocation().getZ() - enemy.getZ();
        double len = Math.max(1e-3, Math.sqrt(dx * dx + dz * dz));
        for (int back = 1; back <= 2; back++) {
            int bx = (int) Math.floor(me.getLocation().getX() + dx / len * back);
            int bz = (int) Math.floor(me.getLocation().getZ() + dz / len * back);
            for (int dy = 0; dy <= 1; dy++) {
                if (me.getWorld().getBlockAt(bx, (int) Math.floor(me.getLocation().getY()) + dy, bz).getType().isSolid())
                    return true;
            }
        }
        return false;
    }

    private static boolean edgeBehind(Player me, Location enemy) {
        if (enemy == null) return false;
        double dx = me.getLocation().getX() - enemy.getX();
        double dz = me.getLocation().getZ() - enemy.getZ();
        double len = Math.max(1e-3, Math.sqrt(dx * dx + dz * dz));
        int bx = (int) Math.floor(me.getLocation().getX() + dx / len * 2);
        int bz = (int) Math.floor(me.getLocation().getZ() + dz / len * 2);
        return me.getWorld().getBlockAt(bx, (int) Math.floor(me.getLocation().getY()) - 1, bz).getType() == Material.AIR;
    }

    /** Which placement this block was, judged from where it went relative to the two players. */
    public static int classify(Player me, Player enemy, Location block) {
        int feet = (int) Math.floor(me.getLocation().getY());
        int by = block.getBlockY();
        if (enemy == null) return OTHER;
        if (by <= feet - 2) return FLOOR;                                   // down: an extension or a save
        if (by == feet - 1) {
            double dx = block.getX() + 0.5 - me.getLocation().getX();
            double dz = block.getZ() + 0.5 - me.getLocation().getZ();
            return (dx * dx + dz * dz) < 1.44 ? PILLAR : FLOOR;             // under himself vs in front/below
        }
        if (by >= feet + 1) return HEAD;
        // feet level: a wall, or a stomp if it went into the enemy's approach
        double ex = block.getX() + 0.5 - enemy.getLocation().getX();
        double ez = block.getZ() + 0.5 - enemy.getLocation().getZ();
        if (ex * ex + ez * ez < 6.25) return STOMP;
        return WALL;
    }

    /** A human placed a block during a fight. */
    public static void observe(Player me, Player enemy, Location block) {
        if (me == null || block == null) return;
        if (enemy == null) return;
        double[] x = state(me, enemy);
        int what = classify(me, enemy, block);
        net.h2020.api.train.Replay.log("human.place", "\"x\":" + net.h2020.api.train.Replay.vec(x)
                + ",\"c\":" + what);
        learn(x, what);
    }

    public static void learn(double[] x, int what) {
        if (x == null || what < 0 || what >= ACTIONS) return;
        double[] p = probabilities(x);
        double total = 1;
        for (double c : CLASS) total += c;
        CLASS[what]++;
        double w = total / (ACTIONS * Math.max(1.0, CLASS[what]));
        w = Math.max(0.3, Math.min(4.0, w));
        double lr = (LR / (1.0 + samples / 300.0)) * w;
        for (int a = 0; a < ACTIONS; a++) {
            double ind = a == what ? 1 : 0;
            double g = ind - p[a];
            for (int i = 0; i < N; i++) W[a * N + i] += lr * g * x[i];
        }
        samples++;
        dirty = true;
    }

    public static double[] probabilities(double[] x) {
        double[] z = new double[ACTIONS];
        double max = -1e9;
        for (int a = 0; a < ACTIONS; a++) { z[a] = dot(a, x); max = Math.max(max, z[a]); }
        double sum = 0;
        for (int a = 0; a < ACTIONS; a++) { z[a] = Math.exp(z[a] - max); sum += z[a]; }
        for (int a = 0; a < ACTIONS; a++) z[a] /= sum;
        return z;
    }

    /** Probability that a human in this state places this kind of block (0 while the model has no data). */
    public static double prob(int what, double[] x) {
        if (samples < 20 || x == null || what < 0 || what >= ACTIONS) return 0;
        return probabilities(x)[what];
    }

    public static long samples() { return samples; }

    private static double dot(int a, double[] x) {
        double z = 0;
        int base = a * N;
        for (int i = 0; i < N; i++) z += W[base + i] * x[i];
        return z;
    }

    public static List<String> report() {
        List<String> out = new ArrayList<String>();
        out.add(String.format("§6[ml] §eplacements of people: §f%d§7 blocks watched in fights", samples));
        StringBuilder sb = new StringBuilder("§7what they build:");
        for (int a = 0; a < ACTIONS; a++) sb.append(' ').append(name(a)).append('=').append((int) CLASS[a]);
        out.add(sb.toString());
        return out;
    }

    public static void reset() {
        for (int i = 0; i < W.length; i++) W[i] = 0;
        for (int i = 0; i < CLASS.length; i++) CLASS[i] = 0;
        samples = 0;
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
            List<Double> c = y.getDoubleList("class");
            for (int i = 0; i < Math.min(CLASS.length, c.size()); i++) CLASS[i] = c.get(i);
            samples = y.getLong("samples", 0);
        } catch (Throwable ignored) { }
    }

    public static void save() {
        if (file == null) return;
        try {
            org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
            List<Double> w = new ArrayList<Double>(W.length);
            for (double v : W) w.add(v);
            List<Double> c = new ArrayList<Double>(CLASS.length);
            for (double v : CLASS) c.add(v);
            y.set("weights", w);
            y.set("class", c);
            y.set("samples", samples);
            if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
            y.save(file);
            dirty = false;
        } catch (Throwable ignored) { }
    }
}
