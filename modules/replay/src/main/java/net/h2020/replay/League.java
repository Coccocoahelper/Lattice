package net.h2020.replay;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Generations of the self-play policies.
 * <p>
 * A generation is a folder with a copy of every policy file that may learn from bot play: duel stances, placement,
 * the combat arbiter, aim, macro, roles and next-opponent. The champion is the generation that passed the gate; the
 * challenger is whatever the arena has been training since.
 * <p>
 * Nothing here touches the models that read people (behaviour, intent, shop, human placements, the human half of the
 * fight model). That split is the whole reason self-play cannot make the bots worse at understanding humans.
 */
public final class League {
    /** Policy files a generation owns. Human-calibrated models are deliberately absent. */
    public static final String[][] MODELS = {
            {"duel", "duel-policy.yml"},
            {"placement", "placement-policy.yml"},
            {"arbiter", "combat-arbiter.yml"},
            {"aim", "aim-policy.yml"},
            {"macro", "macro-policy-v3.yml"},
            {"role", "role-model.yml"},
            {"next", "next-opponent.yml"},
    };

    private static File root;      // plugins/Hypixel2020/training/league
    private static File data;      // plugins/Hypixel2020
    private static long generation; // champion generation number

    private League() {}

    public static void install(JavaPlugin p) {
        if (p == null) return;
        data = p.getDataFolder();
        root = new File(data, "training/league");
        if (!root.isDirectory()) root.mkdirs();
        generation = readGen();
    }

    public static long generation() { return generation; }
    public static File root() { return root; }

    public static File championDir() { return new File(root, "champion"); }
    public static File challengerDir() { return new File(root, "challenger"); }
    public static File archiveDir(long gen) { return new File(root, "archive/gen-" + gen); }

    public static boolean hasChampion() { return new File(championDir(), "duel-policy.yml").isFile(); }

    /** Copy the live policy files into a generation folder. */
    public static int snapshot(File to) {
        if (to == null) return 0;
        if (!to.isDirectory() && !to.mkdirs()) return 0;
        int n = 0;
        for (String[] m : MODELS) {
            File f = new File(data, m[1]);
            if (!f.isFile()) continue;
            try {
                java.nio.file.Files.copy(f.toPath(), new File(to, m[1]).toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                n++;
            } catch (Throwable ignored) { }
        }
        return n;
    }

    /** Write a generation back over the live policy files and reload the models. */
    public static int restore(File from) {
        if (from == null || !from.isDirectory()) return 0;
        int n = 0;
        for (String[] m : MODELS) {
            File src = new File(from, m[1]);
            if (!src.isFile()) continue;
            try {
                java.nio.file.Files.copy(src.toPath(), new File(data, m[1]).toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                n++;
            } catch (Throwable ignored) { }
        }
        reloadModels();
        return n;
    }

    /** The champion is replaced by the challenger (archiving the old champion first). */
    public static void promoteChallenger() {
        File gen = archiveDir(generation);
        snapshot(gen);
        restore(challengerDir());
        snapshot(championDir());
        generation++;
        writeGen(generation);
    }

    /** Throw the challenger away and carry on from the champion. */
    public static void discardChallenger() {
        restore(championDir());
    }

    /** First run: whatever the server has learned so far becomes generation 0. */
    public static void ensureChampion() {
        if (hasChampion()) return;
        snapshot(championDir());
        writeGen(generation);
    }

    /** Flush every trainable model to disk now (they also save on their own timers). */
    public static void saveAll() {
        try { net.h2020.bots.ai.ml.DuelPolicy.save(); } catch (Throwable ignored) { }
        try { net.h2020.bots.ai.ml.PlacementPolicy.save(); } catch (Throwable ignored) { }
        try { net.h2020.bots.ai.ml.CombatArbiter.save(); } catch (Throwable ignored) { }
        try { net.h2020.bots.ai.ml.AimPolicy.save(); } catch (Throwable ignored) { }
        try { net.h2020.bots.ai.ml.MacroPolicy.save(); } catch (Throwable ignored) { }
        try { net.h2020.bots.ai.ml.RoleModel.save(); } catch (Throwable ignored) { }
        try { net.h2020.bots.ai.ml.NextOpponent.save(); } catch (Throwable ignored) { }
        try { net.h2020.bots.ai.ml.FightModel.save(); } catch (Throwable ignored) { }
        try { net.h2020.bots.ai.ml.FightFlow.save(); } catch (Throwable ignored) { }
        try { net.h2020.bots.ai.ml.BehaviourModel.save(); } catch (Throwable ignored) { }
    }

    public static void reloadModels() {
        try { net.h2020.bots.ai.ml.DuelPolicy.reload(); } catch (Throwable ignored) { }
        try { net.h2020.bots.ai.ml.PlacementPolicy.reload(); } catch (Throwable ignored) { }
        try { net.h2020.bots.ai.ml.CombatArbiter.reload(); } catch (Throwable ignored) { }
        try { net.h2020.bots.ai.ml.AimPolicy.reload(); } catch (Throwable ignored) { }
        try { net.h2020.bots.ai.ml.MacroPolicy.reload(); } catch (Throwable ignored) { }
        try { net.h2020.bots.ai.ml.RoleModel.reload(); } catch (Throwable ignored) { }
        try { net.h2020.bots.ai.ml.NextOpponent.reload(); } catch (Throwable ignored) { }
    }

    public static List<String> report() {
        List<String> out = new ArrayList<String>();
        out.add("§6[train] §egeneration §f" + generation
                + "§7, champion " + (hasChampion() ? "§apresent" : "§cmissing")
                + "§7, challenger " + (new File(challengerDir(), "duel-policy.yml").isFile() ? "§epresent" : "§7none"));
        StringBuilder sb = new StringBuilder("§7policy files in a generation:");
        for (String[] m : MODELS) sb.append(' ').append(m[1]);
        out.add(sb.toString());
        return out;
    }

    private static long readGen() {
        try {
            File f = new File(root, "generation.txt");
            if (!f.isFile()) return 0;
            List<String> l = java.nio.file.Files.readAllLines(f.toPath());
            return l.isEmpty() ? 0 : Long.parseLong(l.get(0).trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void writeGen(long gen) {
        try {
            if (!root.isDirectory()) root.mkdirs();
            java.nio.file.Files.write(new File(root, "generation.txt").toPath(),
                    (gen + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) { }
    }
}
