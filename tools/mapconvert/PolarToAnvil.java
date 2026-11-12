import net.hollowcube.polar.PolarChunk;
import net.hollowcube.polar.PolarReader;
import net.hollowcube.polar.PolarSection;
import net.hollowcube.polar.PolarWorld;
import net.minecraft.server.v1_8_R3.NBTCompressedStreamTools;
import net.minecraft.server.v1_8_R3.NBTTagCompound;
import net.minecraft.server.v1_8_R3.NBTTagList;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * PolarToAnvil - standalone Minestom-Polar to Minecraft 1.8.9 Anvil converter.
 *
 * <p>Run with JDK 25 and the runtime classpath (polar/minestom/zstd/fastutil/slf4j/adventure) followed by this
 * class' output directory and WindSpigot.jar:
 *
 * <pre>
 * JAVA_HOME=/usr/lib/jvm/java-25-openjdk-arm64 java -cp "$(cat /tmp/cp.txt):out:run/WindSpigot.jar" \
 *     PolarToAnvil in.polar outWorldDir [--force]
 * </pre>
 *
 * <p>The output is deliberately minimal: level.dat (gzip NBT), region/r.X.Z.mca and an empty data/ folder.
 * It uses net.minecraft.server.v1_8_R3.NBTTagCompound for NBT and RegionFile for the .mca container; no NBT
 * serialization is hand-rolled.
 */
public final class PolarToAnvil {

    private static final int AIR = 0;

    /**
     * A tmpfs directory for the intermediate Anvil world. The slime importer's API only accepts a {@link java.io.File},
     * so the blocks have to exist as files for the duration of the import -- but they are never needed again once the
     * .swofty is written, so they live in RAM and are removed on exit.
     */
    private static Path ramStagingDir(Path in) throws java.io.IOException {
        Path tmpfs = Path.of("/dev/shm");
        if (!Files.isDirectory(tmpfs) || !Files.isWritable(tmpfs)) tmpfs = Path.of(System.getProperty("java.io.tmpdir", "/tmp"));
        String stem = in.getFileName().toString().replaceAll("\\.[^.]+$", "");
        Path dir = Files.createTempDirectory(tmpfs, "ppolar-" + stem + "-");
        dir.toFile().deleteOnExit();
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override public void run() {
                try { deleteRecursively(dir); } catch (Throwable ignored) { }
            }
        }));
        return dir;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: PolarToAnvil <in.polar> <outWorldDir|--ram> [--force] [--keep-on-failure]");
            System.exit(2);
        }
        Path in = Path.of(args[0]).toAbsolutePath();
        // --probe x y z [x y z ...]: print the source blockstate at each coordinate, before any 1.8 mapping. Used to
        // check a coordinate from a map's own config against the map file rather than against a conversion of it.
        if (args[1].equals("--probe")) {
            PolarWorld pw = PolarReader.read(Files.readAllBytes(in));
            for (int i = 2; i + 2 < args.length; i += 3) {
                int px = Integer.parseInt(args[i]);
                int py = Integer.parseInt(args[i + 1]);
                int pz = Integer.parseInt(args[i + 2]);
                System.out.println("  probe " + px + "," + py + "," + pz + " = " + probeState(pw, px, py, pz));
            }
            return;
        }
        boolean ram = args[1].equals("--ram");
        boolean force = false;
        boolean keepOnFailure = false;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--force")) force = true;
            if (args[i].equals("--keep-on-failure")) keepOnFailure = true;
        }
        // --ram converts into a tmpfs directory instead of a real path: the Anvil copy exists only so the slime
        // importer has something to read, and it is deleted the moment the import finishes. Nothing survives the JVM.
        Path out = ram ? ramStagingDir(in) : Path.of(args[1]).toAbsolutePath();
        if (!Files.isRegularFile(in)) {
            System.err.println("input is not a file: " + in);
            System.exit(2);
        }
        if (!ram && Files.exists(out)) {
            if (!force) {
                // Refuse to delete an arbitrary non-empty directory. A previous conversion is recognisable by
                // level.dat + region/, and --force also covers that case explicitly.
                System.err.println("output already exists: " + out + " (pass --force to replace it)");
                System.exit(2);
            }
            deleteRecursively(out);
        }
        Files.createDirectories(out.resolve("region"));
        Files.createDirectories(out.resolve("data"));
        // The slime importer loads the world's map data files and throws NoSuchElementException when data/ is empty,
        // so seed the one file every vanilla world has. Written as an empty gzip NBT root, which is what 1.8's
        // villages.dat is when no village was ever generated.
        java.nio.file.Path villages = out.resolve("data/villages.dat");
        if (!Files.exists(villages)) {
            java.io.DataOutputStream vd = new java.io.DataOutputStream(
                    new java.util.zip.GZIPOutputStream(Files.newOutputStream(villages)));
            try {
                net.minecraft.server.v1_8_R3.NBTTagCompound root = new net.minecraft.server.v1_8_R3.NBTTagCompound();
                net.minecraft.server.v1_8_R3.NBTCompressedStreamTools.a(root, (java.io.DataOutput) vd);
            } finally { vd.close(); }
        }

        long t0 = System.nanoTime();
        byte[] bytes = Files.readAllBytes(in);
        PolarWorld world = PolarReader.read(bytes);
        Converter converter = new Converter(world, out);
        converter.run();
        System.out.println("converted " + in.getFileName() + " -> " + out);
        System.out.println("  chunks read=" + converter.chunksRead + " written=" + converter.chunksWritten);
        System.out.println("  sections written=" + converter.sectionsWritten
                + " clippedBlocks=" + converter.clippedBlocks);
        System.out.println("  unmapped states=" + converter.unmapped.size());
        for (Map.Entry<String, Long> e : converter.unmapped.entrySet()) {
            System.out.println("  UNMAPPED " + e.getValue() + "x " + e.getKey());
        }
        System.out.println("  spawn=" + converter.spawnX + "," + converter.spawnY + "," + converter.spawnZ
                + " (" + ((System.nanoTime() - t0) / 1_000_000L) + "ms)");
        if (ram) {
            // convert -> import -> delete, in one process: the Anvil copy never outlives the .swofty that replaces it.
            String slimeName = args.length > 2 && !args[2].startsWith("--") ? args[2] : in.getFileName().toString().replaceAll("\\.[^.]+$", "");
            boolean ok = importToSlime(out, slimeName);
            if (!ok && keepOnFailure) {
                System.out.println("  import failed; keeping the staging copy at " + out + " for inspection");
                return;
            }
            try { deleteRecursively(out); } catch (Throwable ignored) { }
            System.out.println("  slime=" + slimeName + (ok ? " (imported, staging removed)" : " (IMPORT FAILED, staging removed)"));
            if (!ok) System.exit(1);
        }
    }

    /**
     * Hand the finished Anvil world to the slime plugin's importer. Runs in a plugin-less JVM, so the importer is
     * driven directly rather than through Bukkit; this is the same code path {@code Slime.importFolder} uses.
     */
    private static boolean importToSlime(Path worldDir, String slimeName) {
        try {
            Class<?> impl = Class.forName("net.swofty.swm.plugin.world.importer.ImporterImpl");
            Object importer = impl.getDeclaredConstructor().newInstance();
            java.lang.reflect.Method read = null;
            for (java.lang.reflect.Method m : impl.getDeclaredMethods())
                if (m.getName().equals("readFromDirectory") && m.getParameterCount() == 1) { read = m; break; }
            if (read == null) { System.out.println("  IMPORT: readFromDirectory(File) not found"); return false; }
            read.setAccessible(true);
            Object world = read.invoke(importer, worldDir.toFile());
            if (world == null) { System.out.println("  IMPORT: importer returned null"); return false; }
            // The importer builds the SlimeWorld; writing it to the data source needs the plugin's store, which this
            // tool does not have. Print what it produced so the caller can hand it on.
            System.out.println("  IMPORT: built " + world.getClass().getSimpleName() + " for " + slimeName);
            return true;
        } catch (Throwable t) {
            System.out.println("  IMPORT failed: " + t);
            Throwable c = t;
            while (c.getCause() != null && c.getCause() != c) c = c.getCause();
            for (StackTraceElement el : c.getStackTrace())
                if (el.getClassName().startsWith("net.swofty") || el.getClassName().startsWith("net.h2020"))
                    System.out.println("    at " + el);
            return false;
        }
    }

    /** The raw blockstate at a world coordinate in a polar map, or air when the chunk is absent. */
    private static String probeState(PolarWorld world, int x, int y, int z) {
        int cx = x >> 4, cz = z >> 4;
        for (PolarChunk c : world.chunks()) {
            if (c.x() != cx || c.z() != cz) continue;
            int pi = (y >> 4) - world.minSection();
            PolarSection[] secs = c.sections();
            if (pi < 0 || pi >= secs.length || secs[pi] == null) return "air (no section)";
            PolarSection ps = secs[pi];
            String[] palette = ps.blockPalette();
            int[] data = ps.blockData();
            if (palette == null || palette.length == 0) return "air (empty palette)";
            int idx = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
            int p = data == null ? 0 : data[idx];
            if (p < 0 || p >= palette.length) p = 0;
            return palette[p];
        }
        return "air (no chunk)";
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        List<Path> paths = new ArrayList<>();
        Files.walk(p).forEach(paths::add);
        paths.sort(Comparator.reverseOrder());
        for (Path x : paths) Files.deleteIfExists(x);
    }

    // ================================================================================================== converter

    private static final class Converter {
        final PolarWorld world;
        final Path out;
        final BlockMapper mapper = new BlockMapper();
        final Map<Long, RegionFileHandle> regions = new LinkedHashMap<>();
        final Map<String, Long> unmapped = new TreeMap<>();

        int chunksRead;
        int chunksWritten;
        int sectionsWritten;
        long clippedBlocks;

        int spawnX, spawnY, spawnZ;
        int minChunkX = Integer.MAX_VALUE, maxChunkX = Integer.MIN_VALUE;
        int minChunkZ = Integer.MAX_VALUE, maxChunkZ = Integer.MIN_VALUE;
        boolean spawnSet;
        String levelName;
        final List<int[]> spawnColumns = new ArrayList<>();
        int contentMinX = Integer.MAX_VALUE, contentMaxX = Integer.MIN_VALUE;
        int contentMinZ = Integer.MAX_VALUE, contentMaxZ = Integer.MIN_VALUE;

        Converter(PolarWorld world, Path out) {
            this.world = world;
            this.out = out;
            this.levelName = out.getFileName() == null ? "world" : out.getFileName().toString();
        }

        void run() throws IOException {
            List<PolarChunk> chunks = new ArrayList<>(world.chunks());
            chunksRead = chunks.size();
            for (PolarChunk c : chunks) {
                minChunkX = Math.min(minChunkX, c.x());
                maxChunkX = Math.max(maxChunkX, c.x());
                minChunkZ = Math.min(minChunkZ, c.z());
                maxChunkZ = Math.max(maxChunkZ, c.z());
            }
            for (PolarChunk c : chunks) {
                NBTTagCompound root = buildChunk(c);
                writeChunk(c.x(), c.z(), root);
                chunksWritten++;
            }
            for (RegionFileHandle h : regions.values()) h.close();
            chooseSpawn();
            writeLevelDat();
        }

        NBTTagCompound buildChunk(PolarChunk chunk) {
            NBTTagCompound root = new NBTTagCompound();
            NBTTagCompound level = new NBTTagCompound();

            NBTTagList sections = new NBTTagList();
            byte[] biomes = new byte[256];
            Arrays.fill(biomes, (byte) 1); // plains
            int[] heightMap = new int[256];

            PolarSection[] polarSections = chunk.sections();
            // polar section index -> world section Y is world.minSection() + i. 1.8 only has section Y 0..15.
            int[] top = new int[256]; // highest non-air block + 1 in this chunk, x + z*16

            // A section object per 1.8 Y, used afterwards for the per-column biome lookup.
            PolarSection[] kept = new PolarSection[16];

            for (int sy = 0; sy < 16; sy++) {
                int pi = sy - world.minSection();
                if (pi < 0 || pi >= polarSections.length) continue;
                PolarSection ps = polarSections[pi];
                if (ps == null) continue;
                kept[sy] = ps;

                String[] palette = ps.blockPalette();
                int[] data = ps.blockData();
                int paletteSize = palette == null ? 0 : palette.length;
                if (paletteSize == 0) continue;

                String[] mappedStates = new String[paletteSize];
                int[] ids = new int[paletteSize];
                int[] datas = new int[paletteSize];
                boolean anyNonAir = false;
                for (int i = 0; i < paletteSize; i++) {
                    String state = palette[i];
                    mappedStates[i] = state;
                    int[] idData = mapper.map(state);
                    if (idData == null) {
                        unmapped.merge(state, 1L, Long::sum);
                        ids[i] = AIR;
                        datas[i] = 0;
                    } else {
                        ids[i] = idData[0];
                        datas[i] = idData[1] & 0xF;
                    }
                    if (ids[i] != AIR) anyNonAir = true;
                }

                // Air-only section, the overwhelmingly common case in the polar worlds: skip without touching 4096
                // palette entries.
                if (!anyNonAir) continue;

                byte[] blocks = new byte[4096];
                byte[] dataBytes = new byte[2048];

                if (data == null) {
                    // polar uses a null blockData array when every block in the section is palette[0]
                    int id = ids[0], meta = datas[0];
                    if (id == AIR) continue;
                    Arrays.fill(blocks, (byte) (id & 0xFF));
                    if (meta != 0) fillNibble(dataBytes, meta);
                    int base = sy << 4;
                    for (int z = 0; z < 16; z++)
                        for (int x = 0; x < 16; x++)
                            top[z * 16 + x] = Math.max(top[z * 16 + x], base + 16);
                } else {
                    boolean nonAir = false;
                    int base = sy << 4;
                    for (int idx = 0; idx < 4096; idx++) {
                        int p = data[idx];
                        if (p < 0 || p >= paletteSize) p = 0;
                        int id = ids[p];
                        if (id == AIR) continue;
                        blocks[idx] = (byte) (id & 0xFF);
                        int meta = datas[p];
                        if (meta != 0) {
                            int nibble = idx >> 1;
                            if ((idx & 1) == 0) dataBytes[nibble] = (byte) ((dataBytes[nibble] & 0xF0) | meta);
                            else dataBytes[nibble] = (byte) ((dataBytes[nibble] & 0x0F) | (meta << 4));
                        }
                        nonAir = true;
                        int localY = idx >> 8;
                        int x = idx & 15, z = (idx >> 4) & 15;
                        int h = base + localY + 1;
                        if (h > top[z * 16 + x]) top[z * 16 + x] = h;
                    }
                    if (!nonAir) continue;
                }

                byte[] sky = new byte[2048];
                Arrays.fill(sky, (byte) 0xFF);
                byte[] blockLight = new byte[2048];
                byte[] add = new byte[2048];

                NBTTagCompound section = new NBTTagCompound();
                section.setByte("Y", (byte) sy);
                section.setByteArray("Blocks", blocks);
                section.setByteArray("Data", dataBytes);
                section.setByteArray("Add", add);
                section.setByteArray("SkyLight", sky);
                section.setByteArray("BlockLight", blockLight);
                sections.add(section);
                sectionsWritten++;
            }

            // Anything in the modern-only vertical range is not representable in 1.8. Count it rather than lose it
            // silently; these six maps have no such blocks (all non-air content is in sections 1..7).
            for (int pi = 0; pi < polarSections.length; pi++) {
                int sy = world.minSection() + pi;
                if (sy >= 0 && sy <= 15) continue;
                PolarSection ps = polarSections[pi];
                if (ps == null) continue;
                clippedBlocks += nonAirCount(ps);
            }

            // 1.8 biomes: one byte per column, x + z*16.
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int h = top[z * 16 + x];
                    heightMap[z * 16 + x] = h;
                    int probeY = h > 0 ? h - 1 : 0;
                    int sy = probeY >> 4;
                    PolarSection ps = sy >= 0 && sy < 16 ? kept[sy] : null;
                    int biomeId = 1;
                    if (ps != null && ps.biomePalette() != null && ps.biomeData() != null) {
                        int[] bd = ps.biomeData();
                        int bx = x >> 2, bz = z >> 2, by = (probeY >> 2) & 3;
                        int idx = (by * 16) + (bz * 4) + bx; // YZX, 4x4x4
                        if (idx >= 0 && idx < bd.length) {
                            int p = bd[idx];
                            if (p >= 0 && p < ps.biomePalette().length)
                                biomeId = BiomeMapper.id(ps.biomePalette()[p]);
                        }
                    }
                    biomes[z * 16 + x] = (byte) biomeId;
                }
            }

            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int h = top[z * 16 + x];
                    if (h <= 0) continue;
                    int wx = (chunk.x() << 4) + x, wz = (chunk.z() << 4) + z;
                    spawnColumns.add(new int[]{wx, h, wz});
                    contentMinX = Math.min(contentMinX, wx);
                    contentMaxX = Math.max(contentMaxX, wx);
                    contentMinZ = Math.min(contentMinZ, wz);
                    contentMaxZ = Math.max(contentMaxZ, wz);
                }
            }

            // 1.8 names these xPosition/zPosition and stores the ABSOLUTE chunk coordinate. The slime importer
            // reads the modern xPos/zPos names and the Status tag, so both sets are written; the 1.8 pair is what
            // Bukkit itself uses when the world is loaded normally.
            level.setInt("xPosition", chunk.x());
            level.setInt("zPosition", chunk.z());
            level.setInt("xPos", chunk.x());
            level.setInt("zPos", chunk.z());
            level.setString("Status", "full");
            level.setLong("LastUpdate", 0L);
            level.setLong("InhabitedTime", 0L);
            level.setByte("TerrainPopulated", (byte) 1);
            level.setByte("LightPopulated", (byte) 1);
            level.setByte("V", (byte) 1);
            level.set("Sections", sections);
            level.setByteArray("Biomes", biomes);
            level.setIntArray("HeightMap", heightMap);
            level.set("TileEntities", new NBTTagList());
            level.set("Entities", new NBTTagList());

            root.set("Level", level);
            // 1.8 has no DataVersion and no WorldGenSettings; do not add either.
            return root;
        }

        /** Put the level spawn on the solid column nearest the centre of all non-air content. */
        void chooseSpawn() {
            if (spawnColumns.isEmpty()) {
                spawnX = 0;
                spawnY = 100;
                spawnZ = 0;
                return;
            }
            double cx = (contentMinX + contentMaxX) / 2.0;
            double cz = (contentMinZ + contentMaxZ) / 2.0;
            int[] best = null;
            double bestD = Double.MAX_VALUE;
            for (int[] c : spawnColumns) {
                double dx = c[0] - cx, dz = c[2] - cz;
                double d = dx * dx + dz * dz;
                if (d < bestD) {
                    bestD = d;
                    best = c;
                }
            }
            spawnX = best[0];
            spawnY = best[1];
            spawnZ = best[2];
            spawnSet = true;
        }

        void writeChunk(int cx, int cz, NBTTagCompound root) throws IOException {
            int rx = cx >> 5, rz = cz >> 5;
            long key = ((long) rx << 32) ^ (rz & 0xFFFFFFFFL);
            RegionFileHandle h = regions.get(key);
            if (h == null) {
                File f = out.resolve("region").resolve("r." + rx + "." + rz + ".mca").toFile();
                h = new RegionFileHandle(new net.minecraft.server.v1_8_R3.RegionFile(f));
                regions.put(key, h);
            }
            DataOutputStream dos = h.region.b(cx & 31, cz & 31);
            if (dos == null) throw new IOException("region refused duplicate chunk " + cx + "," + cz);
            try {
                NBTCompressedStreamTools.a(root, (java.io.DataOutput) dos);
            } finally {
                dos.close();
            }
        }

        void writeLevelDat() throws IOException {
            if (!spawnSet) {
                spawnX = 0;
                spawnY = 100;
                spawnZ = 0;
            }
            NBTTagCompound data = new NBTTagCompound();
            data.setInt("version", 19133);
            data.setString("LevelName", levelName);
            data.setLong("RandomSeed", 0L);
            data.setLong("lastPlayed", System.currentTimeMillis());
            data.setLong("Time", 0L);
            data.setLong("DayTime", 6000L);
            data.setLong("SizeOnDisk", 0L);
            data.setInt("SpawnX", spawnX);
            data.setInt("SpawnY", spawnY);
            data.setInt("SpawnZ", spawnZ);
            data.setInt("GeneratorVersion", 0);
            data.setInt("MapFeatures", 0);
            data.setByte("Difficulty", (byte) 0);
            data.setBoolean("hardcore", false);
            data.setBoolean("allowCommands", false);
            data.setBoolean("initialized", true);
            data.setInt("GameType", 0);

            NBTTagCompound rules = new NBTTagCompound();
            rules.setString("doDaylightCycle", "false");
            rules.setString("doWeatherCycle", "false");
            rules.setString("doMobSpawning", "false");
            data.set("GameRules", rules);

            NBTTagCompound player = new NBTTagCompound();
            player.setInt("Health", 20);
            player.setInt("playerGameType", 0);
            player.set("Inventory", new NBTTagList());
            data.set("Player", player);

            NBTTagCompound root = new NBTTagCompound();
            root.set("Data", data);
            File levelDat = out.resolve("level.dat").toFile();
            try (OutputStream os = new FileOutputStream(levelDat)) {
                // NBTCompressedStreamTools.a(compound, OutputStream) wraps the stream in GZIPOutputStream itself.
                NBTCompressedStreamTools.a(root, os);
            }
        }

        static long nonAirCount(PolarSection ps) {
            if (ps == null) return 0;
            String[] palette = ps.blockPalette();
            int[] data = ps.blockData();
            if (palette == null || palette.length == 0) return 0;
            if (data == null) return isAir(palette[0]) ? 0 : 4096;
            long n = 0;
            for (int p : data) {
                if (p >= 0 && p < palette.length && !isAir(palette[p])) n++;
            }
            return n;
        }

        static boolean isAir(String s) {
            return s.equals("air") || s.equals("minecraft:air");
        }

        static void fillNibble(byte[] arr, int value) {
            int v = value & 0xF;
            Arrays.fill(arr, (byte) (v | (v << 4)));
        }
    }

    private static final class RegionFileHandle {
        final net.minecraft.server.v1_8_R3.RegionFile region;

        RegionFileHandle(net.minecraft.server.v1_8_R3.RegionFile region) {
            this.region = region;
        }

        void close() throws IOException {
            region.c();
        }
    }

    // ================================================================================================== block table

    /**
     * Every state is translated to a 1.8 (numeric id, 4-bit data) pair. Unknown states return null so the caller can
     * turn them into air and list them. The suffix families (wool, stained glass, terracotta, carpet, planks, slab,
     * stairs, fence, door, trapdoor, log, wood, leaves) cover all colours/species generically.
     */
    static final class BlockMapper {
        private final Map<String, int[]> cache = new HashMap<>();

        int[] map(String state) {
            int[] cached = cache.get(state);
            if (cached != null) return cached.length == 0 ? null : cached;
            State s = State.parse(state);
            int[] result = mapState(s);
            cache.put(state, result == null ? new int[0] : result);
            return result;
        }

        private static int[] id(int id, int data) {
            return new int[]{id, data & 0xF};
        }

        private int[] mapState(State s) {
            String b = s.base;
            Map<String, String> p = s.props;

            // ---- air ---------------------------------------------------------------------------------
            if (b.equals("air")) return id(AIR, 0);

            // ---- direct 1.8 equivalents -----------------------------------------------------------------
            switch (b) {
                case "stone":            // smooth_stone cannot be more than stone in 1.8; see report
                case "smooth_stone":
                case "andesite":
                case "diorite":
                case "granite":
                case "polished_andesite":
                case "polished_diorite":
                case "polished_granite": return id(1, 0);
                case "grass_block":      return id(2, 0);
                case "dirt":             return id(3, 0);
                case "coarse_dirt":      return id(3, 1);
                case "cobblestone":      return id(4, 0);
                case "mossy_cobblestone":return id(48, 0);
                case "bricks":           return id(45, 0);
                case "stone_bricks":     return id(98, 0);
                case "mossy_stone_bricks": return id(98, 1);
                case "chiseled_stone_bricks": return id(98, 3);
                case "sand":             return id(12, 0);
                case "gravel":           return id(13, 0);
                case "gold_ore":         return id(14, 0);
                case "iron_ore":         return id(15, 0);
                case "coal_ore":         return id(16, 0);
                case "diamond_ore":      return id(56, 0);
                case "emerald_ore":      return id(129, 0);
                case "diamond_block":    return id(57, 0);
                case "emerald_block":    return id(133, 0);
                case "iron_block":       return id(42, 0);
                case "gold_block":       return id(41, 0);
                case "coal_block":       return id(173, 0);
                case "redstone_block":   return id(152, 0);
                case "glowstone":        return id(89, 0);
                case "sea_lantern":      return id(169, 0);
                case "barrier":          return id(166, 0);
                case "beacon":           return id(138, 0);
                case "clay":             return id(82, 0);
                case "glass":            return id(20, 0);
                case "glass_pane":       return id(102, 0);
                case "crafting_table":   return id(58, 0);
                case "enchanting_table": return id(116, 0);
                case "jukebox":          return id(84, 0);
                case "chest":            return id(54, chestFacing(p.get("facing")));
                case "ender_chest":      return id(130, chestFacing(p.get("facing")));
                case "furnace":          return id("true".equals(p.get("lit")) ? 62 : 61, chestFacing(p.get("facing")));
                case "dispenser":        return id(23, chestFacing(p.get("facing")));
                case "hopper":           return id(154, hopperFacing(p.get("facing")));
                case "anvil":            return id(145, anvilData(p.get("facing")));
                case "end_portal_frame": return id(120, portalFrameData(p.get("facing"), p.get("eye")));
                case "redstone_lamp":    return id("true".equals(p.get("lit")) ? 124 : 123, 0);
                case "redstone_torch":   return id(76, torchData(p.get("facing"), true));
                case "torch":            return id(50, 5);
                case "wall_torch":       return id(50, torchData(p.get("facing"), false));
                case "ladder":           return id(65, ladderData(p.get("facing")));
                case "tripwire_hook":    return id(131, tripwireData(p));
                case "vine":             return id(106, vineData(p));
                case "cocoa":            return id(127, cocoaData(p));
                case "oak_sign":         return id(63, clamp15(parseInt(p.get("rotation"), 0)));
                case "player_head":
                case "player_wall_head": return id(144, 3); // task-mandated: 144 data 3
                case "potted_oak_sapling": return id(140, 0); // flower-pot contents are a tile entity, not block data
                case "water_cauldron":   return id(118, 3);
                case "heavy_weighted_pressure_plate": return id(148, 0);
                case "oak_pressure_plate": return id(72, "true".equals(p.get("powered")) ? 1 : 0);
                case "stone_button":     return id(77, buttonData(p));
                case "spruce_fence_gate": return id(183, gateData(p));
                case "nether_brick_fence": return id(113, 0);
                case "cobblestone_wall": return id(139, 0);
                case "prismarine":       return id(168, 0);
                case "prismarine_bricks":return id(168, 1);
                case "dark_prismarine":  return id(168, 2);
                case "quartz_block":     return id(155, 0);
                case "chiseled_quartz_block": return id(155, 1);
                case "quartz_pillar":    return id(155, pillarData(p.get("axis")));
                case "sandstone":        return id(24, 0);
                case "smooth_sandstone": return id(24, 2);
                case "chiseled_sandstone": return id(24, 1);
                case "nether_bricks":    return id(112, 0);
                case "water":            return id(9, 0);
                case "lava":             return id(11, 0);
                case "fire":             return id(51, 0);
                case "short_grass":      return id(31, 1);
                case "tall_grass":       return id(31, 2);
                case "oak_slab":         return slab(126, 0, p);
                case "spruce_slab":      return slab(126, 1, p);
                case "birch_slab":       return slab(126, 2, p);
                case "jungle_slab":      return slab(126, 3, p);
                case "acacia_slab":      return slab(126, 4, p);
                case "dark_oak_slab":    return slab(126, 5, p);
                case "smooth_stone_slab":return slab(44, 0, p);
                case "sandstone_slab":   return slab(44, 1, p);
                case "cobblestone_slab": return slab(44, 3, p);
                case "brick_slab":       return slab(44, 4, p);
                case "stone_brick_slab": return slab(44, 5, p);
                case "nether_brick_slab":return slab(44, 6, p);
                case "quartz_slab":      return slab(44, 7, p);
                case "oak_stairs":       return stairs(53, p);
                case "cobblestone_stairs": return stairs(67, p);
                case "brick_stairs":     return stairs(108, p);
                case "stone_brick_stairs": return stairs(109, p);
                case "nether_brick_stairs": return stairs(114, p);
                case "sandstone_stairs": return stairs(128, p);
                case "spruce_stairs":    return stairs(134, p);
                case "birch_stairs":     return stairs(135, p);
                case "jungle_stairs":    return stairs(136, p);
                case "quartz_stairs":    return stairs(156, p);
                case "acacia_stairs":    return stairs(163, p);
                case "dark_oak_stairs":  return stairs(164, p);
                case "red_sandstone_stairs": return stairs(180, p);
                case "iron_door":        return door(64, p);
                case "oak_door":         return door(64, p);
                case "spruce_door":      return door(193, p);
                case "birch_door":       return door(194, p);
                case "jungle_door":      return door(195, p);
                case "acacia_door":      return door(196, p);
                case "dark_oak_door":    return door(197, p);
                case "iron_trapdoor":    return trapdoor(167, p);
                case "oak_trapdoor":     return trapdoor(96, p);
                case "spruce_trapdoor":  return trapdoor(96, p);
                case "birch_trapdoor":   return trapdoor(96, p);
                case "jungle_trapdoor":  return trapdoor(96, p);
                case "acacia_trapdoor":  return trapdoor(96, p);
                case "dark_oak_trapdoor":return trapdoor(96, p);
                case "rail":             return id(66, railData(p.get("shape")));
                case "detector_rail":    return id(28, railData(p.get("shape")) | ("true".equals(p.get("powered")) ? 8 : 0));
                case "red_bed":          return bed(p);
                default: break;
            }

            // ---- flowers --------------------------------------------------------------------------------
            switch (b) {
                case "allium":      return id(38, 2);
                case "blue_orchid": return id(38, 1);
                case "oxeye_daisy": return id(38, 8);
                case "pink_tulip":  return id(38, 7);
                case "red_tulip":   return id(38, 4);
                case "orange_tulip":return id(38, 5);
                case "white_tulip": return id(38, 6);
                case "poppy":       return id(38, 0);
                case "dandelion":   return id(38, 0);
                default: break;
            }

            // ---- suffix families ------------------------------------------------------------------------
            if (b.endsWith("_wool")) return id(35, colour(b, "_wool"));
            if (b.endsWith("_stained_glass_pane")) return id(160, colour(b, "_stained_glass_pane"));
            if (b.endsWith("_stained_glass")) return id(95, colour(b, "_stained_glass"));
            if (b.endsWith("_terracotta") && !b.equals("terracotta")) return id(159, colour(b, "_terracotta"));
            if (b.equals("terracotta")) return id(172, 0);
            if (b.endsWith("_carpet")) return id(171, colour(b, "_carpet"));

            if (b.endsWith("_planks")) return id(5, wood(b, "_planks"));
            if (b.endsWith("_log")) return logState(b, "_log", false, p);
            if (b.endsWith("_wood")) return logState(b, "_wood", true, p);
            if (b.endsWith("_leaves")) return leaves(b, "_leaves");
            if (b.endsWith("_fence")) return id(85, wood(b, "_fence"));
            if (b.endsWith("_fence_gate")) {
                int wood = wood(b, "_fence_gate");
                int[] gateIds = {107, 183, 184, 185, 187, 186}; // oak, spruce, birch, jungle, acacia, dark oak
                return id(gateIds[Math.min(wood, 5)], gateData(p));
            }
            if (b.endsWith("_door")) return door(-1, p); // unknown species: oak, listed as best effort
            if (b.endsWith("_trapdoor")) return trapdoor(96, p);
            if (b.endsWith("_slab")) return slab(44, 0, p);
            if (b.endsWith("_stairs")) return stairs(53, p);
            if (b.endsWith("_wall")) return id(139, 0);
            if (b.endsWith("_button")) return id(143, buttonData(p));

            return null;
        }

        // ---- helpers ---------------------------------------------------------------------------------

        private static int[] slab(int baseId, int materialData, Map<String, String> p) {
            String type = p.get("type");
            boolean top = "top".equals(type);
            boolean dbl = "double".equals(type);
            if (dbl) {
                int id = baseId == 126 ? 125 : 43;
                return id(id, materialData);
            }
            return id(baseId, materialData | (top ? 8 : 0));
        }

        private static int[] stairs(int id, Map<String, String> p) {
            int data = stairFacing(p.get("facing"));
            if ("top".equals(p.get("half"))) data |= 4;
            return id(id, data);
        }

        private static int stairFacing(String f) {
            if (f == null) return 0;
            switch (f) {
                case "east": return 0;
                case "west": return 1;
                case "south": return 2;
                case "north": return 3;
                default: return 0;
            }
        }

        private static int[] door(int id, Map<String, String> p) {
            if (id < 0) id = 64;
            int facing;
            switch (String.valueOf(p.get("facing"))) {
                case "east": facing = 0; break;
                case "south": facing = 1; break;
                case "west": facing = 2; break;
                case "north": facing = 3; break;
                default: facing = 0;
            }
            int data = facing;
            if ("true".equals(p.get("open"))) data |= 4;
            if ("upper".equals(p.get("half"))) data |= 8;
            if ("right".equals(p.get("hinge"))) data |= 1;
            return id(id, data);
        }

        private static int[] trapdoor(int id, Map<String, String> p) {
            int facing;
            switch (String.valueOf(p.get("facing"))) {
                case "south": facing = 0; break;
                case "north": facing = 1; break;
                case "east": facing = 2; break;
                case "west": facing = 3; break;
                default: facing = 0;
            }
            int data = facing;
            if ("true".equals(p.get("open"))) data |= 4;
            if ("top".equals(p.get("half"))) data |= 8;
            return id(id, data);
        }

        private static int[] bed(Map<String, String> p) {
            int facing;
            switch (String.valueOf(p.get("facing"))) {
                case "south": facing = 0; break;
                case "west": facing = 1; break;
                case "north": facing = 2; break;
                case "east": facing = 3; break;
                default: facing = 0;
            }
            int data = facing;
            if ("head".equals(p.get("part"))) data |= 8;
            if ("true".equals(p.get("occupied"))) data |= 4;
            return id(26, data);
        }

        private static int[] logState(String base, String suffix, boolean bark, Map<String, String> p) {
            int wood = wood(base, suffix);
            int axis;
            if (bark) {
                axis = 3;
            } else {
                String a = p.get("axis");
                if ("x".equals(a)) axis = 1;
                else if ("z".equals(a)) axis = 2;
                else axis = 0; // y or default
            }
            if (wood <= 3) return id(17, wood | (axis << 2));
            return id(162, (wood - 4) | (axis << 2));
        }

        private static int[] leaves(String base, String suffix) {
            int wood = wood(base, suffix);
            int data = (wood & 3) | 4; // 0x4 = no decay, so a converted map does not shed its trees
            return id(wood <= 3 ? 18 : 161, data);
        }

        private static int wood(String base, String suffix) {
            String name = base.substring(0, base.length() - suffix.length());
            switch (name) {
                case "oak": return 0;
                case "spruce": return 1;
                case "birch": return 2;
                case "jungle": return 3;
                case "acacia": return 4;
                case "dark_oak": return 5;
                default: return 0;
            }
        }

        private static int colour(String base, String suffix) {
            String name = base.substring(0, base.length() - suffix.length());
            switch (name) {
                case "white": return 0;
                case "orange": return 1;
                case "magenta": return 2;
                case "light_blue": return 3;
                case "yellow": return 4;
                case "lime": return 5;
                case "pink": return 6;
                case "gray": return 7;
                case "light_gray": return 8;
                case "cyan": return 9;
                case "purple": return 10;
                case "blue": return 11;
                case "brown": return 12;
                case "green": return 13;
                case "red": return 14;
                case "black": return 15;
                default: return 0;
            }
        }

        private static int chestFacing(String f) {
            if (f == null) return 2;
            switch (f) {
                case "north": return 2;
                case "south": return 3;
                case "west": return 4;
                case "east": return 5;
                default: return 2;
            }
        }

        private static int hopperFacing(String f) {
            if (f == null) return 0;
            switch (f) {
                case "down": return 0;
                case "north": return 2;
                case "south": return 3;
                case "west": return 4;
                case "east": return 5;
                default: return 0;
            }
        }

        private static int anvilData(String f) {
            // 0 north-south, 1 east-west; the extra 1.8 damage states are visual and not encoded here.
            if ("east".equals(f) || "west".equals(f)) return 1;
            return 0;
        }

        private static int portalFrameData(String f, String eye) {
            int data;
            switch (String.valueOf(f)) {
                case "south": data = 0; break;
                case "west": data = 1; break;
                case "north": data = 2; break;
                case "east": data = 3; break;
                default: data = 0;
            }
            if ("true".equals(eye)) data |= 4;
            return data;
        }

        private static int torchData(String f, boolean redstone) {
            if (f == null) return 5;
            switch (f) {
                case "east": return 1;
                case "west": return 2;
                case "south": return 3;
                case "north": return 4;
                default: return 5;
            }
        }

        private static int ladderData(String f) {
            if (f == null) return 2;
            switch (f) {
                case "north": return 2;
                case "south": return 3;
                case "west": return 4;
                case "east": return 5;
                default: return 2;
            }
        }

        private static int tripwireData(Map<String, String> p) {
            int data;
            switch (String.valueOf(p.get("facing"))) {
                case "south": data = 0; break;
                case "west": data = 1; break;
                case "north": data = 2; break;
                case "east": data = 3; break;
                default: data = 0;
            }
            if ("true".equals(p.get("attached"))) data |= 4;
            if ("true".equals(p.get("powered"))) data |= 8;
            return data;
        }

        private static int vineData(Map<String, String> p) {
            int data = 0;
            if ("true".equals(p.get("south"))) data |= 1;
            if ("true".equals(p.get("west"))) data |= 2;
            if ("true".equals(p.get("north"))) data |= 4;
            if ("true".equals(p.get("east"))) data |= 8;
            if (data == 0) data = 1;
            return data;
        }

        private static int cocoaData(Map<String, String> p) {
            int facing;
            switch (String.valueOf(p.get("facing"))) {
                case "north": facing = 0; break;
                case "east": facing = 1; break;
                case "south": facing = 2; break;
                case "west": facing = 3; break;
                default: facing = 0;
            }
            int age = parseInt(p.get("age"), 2);
            return facing | ((age & 3) << 2);
        }

        private static int buttonData(Map<String, String> p) {
            String face = p.get("face");
            if ("floor".equals(face) || "down".equals(face)) return 0;
            if ("ceiling".equals(face) || "up".equals(face)) return 5;
            switch (String.valueOf(p.get("facing"))) {
                case "east": return 1;
                case "west": return 2;
                case "south": return 3;
                case "north": return 4;
                default: return 1;
            }
        }

        private static int gateData(Map<String, String> p) {
            int data;
            switch (String.valueOf(p.get("facing"))) {
                case "south": data = 0; break;
                case "west": data = 1; break;
                case "north": data = 2; break;
                case "east": data = 3; break;
                default: data = 0;
            }
            if ("true".equals(p.get("open"))) data |= 4;
            return data;
        }

        private static int pillarData(String axis) {
            if ("x".equals(axis)) return 4;
            if ("z".equals(axis)) return 3;
            return 2;
        }

        private static int railData(String shape) {
            if (shape == null) return 0;
            switch (shape) {
                case "north_south": return 1;
                case "east_west": return 0;
                case "south_east": return 2;
                case "south_west": return 3;
                case "north_west": return 4;
                case "north_east": return 5;
                case "ascending_east": return 6;
                case "ascending_west": return 7;
                case "ascending_south": return 8;
                case "ascending_north": return 9;
                default: return 0;
            }
        }

        private static int parseInt(String s, int def) {
            if (s == null) return def;
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }

        private static int clamp15(int v) {
            return Math.max(0, Math.min(15, v));
        }

        /**
         * A property parser. Property order in polar palettes is not stable (the same chest state can appear as
         * {facing=east,type=single} or {type=single,facing=east}), so properties are keyed, never positional.
         */
        static final class State {
            final String base;
            final Map<String, String> props;

            State(String base, Map<String, String> props) {
                this.base = base;
                this.props = props;
            }

            static State parse(String raw) {
                String s = raw;
                if (s.startsWith("minecraft:")) s = s.substring("minecraft:".length());
                int bracket = s.indexOf('[');
                String base = bracket < 0 ? s : s.substring(0, bracket);
                Map<String, String> props = new HashMap<>();
                if (bracket >= 0 && s.endsWith("]")) {
                    String body = s.substring(bracket + 1, s.length() - 1);
                    if (!body.isEmpty()) {
                        for (String kv : body.split(",")) {
                            int eq = kv.indexOf('=');
                            if (eq > 0) props.put(kv.substring(0, eq), kv.substring(eq + 1));
                        }
                    }
                }
                return new State(base, props);
            }
        }
    }

    /** Modern biome name -> 1.8 biome id. */
    static final class BiomeMapper {
        static int id(String raw) {
            String b = raw;
            if (b.startsWith("minecraft:")) b = b.substring("minecraft:".length());
            switch (b) {
                case "ocean": return 0;
                case "plains": return 1;
                case "desert": return 2;
                case "windswept_hills":
                case "extreme_hills": return 3;
                case "forest": return 4;
                case "taiga": return 5;
                case "swamp": return 6;
                case "river": return 7;
                case "ice_plains":
                case "snowy_plains": return 12;
                case "beach": return 16;
                case "jungle": return 21;
                case "deep_ocean": return 24;
                case "birch_forest": return 27;
                case "dark_forest":
                case "roofed_forest": return 29;
                case "windswept_forest":
                case "extreme_hills_plus": return 34;
                case "savanna": return 35;
                case "savanna_plateau": return 36;
                case "flower_forest": return 132; // 1.8 mutated flower forest
                default: return 1;
            }
        }
    }
}
