package net.h2020.duels;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A duel arena, built in code rather than copied from a map.
 *
 * <p>Hypixel's duels maps are small, flat and hand-made: a square of blocks with a wall around it, two spawn points
 * facing each other, and for Sumo a platform in the void with nothing around it. That is cheap to generate and it
 * means a duel never waits for a map copy - which matters most for the mode that is played in thirty-second bursts.
 *
 * <p>The floor is built one block deep on purpose: a duel is fought on top of it, and a one-deep floor means a hole
 * punched in it (a lava bucket, an explosion) drops the player into the void where the fall is decided, instead of
 * into a second layer that hides the mistake.
 */
public final class DuelsArena {

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** Half-width of the fight area in blocks. */
    public static final int RADIUS = 12;
    /** Floor height: everyone stands at y = FLOOR_Y. */
    public static final int FLOOR_Y = 65;

    private final World world;
    private final String name;
    private final DuelsMode mode;
    /** A world can only be unloaded once: the second attempt leaves the ticker with no chunk provider and crashes. */
    private boolean destroyed;
    private final Location spawnA, spawnB, spectator;

    private DuelsArena(World world, String name, DuelsMode mode) {
        this.world = world;
        this.name = name;
        this.mode = mode;
        this.spawnA = new Location(world, 0.5, FLOOR_Y, RADIUS - 1.5, 180f, 0f);   // feet at FLOOR_Y, floor at FLOOR_Y-1
        this.spawnB = new Location(world, 0.5, FLOOR_Y, -(RADIUS - 1.5), 0f, 0f);
        this.spectator = new Location(world, 0.5, FLOOR_Y + 14, 0.5, 0f, 35f);
    }

    public World world() { return world; }
    public String name() { return name; }
    public Location spawnA() { return spawnA.clone(); }
    public Location spawnB() { return spawnB.clone(); }
    public Location spectator() { return spectator.clone(); }

    /**
     * Live arenas by mode, free for reuse. A duels arena is static - a floor, a wall, two pads - and a duel only ever
     * changes the blocks on its surface, so the world is recycled instead of unloaded and rebuilt.
     *
     * <p>This is not an optimisation. Unloading a world from inside a game tick crashes WindSpigot: the per-world
     * ticker thread is inside WorldServer.doTick when Bukkit.unloadWorld nulls the chunk provider, and the next call
     * to chunkProvider.unloadChunks() throws a NullPointerException that kills the server (measured twice, with the
     * trace in the log). Recycling the world removes the unload from the hot path entirely.
     */
    private static final java.util.Map<String, java.util.ArrayDeque<DuelsArena>> POOL =
            new java.util.concurrent.ConcurrentHashMap<String, java.util.ArrayDeque<DuelsArena>>();

    /** An arena for this mode: a free one from the pool, or a freshly built one. */
    public static synchronized DuelsArena acquire(DuelsMode mode) {
        java.util.ArrayDeque<DuelsArena> free = POOL.get(mode.id());
        if (free != null) {
            DuelsArena a = free.poll();
            if (a != null && Bukkit.getWorld(a.name) != null) { a.reset(); return a; }
        }
        DuelsArena a = build(mode);
        a.poolKey = mode.id();
        return a;
    }

    /** Give an arena back: clear what the fight left behind and put it in the pool. */
    public static void release(DuelsArena a) {
        if (a == null || a.destroyed) return;
        a.reset();
        POOL.computeIfAbsent(a.poolKey, k -> new java.util.ArrayDeque<DuelsArena>()).push(a);
    }

    private String poolKey = "duels_classic";

    /** Put the arena back the way it was built: rebuild only the cells the fight could have changed. */
    private void reset() {
        destroyed = false;
        for (Player p : new ArrayList<Player>(world.getPlayers())) p.teleport(world.getSpawnLocation().add(0, 30, 0));
        build();
    }

    /** Build the arena for a mode: a sumo platform, or a walled floor. */
    private static DuelsArena build(DuelsMode mode) {
        String name = "duel_" + mode.id().replace("duels_", "") + "_" + SEQ.incrementAndGet();
        File folder = new File(Bukkit.getWorldContainer(), name);
        deleteRecursively(folder);
        WorldCreator wc = new WorldCreator(name)
                .environment(World.Environment.NORMAL)
                .type(WorldType.NORMAL)
                .generateStructures(false)
                .generator(new VoidGenerator());
        World w = Bukkit.createWorld(wc);
        if (w == null) throw new IllegalStateException("could not create the duel world " + name);
        w.setAutoSave(false);
        w.setKeepSpawnInMemory(false);
        w.setSpawnLocation(0, FLOOR_Y + 1, 0);
        w.setTime(6000L);
        w.setStorm(false);
        w.setThundering(false);
        w.setDifficulty(org.bukkit.Difficulty.NORMAL);
        w.setGameRuleValue("doDaylightCycle", "false");
        w.setGameRuleValue("doWeatherCycle", "false");
        w.setGameRuleValue("doMobSpawning", "false");
        w.setGameRuleValue("doFireTick", "false");
        w.setGameRuleValue("naturalRegeneration", mode.uhc ? "false" : "false");
        w.setGameRuleValue("keepInventory", "false");
        w.setGameRuleValue("showDeathMessages", "false");
        w.setGameRuleValue("fallDamage", mode.sumo ? "false" : "true");
        // the arena is built once, at creation: a duel never edits the map it is fought on
        DuelsArena a = new DuelsArena(w, name, mode);
        a.build();
        return a;
    }

    private void build() {
        if (mode.sumo) { buildSumo(); return; }
        // stone-brick floor, one deep, with a rim of walls so the fight stays in the arena. Two lanes of a different
        // block mark the spawn lines, which is what makes the distance readable at a glance.
        // two layers, not one: the standing surface is FLOOR_Y - 1 and the layer under it is the failure margin, so a
        // single block of disagreement between the spawn point and the build can never be a hole (measured: one-deep
        // floor, both bots spawned into air and fell to y=35 on the first test).
        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                boolean edge = Math.abs(x) == RADIUS || Math.abs(z) == RADIUS;
                set(x, FLOOR_Y - 1, z, edge ? Material.STONE : Material.SMOOTH_BRICK);
                set(x, FLOOR_Y - 2, z, Material.STONE);
            }
        }
        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                boolean edge = Math.abs(x) == RADIUS || Math.abs(z) == RADIUS;
                if (!edge) continue;
                for (int y = FLOOR_Y; y < FLOOR_Y + 4; y++) set(x, y, z, Material.SMOOTH_BRICK);
            }
        }
        // spawn pads: quartz on the two ends, a stripe of glowstone so the direction is obvious
        for (int x = -2; x <= 2; x++) {
            set(x, FLOOR_Y - 1, RADIUS - 2, Material.QUARTZ_BLOCK);
            set(x, FLOOR_Y - 1, -(RADIUS - 2), Material.QUARTZ_BLOCK);
        }
        for (int z = -3; z <= 3; z++) {
            set(0, FLOOR_Y - 1, z, Material.STONE);
        }
        // a roof of nothing at all: duels are played under open sky
    }

    /** A 12x12 platform in the void with no walls: the only way to lose is to be knocked off. */
    private void buildSumo() {
        int r = 6;
        for (int x = -r; x <= r; x++)
            for (int z = -r; z <= r; z++) {
                boolean lip = Math.abs(x) == r || Math.abs(z) == r;
                set(x, FLOOR_Y - 1, z, lip ? Material.QUARTZ_BLOCK : Material.SMOOTH_BRICK);
                set(x, FLOOR_Y - 2, z, Material.SMOOTH_BRICK);   // one block of margin under the platform
            }
        // a second layer under the middle so the platform reads as solid from the side
        for (int x = -r + 1; x <= r - 1; x++)
            for (int z = -r + 1; z <= r - 1; z++) set(x, FLOOR_Y - 2, z, Material.SMOOTH_BRICK);
    }

    private void set(int x, int y, int z, Material m) {
        Block b = world.getBlockAt(x, y, z);
        if (b.getType() != m) b.setType(m, false);
    }

    /**
     * Unload and delete the world. A duel is one arena for one match; nothing is reused.
     *
     * <p>Unloading is once-only and checked, because a second unload of the same world is not a harmless no-op: the
     * server's world ticker still holds the world for the rest of the tick, and with the chunk provider gone the next
     * tick throws a NullPointerException inside WorldServer.doTick and takes the whole server down (measured: the
     * first duel that ended this way killed the process).
     */
    public void destroy() {
        // recycle, never unload: see POOL above for why an unload here takes the server down
        if (destroyed) return;
        destroyed = true;
        release(this);
    }

    public boolean destroyed() { return destroyed; }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** The void, with a plains sky so the arena does not look like an unset world. */
    private static final class VoidGenerator extends ChunkGenerator {
        @Override
        public byte[][] generateBlockSections(World world, Random random, int chunkX, int chunkZ, BiomeGrid biomes) {
            for (int x = 0; x < 16; x++)
                for (int z = 0; z < 16; z++) biomes.setBiome(x, z, org.bukkit.block.Biome.PLAINS);
            return new byte[world.getMaxHeight() / 16][];
        }
        @Override public boolean canSpawn(World world, int x, int z) { return true; }
        @Override public List<BlockPopulator> getDefaultPopulators(World world) { return Collections.emptyList(); }
        @Override public Location getFixedSpawnLocation(World world, Random random) {
            return new Location(world, 0.5, FLOOR_Y + 1, 0.5);
        }
    }

    /** Every block a mode's arena uses, so a test can assert the shape without a running server. */
    public static List<Material> palette() {
        return new ArrayList<Material>(Arrays.asList(
                Material.SMOOTH_BRICK, Material.STONE, Material.QUARTZ_BLOCK));
    }
}
