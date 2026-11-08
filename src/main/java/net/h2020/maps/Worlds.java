package net.h2020.maps;

import net.minecraft.server.v1_8_R3.FileIOThread;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Biome;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldInitEvent;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/** World plumbing: void world creation, Hypixel-style game rules, saving, copying and deleting world folders. */
public final class Worlds implements Listener {
    public static final String INSTANCE_DIR = "instances";
    public static final String GEN_PREFIX = "h2020gen_";
    private static final Set<String> SKIP_COPY = new HashSet<String>(Arrays.asList("uid.dat", "session.lock"));
    private static final Set<String> SKIP_DIRS = new HashSet<String>(Arrays.asList("playerdata", "stats", "players"));

    /** Never keep 625 spawn chunks in memory for our worlds: WorldInitEvent fires before spawn preparation. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInit(WorldInitEvent e) {
        if (isOurs(e.getWorld().getName())) e.getWorld().setKeepSpawnInMemory(false);
    }

    public static boolean isOurs(String name) {
        String n = name.replace('\\', '/').toLowerCase();
        return n.startsWith(INSTANCE_DIR + "/") || n.startsWith(GEN_PREFIX) || n.startsWith("h2020_");
    }

    /** Create or load a world with the void generator (folder relative to the world container). */
    public static World loadVoid(String name, Biome biome, double sx, double sy, double sz) {
        WorldCreator wc = new WorldCreator(name)
                .environment(World.Environment.NORMAL)
                .type(WorldType.FLAT)
                .generateStructures(false)
                .generator(new VoidGenerator(biome, sx, sy, sz));
        return Bukkit.createWorld(wc);
    }

    /** Game rules shared by every world this module loads. */
    public static void applyRules(World w, boolean pvp) {
        w.setGameRuleValue("doDaylightCycle", "false");
        w.setGameRuleValue("doMobSpawning", "false");
        w.setGameRuleValue("doFireTick", "false");
        w.setGameRuleValue("keepInventory", "false");
        w.setGameRuleValue("naturalRegeneration", "true");
        w.setGameRuleValue("randomTickSpeed", "0");   // vines/grass/ice stay exactly as built
        w.setGameRuleValue("doMobLoot", "false");
        w.setAutoSave(false);
        w.setKeepSpawnInMemory(false);
        w.setTime(6000L);
        w.setDifficulty(Difficulty.NORMAL);
        w.setStorm(false);
        w.setThundering(false);
        w.setWeatherDuration(Integer.MAX_VALUE);
        w.setSpawnFlags(false, false);
        w.setPVP(pvp);
    }

    /** Bytes a folder tree occupies, used to report what deleting an old world folder actually freed. */
    public static long sizeOf(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long total = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) total += sizeOf(k);
        return total;
    }

    /** Save every chunk + level.dat and block until the async chunk writer has flushed to disk. */
    public static void saveFully(World w, Logger log) {
        w.save();
        try {
            FileIOThread.a().b();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            log.log(Level.WARNING, "[maps] waiting for chunk IO failed", t);
        }
    }

    public static File container() { return Bukkit.getWorldContainer(); }

    /** Copy a world folder, skipping uid.dat / session.lock (so Bukkit accepts the copy as a new world). */
    public static void copyWorld(File src, File dst) throws IOException {
        final Path from = src.toPath(), to = dst.toPath();
        Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(from) && SKIP_DIRS.contains(dir.getFileName().toString().toLowerCase()))
                    return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (SKIP_COPY.contains(file.getFileName().toString().toLowerCase())) return FileVisitResult.CONTINUE;
                Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Recursive delete; returns true if the folder is gone afterwards. */
    public static boolean delete(File f) {
        if (f == null || !f.exists()) return true;
        try {
            Files.walkFileTree(f.toPath(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // retried by callers / next startup cleanup
        }
        return !f.exists();
    }
}
