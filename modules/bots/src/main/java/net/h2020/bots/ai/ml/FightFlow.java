package net.h2020.bots.ai.ml;

import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.perception.Sighting;
import net.h2020.bots.ai.util.AIMath;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the fight is going: a small linear model of the opponent's movement over the next {@link #HORIZON} ticks,
 * trained online on the fights as they happen (features now -> their actual displacement ten ticks later).
 * <p>
 * Every player moves the same way physically (strafe, sprint, knockback), so this is learnable from the fights a bot
 * is already in: sprinting at you means they keep closing for a few ticks, a knockback sends them back and sideways,
 * a strafe reverses at their own rhythm. The prediction is used for
 * <ul>
 * <li>the cell a <b>stomp</b> goes into (the cell they are about to walk into, not the one they are in),</li>
 * <li>which side of a 2-block pillar to <b>strafe around</b> (the side they are moving away from),</li>
 * <li>and as a lead term when aiming at a point rather than an entity.</li>
 * </ul>
 * It never replaces the physics: a prediction only shifts a target cell or an aim point by at most a few blocks, and
 * the placements and hits are still checked by the client's own ray.
 */
public final class FightFlow {
    public static final int HORIZON = 10;
    private static final int M = 10;

    /** {dx, dz} weights: two linear heads over the same features. */
    private static final double[] WX = new double[M];
    private static final double[] WZ = new double[M];
    private static long samples;
    private static double error = 0.5;      // mean |predicted - actual| in blocks
    private static File file;
    private static boolean dirty;

    private static final class Pending {
        final double[] x;
        final double x0, z0;
        final long tick;
        Pending(double[] x, double x0, double z0, long tick) { this.x = x; this.x0 = x0; this.z0 = z0; this.tick = tick; }
    }
    private static Pending pending;

    private FightFlow() {}

    public static void install(JavaPlugin plugin) {
        if (plugin == null) return;
        file = new File(plugin.getDataFolder(), "fight-flow.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() { if (dirty) save(); }
        }, 1600L, 1600L);
    }

    /** Features of a fight at this instant. */
    public static double[] features(BotContext ctx, Player target, double d) {
        double[] x = new double[M];
        if (target == null) return x;
        Sighting s = ctx.perception.get(target);
        org.bukkit.util.Vector tv = s == null ? new org.bukkit.util.Vector() : s.lastVel;
        org.bukkit.util.Vector mv = ctx.c.velocity();
        Location me = ctx.pos();
        Location tl = ctx.posOf(target);
        double ux = tl.getX() - me.getX(), uz = tl.getZ() - me.getZ();
        double l = Math.max(1e-3, Math.sqrt(ux * ux + uz * uz));
        ux /= l; uz /= l;
        x[0] = 1;
        x[1] = clamp(tv.getX(), -0.4, 0.4) * 2.5;      // their velocity
        x[2] = clamp(tv.getZ(), -0.4, 0.4) * 2.5;
        x[3] = clamp(mv.getX(), -0.4, 0.4) * 2.5;      // mine (knockback moves both)
        x[4] = clamp(mv.getZ(), -0.4, 0.4) * 2.5;
        x[5] = Math.min(1, d / 8.0);
        x[6] = tv.getX() * ux + tv.getZ() * uz;        // closing speed (their velocity toward me)
        x[7] = target.isOnGround() ? 1 : 0;
        x[8] = clamp((ctx.health() - target.getHealth()) / 20.0, -1, 1);
        x[9] = ctx.persona.skill.overall;
        return x;
    }

    /**
     * Feed the model: once per ~{@link #HORIZON} ticks while engaged. Compares the last stored sample with where the
     * target actually ended up and trains on it, then stores the current state for the next round.
     */
    public static void observe(BotContext ctx, Player target, double d) {
        if (target == null) return;
        Location tl = ctx.posOf(target);
        if (pending != null && ctx.tick - pending.tick >= HORIZON) {
            double ax = tl.getX() - pending.x0, az = tl.getZ() - pending.z0;
            // a teleport or a respawn is not movement: throw the sample away
            if (Math.abs(ax) < 6 && Math.abs(az) < 6) {
                double px = dot(WX, pending.x), pz = dot(WZ, pending.x);
                double err = (Math.abs(px - ax) + Math.abs(pz - az)) * 0.5;
                error = 0.95 * error + 0.05 * err;
                double lr = 0.06 / (1.0 + samples / 500.0);
                for (int i = 0; i < M; i++) {
                    WX[i] += lr * (ax - px) * pending.x[i];
                    WZ[i] += lr * (az - pz) * pending.x[i];
                }
                samples++;
                dirty = true;
            }
            pending = null;
        }
        if (pending == null) pending = new Pending(features(ctx, target, d), tl.getX(), tl.getZ(), ctx.tick);
    }

    /** Predicted displacement of the target over the next `ticks` ticks (clamped to something a player could do). */
    public static double[] predict(BotContext ctx, Player target, double d, int ticks) {
        double[] x = features(ctx, target, d);
        double k = ticks / (double) HORIZON;
        double dx = dot(WX, x) * k, dz = dot(WZ, x) * k;
        dx = clamp(dx, -3.0, 3.0);
        dz = clamp(dz, -3.0, 3.0);
        return new double[]{dx, dz};
    }

    /** Where the target is heading, as a point (live position + prediction). */
    public static Location aheadOf(BotContext ctx, Player target, double d, int ticks) {
        Location tl = ctx.posOf(target);
        double[] delta = predict(ctx, target, d, ticks);
        return tl.clone().add(delta[0], 0, delta[1]);
    }

    /**
     * Which lateral side of the target is opening up: +1 when they are drifting to our right (so we go left around
     * them), -1 the other way. Used to pick the side of the 2-block pillar to strafe around.
     */
    public static int openingSide(BotContext ctx, Player target, double d) {
        Location me = ctx.pos();
        Location tl = ctx.posOf(target);
        double ux = tl.getX() - me.getX(), uz = tl.getZ() - me.getZ();
        double l = Math.max(1e-3, Math.sqrt(ux * ux + uz * uz));
        ux /= l; uz /= l;
        double[] delta = predict(ctx, target, d, 8);
        double lateral = delta[0] * -uz + delta[1] * ux;
        if (Math.abs(lateral) < 0.05) return 0;
        return lateral > 0 ? 1 : -1;
    }

    public static long samples() { return samples; }

    public static double error() { return error; }

    public static List<String> report() {
        List<String> out = new ArrayList<String>();
        out.add(String.format("§6[ml] §efight flow: §f%d§7 samples, mean error §f%.2f§7 blocks over %d ticks",
                samples, error, HORIZON));
        out.add(String.format("§7their velocity -> movement weights: vx=%.2f vz=%.2f, closing=%.2f, myVel=%.2f/%.2f",
                WX[1], WZ[2], WX[6], WX[3], WZ[4]));
        return out;
    }

    public static void reset() {
        for (int i = 0; i < M; i++) { WX[i] = 0; WZ[i] = 0; }
        samples = 0;
        error = 0.5;
        pending = null;
        dirty = true;
        save();
    }

    private static double dot(double[] w, double[] x) {
        double z = 0;
        for (int i = 0; i < M; i++) z += w[i] * x[i];
        return z;
    }

    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }

    private static void load() {
        if (file == null || !file.exists()) return;
        try {
            org.bukkit.configuration.file.YamlConfiguration y =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            List<Double> wx = y.getDoubleList("wx"), wz = y.getDoubleList("wz");
            for (int i = 0; i < Math.min(M, wx.size()); i++) WX[i] = wx.get(i);
            for (int i = 0; i < Math.min(M, wz.size()); i++) WZ[i] = wz.get(i);
            samples = y.getLong("samples", 0);
            error = y.getDouble("error", 0.5);
        } catch (Throwable ignored) { }
    }

    public static void save() {
        if (file == null) return;
        try {
            org.bukkit.configuration.file.YamlConfiguration y = new org.bukkit.configuration.file.YamlConfiguration();
            List<Double> wx = new ArrayList<Double>(M), wz = new ArrayList<Double>(M);
            for (int i = 0; i < M; i++) { wx.add(WX[i]); wz.add(WZ[i]); }
            y.set("wx", wx);
            y.set("wz", wz);
            y.set("samples", samples);
            y.set("error", error);
            if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
            y.save(file);
            dirty = false;
        } catch (Throwable ignored) { }
    }
}
