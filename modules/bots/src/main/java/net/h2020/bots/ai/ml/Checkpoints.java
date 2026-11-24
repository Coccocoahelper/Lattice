package net.h2020.bots.ai.ml;

import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Named snapshots of every learning model, so a bad training run can be undone without losing the good one.
 *
 * <p>The models already save themselves atomically, but they save to one place: there is exactly one live state, and the
 * only way back from a run that trained the models into nonsense was to delete the yml files and start over from the
 * priors. That has already happened twice here - a fight model at weight -73 with log-loss 4.9, and a later run at
 * log-loss 12.6 - and both times the only record of what it looked like before was the conversation it was diagnosed in.
 *
 * <p>A checkpoint is a copy of the whole model directory, named by the operator, with the numbers that say whether it
 * was worth keeping: fights seen, placements recorded, the fight model's loss and brier. Restoring copies the files back
 * and reloads every model in memory; because the same model code reads the same file names, a checkpoint is versionless
 * by construction - a file whose shape no longer matches is refused by the model's own {@code load()}, which returns
 * without touching the weights rather than loading a shifted array.
 *
 * <p>Live data files are not checkpointed. {@code pvp-stats.yml} is a counter dump that only ever grows, and the
 * per-player fact book holds live beliefs about players currently in a game; restoring either would rewrite what the
 * bots know about the people they are fighting right now.
 */
public final class Checkpoints {

    /** Models that are checkpointed: everything with a saved state on disk. */
    private static final String[] MODEL_FILES = {
            "fight-model.yml", "placement-policy.yml", "aim-policy.yml", "fight-flow.yml", "duel-policy.yml",
            "next-opponent.yml", "macro-policy-v3.yml", "behaviour-model-v2.yml", "question-book.yml",
            "intent-model-v2.yml", "combat-arbiter.yml", "human-placements.yml", "shop-model.yml", "role-model.yml",
    };

    /** Never restored: live counters and per-player state for the people in the current game. */
    private static final String[] NOT_CHECKPOINTED = { "pvp-stats.yml", "ml-settings.yml" };

    private static File dataFolder;
    private static File root;

    private Checkpoints() {}

    public static void install(File botsDataFolder) {
        dataFolder = botsDataFolder;
        root = new File(botsDataFolder, "checkpoints");
        if (!root.isDirectory() && !root.mkdirs()) {
            Bukkit.getLogger().warning("[ml] checkpoints: could not create " + root.getAbsolutePath());
        }
    }

    /**
     * Save every model into a new named checkpoint.
     *
     * <p>Saves first, deliberately: a checkpoint should be the state the models are actually in, and the in-memory
     * weights are the truth right now - the files may be up to a save-interval stale.
     *
     * @return a one-line summary, or the reason it failed
     */
    public static String capture(String name) {
        String clean = sanitise(name);
        if (clean == null) return "§cname must be 1-48 chars of letters, digits, dash or underscore";
        if (dataFolder == null || root == null) return "§ccheckpoints are not installed";
        File dir = new File(root, clean);
        if (dir.exists()) return "§ca checkpoint called §f" + clean + "§c already exists (delete it first)";

        saveAll();
        if (!dir.mkdirs()) return "§ccould not create " + dir.getAbsolutePath();
        int copied = 0;
        List<String> missing = new ArrayList<String>();
        for (String f : MODEL_FILES) {
            File src = new File(dataFolder, f);
            if (!src.isFile()) { missing.add(f); continue; }
            if (copy(src, new File(dir, f))) copied++;
            else return "§cfailed while copying " + f;
        }
        writeSummary(new File(dir, "summary.txt"), clean, copied, missing);
        return String.format(Locale.ROOT, "§6[ml] §acheckpoint §f%s§a saved: §f%d§a model files%s",
                clean, copied, missing.isEmpty() ? "" : " §7(" + missing.size() + " not written yet)");
    }

    /** Put a checkpoint back: copy its files over the live ones and reload every model from disk. */
    public static String restore(String name) {
        String clean = sanitise(name);
        if (clean == null || root == null) return "§cno such checkpoint";
        File dir = new File(root, clean);
        if (!dir.isDirectory()) return "§cno checkpoint called §f" + clean;
        // Validate BEFORE overwriting anything. Copy-then-reload is not good enough: a model whose array shape no
        // longer matches refuses the file in load() and keeps its in-memory weights, so the running server looks fine
        // while the file on disk is now the incompatible one - and the next restart silently starts from priors.
        // Measured: a checkpoint planted with n=99 restored cleanly, the model kept working, and the live file was
        // overwritten with the bad shape.
        List<String> restored = new ArrayList<String>();
        List<String> refused = new ArrayList<String>();
        File staging = new File(dataFolder, ".restore-tmp");
        try {
            deleteTree(staging);
            if (!staging.mkdirs()) return "§ccould not stage the restore";
            for (String f : MODEL_FILES) {
                File src = new File(dir, f);
                if (!src.isFile()) continue;
                File staged = new File(staging, f);
                if (!copy(src, staged)) return "§cfailed while staging " + f;
                String why = incompatible(f, staged);
                if (why != null) { refused.add(f + " (" + why + ")"); continue; }
                restored.add(f);
            }
            if (restored.isEmpty()) {
                return "§cthat checkpoint is not usable by this build of the models"
                        + (refused.isEmpty() ? "" : ": §f" + String.join(", ", refused));
            }
            for (String f : restored) {
                if (!copy(new File(staging, f), new File(dataFolder, f))) return "§cfailed while restoring " + f;
            }
        } finally {
            deleteTree(staging);
        }
        reloadAll();
        if (!refused.isEmpty()) {
            return "§6[ml] §acheckpoint §f" + clean + "§a restored: §f" + restored.size()
                    + "§a model files§c; skipped as incompatible: §f" + String.join(", ", refused);
        }
        return String.format(Locale.ROOT, "§6[ml] §acheckpoint §f%s§a restored: §f%d§a model files, every model "
                + "reloaded from disk", clean, restored.size());
    }

    /** Every checkpoint, newest first, with the numbers that say whether it was worth keeping. */
    public static List<String> list() {
        List<String> out = new ArrayList<String>();
        File[] dirs = root == null ? null : root.listFiles();
        if (dirs == null || dirs.length == 0) {
            out.add("§6[ml] §7no checkpoints yet - §f/bot ml checkpoint save <name>§7 makes one");
            return out;
        }
        List<File> sorted = new ArrayList<File>();
        Collections.addAll(sorted, dirs);
        sorted.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        out.add("§6[ml] §e" + sorted.size() + " §7checkpoint" + (sorted.size() == 1 ? "" : "s") + ":");
        for (File d : sorted) {
            if (!d.isDirectory()) continue;
            int n = 0;
            for (String f : MODEL_FILES) if (new File(d, f).isFile()) n++;
            String quality = "";
            try {
                File fm = new File(d, "fight-model.yml");
                if (fm.isFile()) {
                    org.bukkit.configuration.file.YamlConfiguration y =
                            org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(fm);
                    long samples = y.getLong("samples", 0);
                    if (samples > 0) {
                        quality = String.format(Locale.ROOT, " §7fights §f%d§7 loss §f%.3f§7 brier §f%.3f",
                                samples, y.getDouble("loss", Double.NaN), y.getDouble("brier", Double.NaN));
                    }
                }
            } catch (Throwable ignored) { }
            out.add(String.format(Locale.ROOT, "§7 - §f%s §7(%d files, %s)%s", d.getName(), n, age(d), quality));
        }
        return out;
    }

    /** Remove a checkpoint. Never touches the live models. */
    public static String delete(String name) {
        String clean = sanitise(name);
        if (clean == null || root == null) return "§cno such checkpoint";
        File dir = new File(root, clean);
        if (!dir.isDirectory()) return "§cno checkpoint called §f" + clean;
        int n = 0;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) { if (f.delete()) n++; }
        if (!dir.delete()) return "§ccould not remove every file in " + clean;
        return "§6[ml] §acheckpoint §f" + clean + "§a deleted (§f" + n + "§a files)";
    }

    // ------------------------------------------------------------------ internals

    private static void saveAll() {
        try { FightModel.save(); } catch (Throwable ignored) { }
        try { PlacementPolicy.save(); } catch (Throwable ignored) { }
        try { AimPolicy.save(); } catch (Throwable ignored) { }
        try { FightFlow.save(); } catch (Throwable ignored) { }
        try { DuelPolicy.save(); } catch (Throwable ignored) { }
        try { NextOpponent.save(); } catch (Throwable ignored) { }
        try { MacroPolicy.save(); } catch (Throwable ignored) { }
        try { BehaviourModel.save(); } catch (Throwable ignored) { }
        try { QuestionBook.save(); } catch (Throwable ignored) { }
        try { IntentModel.save(); } catch (Throwable ignored) { }
        try { CombatArbiter.save(); } catch (Throwable ignored) { }
        try { HumanPlacements.save(); } catch (Throwable ignored) { }
        try { ShopModel.save(); } catch (Throwable ignored) { }
        try { RoleModel.save(); } catch (Throwable ignored) { }
    }

    /**
     * Reload every model from the files just restored.
     *
     * <p>{@code reload()} calls the model's own {@code load()}, which validates the array shape and feature version and
     * refuses a mismatch instead of loading a shifted vector - so restoring a checkpoint from an older feature set
     * leaves that model on its priors rather than making it predict nonsense.
     */
    private static void reloadAll() {
        try { FightModel.reload(); } catch (Throwable ignored) { }
        try { PlacementPolicy.reload(); } catch (Throwable ignored) { }
        try { AimPolicy.reload(); } catch (Throwable ignored) { }
        try { FightFlow.reload(); } catch (Throwable ignored) { }
        try { DuelPolicy.reload(); } catch (Throwable ignored) { }
        try { NextOpponent.reload(); } catch (Throwable ignored) { }
        try { MacroPolicy.reload(); } catch (Throwable ignored) { }
        try { BehaviourModel.reload(); } catch (Throwable ignored) { }
        try { QuestionBook.reload(); } catch (Throwable ignored) { }
        try { IntentModel.reload(); } catch (Throwable ignored) { }
        try { CombatArbiter.reload(); } catch (Throwable ignored) { }
        try { HumanPlacements.reload(); } catch (Throwable ignored) { }
        try { ShopModel.reload(); } catch (Throwable ignored) { }
        try { RoleModel.reload(); } catch (Throwable ignored) { }
    }

    /**
     * Why this checkpoint file cannot be loaded by the current models, or null when it is fine.
     *
     * <p>Each model guards its own shape - {@code n} for a feature-vector model, {@code featureVersion} or
     * {@code facts-version} where the basis changed - and a refusal there is a silent no-op at runtime. Checking the
     * same fields here turns that into a refusal to overwrite, which is the only place the decision is still cheap.
     */
    private static String incompatible(String file, File staged) {
        try {
            org.bukkit.configuration.file.YamlConfiguration y =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(staged);
            if (y.getKeys(false).isEmpty()) return "empty file";
            if (file.equals("fight-model.yml")) {
                if (y.getInt("n", 0) != 30) return "n=" + y.getInt("n", 0) + ", expected 30";
                if (y.getInt("featureVersion", 1) != 2) return "featureVersion " + y.getInt("featureVersion", 1) + ", expected 2";
            }
            if (file.equals("question-book.yml")) {
                int v = y.getInt("facts-version", 0);
                if (v != QuestionBook.FACTS_VERSION) return "facts-version " + v + ", expected " + QuestionBook.FACTS_VERSION;
            }
            if (file.equals("placement-policy.yml")) {
                if (y.getInt("n", 0) != PlacementPolicy.N) return "n=" + y.getInt("n", 0) + ", expected " + PlacementPolicy.N;
            }
            return null;
        } catch (Throwable t) {
            return "unreadable (" + t.getClass().getSimpleName() + ")";
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        if (!f.delete()) f.deleteOnExit();
    }

    private static boolean copy(File src, File dst) {
        try {
            Path parent = dst.getParentFile().toPath();
            if (!Files.isDirectory(parent)) Files.createDirectories(parent);
            Files.copy(src.toPath(), dst.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            Bukkit.getLogger().warning("[ml] checkpoints: " + src.getName() + " -> " + dst + " failed: " + e);
            return false;
        }
    }

    /** A short record of what the checkpoint was, next to the files, so it is still legible in a year. */
    private static void writeSummary(File to, String name, int copied, List<String> missing) {
        StringBuilder sb = new StringBuilder();
        sb.append("Lattice model checkpoint\n");
        sb.append("name:    ").append(name).append('\n');
        sb.append("saved:   ").append(new java.util.Date()).append('\n');
        sb.append("files:   ").append(copied).append(" of ").append(MODEL_FILES.length).append('\n');
        if (!missing.isEmpty()) sb.append("absent:  ").append(String.join(", ", missing)).append('\n');
        sb.append('\n');
        try {
            sb.append(String.format(Locale.ROOT, "fights seen:   %d%n", FightModel.samples()));
            sb.append(String.format(Locale.ROOT, "fight accuracy:%.3f%n", FightModel.accuracy()));
            sb.append(String.format(Locale.ROOT, "fight loss:    %.4f%n", FightModel.loss()));
            sb.append(String.format(Locale.ROOT, "fight brier:   %.4f%n", FightModel.brier()));
        } catch (Throwable ignored) { }
        String note = "not checkpointed: " + String.join(", ", NOT_CHECKPOINTED)
                + " (live counters and per-player state for the current game)\n";
        sb.append('\n').append(note);
        try {
            Files.write(to.toPath(), sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }

    private static String age(File d) {
        long ms = System.currentTimeMillis() - d.lastModified();
        long min = ms / 60000L;
        if (min < 1) return "just now";
        if (min < 60) return min + "m ago";
        long h = min / 60;
        if (h < 24) return h + "h ago";
        return (h / 24) + "d ago";
    }

    /** Letters, digits, dash, underscore; 1-48 chars. Anything else would be a path or a mess. */
    private static String sanitise(String name) {
        if (name == null) return null;
        String s = name.trim();
        if (s.isEmpty() || s.length() > 48) return null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_';
            if (!ok) return null;
        }
        if (s.equals(".") || s.equals("..")) return null;
        return s;
    }
}
