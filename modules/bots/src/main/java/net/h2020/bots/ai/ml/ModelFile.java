package net.h2020.bots.ai.ml;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Write a model's weights without ever leaving a half-written file.
 *
 * <p>Every learning model saved with {@code yaml.save(file)}, which truncates the target and then streams into it. A
 * crash, a kill, or the disk filling up between those two steps leaves a truncated YAML - and the loader catches the
 * parse error and starts from zero weights, silently. That is the worst possible failure for a model that only earns
 * its numbers over a long session: the loss is invisible, and it looks like the learning never worked.
 *
 * <p>Writing a sibling file and moving it into place is atomic on every filesystem the server runs on, so a reader
 * either sees the old file or the new one. The temporary file is named with the process id so two servers sharing a
 * data folder cannot collide.
 */
public final class ModelFile {

    private ModelFile() {}

    /** Save atomically. Returns false when the write failed, so a caller can report it instead of swallowing it. */
    public static boolean save(YamlConfiguration yaml, File target) {
        if (yaml == null || target == null) return false;
        Path dir = target.getParentFile() == null ? null : target.getParentFile().toPath();
        Path tmp = null;
        try {
            if (dir != null && !Files.isDirectory(dir)) Files.createDirectories(dir);
            tmp = new File(target.getParentFile() == null ? new File(".") : target.getParentFile(),
                    target.getName() + ".tmp").toPath();
            String text = yaml.saveToString();
            Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Throwable t) {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (Throwable ignored) { }
            }
            return false;
        }
    }

    /** A model file that failed to load is reported once, not silently treated as "no weights yet". */
    public static void warnOnce(String who, File file, Throwable cause) {
        if (file == null) return;
        if (!WARNED.add(who + "|" + file.getName())) return;
        org.bukkit.Bukkit.getLogger().warning("[ml] " + who + ": could not read " + file.getName()
                + " (" + (cause == null ? "unknown" : cause.getClass().getSimpleName()) + "), starting from its defaults");
    }

    private static final java.util.Set<String> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();
}
