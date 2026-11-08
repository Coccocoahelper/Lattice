package net.h2020;

import net.h2020.api.H2020;
import net.h2020.api.Module;
import net.h2020.core.CoreModule;
import net.h2020.maps.MapsModule;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

public final class H2020Plugin extends JavaPlugin {
    private final List<Module> enabled = new ArrayList<>();

    @Override
    public void onEnable() {
        H2020.init(this);
        migrateLegacyData();
        saveDefaultConfig();
        List<Module> order = Arrays.asList(
                new CoreModule(), new MapsModule());
        for (Module m : order) {
            try {
                m.enable(this);
                enabled.add(m);
                getLogger().info("Enabled module " + m.name());
            } catch (Throwable t) {
                getLogger().log(Level.SEVERE, "Failed to enable module " + m.name(), t);
            }
        }
    }

    /**
     * The plugin used to be called Hypixel2020. Its data folder holds the maps, profiles, models, replays and stats,
     * so the first boot under the new name copies the old folder across rather than starting from nothing.
     */
    private void migrateLegacyData() {
        try {
            java.io.File mine = getDataFolder();
            java.io.File legacy = new java.io.File(getDataFolder().getParentFile(), "Hypixel2020");
            if (mine.exists() && mine.list() != null && mine.list().length > 0) return;
            if (!legacy.isDirectory()) return;
            copyTree(legacy.toPath(), mine.toPath());
            getLogger().info("Migrated data from plugins/Hypixel2020");
        } catch (Throwable t) {
            getLogger().warning("Legacy data copy failed: " + t);
        }
    }

    private static void copyTree(java.nio.file.Path from, java.nio.file.Path to) throws java.io.IOException {
        java.nio.file.Files.walkFileTree(from, new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            @Override public java.nio.file.FileVisitResult preVisitDirectory(java.nio.file.Path dir,
                    java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException {
                java.nio.file.Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file,
                    java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException {
                java.nio.file.Files.copy(file, to.resolve(from.relativize(file).toString()),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    public void onDisable() {
        List<Module> rev = new ArrayList<>(enabled);
        Collections.reverse(rev);
        for (Module m : rev) {
            try { m.disable(); } catch (Throwable t) { getLogger().log(Level.SEVERE, "Failed to disable " + m.name(), t); }
        }
        enabled.clear();
    }
}
