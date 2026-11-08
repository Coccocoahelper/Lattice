package net.h2020.bots.ai.ml;

import net.h2020.api.game.BedwarsView;
import net.h2020.bots.ai.BotContext;
import net.h2020.bots.ai.inv.Inv;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * What a player actually buys, learned from people only. A human shop visit is observed: the inventory and the game
 * state at the moment the menu opens are the features, and what changes in the bag over the next few seconds is the
 * label (blocks, weapon, armour, tools, ranged, potion, utility). Nothing here is trained on our own bots - the shop
 * planner's hard-coded list is the prior, and this model is the correction on top of it.
 */
public final class ShopModel {
    public static final int BLOCKS = 0, WEAPON = 1, ARMOR = 2, TOOLS = 3, RANGED = 4, POTION = 5, UTILITY = 6, NOTHING = 7;
    public static final int ACTIONS = 8;
    public static final int N = 11;

    private static final double LR = 0.06;
    private static final double[] W = new double[ACTIONS * N];
    private static long samples;
    private static File file;
    private static boolean dirty;

    private ShopModel() {}

    public static void install(JavaPlugin p) {
        if (p == null) return;
        file = new File(p.getDataFolder(), "shop-model.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(p, new Runnable() {
            @Override public void run() { if (dirty) save(); }
        }, 2000L, 2000L);
    }

    public static String name(int a) {
        switch (a) {
            case BLOCKS: return "blocks";
            case WEAPON: return "weapon";
            case ARMOR: return "armour";
            case TOOLS: return "tools";
            case RANGED: return "ranged";
            case POTION: return "potion";
            case UTILITY: return "utility";
            default: return "nothing";
        }
    }

    /** The state a shop trip starts from - what the hard-coded planner does not get to see. */
    public static double[] features(Player p, boolean bedAlive, boolean finalLife, int gameSeconds, int roleIndex) {
        double[] x = new double[N];
        x[0] = 1;
        x[1] = Math.min(1, Inv.count(p, Material.IRON_INGOT) / 32.0);
        x[2] = Math.min(1, Inv.count(p, Material.GOLD_INGOT) / 16.0);
        x[3] = Math.min(1, Inv.count(p, Material.DIAMOND) / 8.0);
        x[4] = Math.min(1, Inv.count(p, Material.EMERALD) / 6.0);
        x[5] = Math.min(1, Inv.count(p, Inv.BLOCK) / 64.0);
        x[6] = swordTier(p) / 4.0;
        x[7] = armourTier(p) / 4.0;
        x[8] = bedAlive ? 1 : 0;
        x[9] = finalLife ? 1 : 0;
        x[10] = Math.min(1, gameSeconds / 600.0);
        return x;
    }

    private static int swordTier(Player p) {
        int t = 0;
        for (org.bukkit.inventory.ItemStack i : p.getInventory().getContents()) {
            if (i == null) continue;
            switch (i.getType()) {
                case DIAMOND_SWORD: t = Math.max(t, 4); break;
                case IRON_SWORD: t = Math.max(t, 3); break;
                case STONE_SWORD: t = Math.max(t, 2); break;
                case WOOD_SWORD: t = Math.max(t, 1); break;
                default: break;
            }
        }
        return t;
    }

    private static int armourTier(Player p) {
        org.bukkit.inventory.ItemStack b = p.getInventory().getBoots();
        if (b == null) return 0;
        switch (b.getType()) {
            case DIAMOND_BOOTS: return 4;
            case IRON_BOOTS: return 3;
            case CHAINMAIL_BOOTS: return 2;
            default: return 1;
        }
    }

    public static void learn(double[] x, int what) {
        if (x == null || what < 0 || what >= ACTIONS) return;
        double[] p = probabilities(x);
        double lr = LR / (1.0 + samples / 300.0);
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

    /** The category a player in this state most likely shops for (null until the model has seen a few trips). */
    public static Integer preferred(BotContext ctx, boolean bedAlive, boolean finalLife, int gameSeconds, int roleIndex) {
        if (samples < 5) return null;
        double[] pr = probabilities(features(ctx.player, bedAlive, finalLife, gameSeconds, roleIndex));
        int best = 0;
        for (int a = 1; a < ACTIONS; a++) if (pr[a] > pr[best]) best = a;
        if (best == NOTHING || pr[best] < 0.3) return null;
        return best;
    }

    private static double dot(int a, double[] x) {
        double z = 0;
        int base = a * N;
        for (int i = 0; i < N; i++) z += W[base + i] * x[i];
        return z;
    }

    public static long samples() { return samples; }

    public static List<String> report() {
        List<String> out = new ArrayList<String>();
        out.add(String.format("§6[ml] §eshop model: §f%d§7 human shop trips learned", samples));
        StringBuilder sb = new StringBuilder("§7buys per category:");
        String[] cues = {"bias", "iron", "gold", "diamonds", "emeralds", "blocks", "sword", "armour", "bedAlive", "finalLife", "time"};
        for (int a = 0; a < ACTIONS; a++) {
            int best = -1;
            double bv = 0;
            for (int i = 1; i < N; i++) if (Math.abs(W[a * N + i]) > Math.abs(bv)) { bv = W[a * N + i]; best = i; }
            if (best < 0) continue;
            sb.append(String.format(" %s:%s%+.2f", name(a), cues[Math.min(best, cues.length - 1)], bv));
        }
        out.add(sb.toString());
        return out;
    }

    public static void reset() {
        for (int i = 0; i < W.length; i++) W[i] = 0;
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
