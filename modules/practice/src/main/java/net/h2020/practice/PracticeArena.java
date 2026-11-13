package net.h2020.practice;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A practice arena: an empty world with exactly the blocks the drill needs, built when the drill starts and deleted when
 * it ends.
 *
 * <p>Nothing is copied from a map. A drill needs a platform, a gap, a lane or a wall, and generating that is faster and
 * far less fragile than keeping a map.yml and a world folder in step with every change to a scenario. The world is void
 * so a mistake in the build shows up as a hole rather than as terrain that accidentally saves someone.
 */
public final class PracticeArena {

    /** One builder per world, so two drills in the same world cannot both claim the name. */
    private static final AtomicInteger SEQ = new AtomicInteger();

    private final World world;
    private final String name;

    private PracticeArena(World world, String name) {
        this.world = world;
        this.name = name;
    }

    /**
     * Create the empty world for a drill. A void generator, no structures, no weather: everything the player stands on
     * is placed by the drill itself.
     */
    public static PracticeArena create(String drillId) {
        String name = "practice_" + drillId + "_" + SEQ.incrementAndGet();
        File folder = new File(Bukkit.getWorldContainer(), name);
        deleteRecursively(folder);
        WorldCreator wc = new WorldCreator(name)
                .environment(World.Environment.NORMAL)
                .type(WorldType.NORMAL)
                .generateStructures(false)
                .generator(new VoidGenerator());
        World w = Bukkit.createWorld(wc);
        if (w == null) throw new IllegalStateException("could not create the practice world " + name);
        w.setAutoSave(false);
        w.setKeepSpawnInMemory(false);
        w.setSpawnLocation(0, 65, 0);
        w.setTime(6000);
        w.setStorm(false);
        w.setThundering(false);
        w.setGameRuleValue("doDaylightCycle", "false");
        w.setGameRuleValue("doWeatherCycle", "false");
        w.setGameRuleValue("doMobSpawning", "false");
        w.setGameRuleValue("naturalRegeneration", "true");
        w.setGameRuleValue("showDeathMessages", "false");
        w.setGameRuleValue("keepInventory", "true");
        return new PracticeArena(w, name);
    }

    public World world() { return world; }
    public String name() { return name; }

    // ---- building ----

    /** Filled cuboid, inclusive. */
    public void box(int x1, int y1, int z1, int x2, int y2, int z2, Material m) {
        int ax = Math.min(x1, x2), bx = Math.max(x1, x2);
        int ay = Math.min(y1, y2), by = Math.max(y1, y2);
        int az = Math.min(z1, z2), bz = Math.max(z1, z2);
        for (int x = ax; x <= bx; x++)
            for (int y = ay; y <= by; y++)
                for (int z = az; z <= bz; z++)
                    world.getBlockAt(x, y, z).setType(m, false);
    }

    /** Square platform centred on (cx,cz) with radius r (0 = one block). */
    public void platform(int cx, int cy, int cz, int r, Material top) {
        box(cx - r, cy, cz - r, cx + r, cy, cz + r, top);
        box(cx - r, cy - 1, cz - r, cx + r, cy - 1, cz + r, top);
    }

    /** A wall of height h along the four sides of a square, so nobody wanders out of the drill. */
    public void rim(int cx, int cy, int cz, int r, int h, Material m) {
        for (int d = -r; d <= r; d++) {
            for (int y = cy + 1; y <= cy + h; y++) {
                world.getBlockAt(cx + d, y, cz - r).setType(m, false);
                world.getBlockAt(cx + d, y, cz + r).setType(m, false);
                world.getBlockAt(cx - r, y, cz + d).setType(m, false);
                world.getBlockAt(cx + r, y, cz + d).setType(m, false);
            }
        }
    }

    /** A row of blocks, used for lane markings and for the far side of a gap. */
    public void line(int x1, int y1, int z1, int x2, int y2, int z2, Material m) {
        int steps = Math.max(Math.abs(x2 - x1), Math.max(Math.abs(y2 - y1), Math.abs(z2 - z1)));
        if (steps == 0) { world.getBlockAt(x1, y1, z1).setType(m, false); return; }
        for (int i = 0; i <= steps; i++) {
            int x = x1 + Math.round((x2 - x1) * (float) i / steps);
            int y = y1 + Math.round((y2 - y1) * (float) i / steps);
            int z = z1 + Math.round((z2 - z1) * (float) i / steps);
            world.getBlockAt(x, y, z).setType(m, false);
        }
    }

    /** Glowing block so a drill's markers read at a glance without adding light sources to the arena. */
    public void marker(int x, int y, int z, Material m) {
        Block b = world.getBlockAt(x, y, z);
        b.setType(m, false);
    }

    public Location at(double x, double y, double z) { return new Location(world, x, y, z); }

    public Location at(double x, double y, double z, float yaw) {
        return new Location(world, x, y, z, yaw, 0f);
    }

    /** Unload and delete. Called when the game ends, so a drill never leaves a world behind. */
    public void destroy() {
        try {
            Bukkit.unloadWorld(world, false);
        } catch (Throwable ignored) {
        }
        deleteRecursively(new File(Bukkit.getWorldContainer(), name));
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /**
     * An empty world. Every method that could place terrain does nothing, and the biome grid is set so the sky and the
     * fog read as a normal plains world rather than as an unset void.
     */
    private static final class VoidGenerator extends ChunkGenerator {
        @Override
        public byte[][] generateBlockSections(World world, Random random, int chunkX, int chunkZ, BiomeGrid biomes) {
            for (int x = 0; x < 16; x++)
                for (int z = 0; z < 16; z++)
                    biomes.setBiome(x, z, org.bukkit.block.Biome.PLAINS);
            return new byte[world.getMaxHeight() / 16][];
        }

        @Override
        public boolean canSpawn(World world, int x, int z) { return true; }

        @Override
        public List<BlockPopulator> getDefaultPopulators(World world) { return Collections.emptyList(); }

        @Override
        public Location getFixedSpawnLocation(World world, Random random) { return new Location(world, 0.5, 65, 0.5); }
    }
}
