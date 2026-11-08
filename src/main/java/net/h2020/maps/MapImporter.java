package net.h2020.maps;

import net.h2020.api.game.GameFamily;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Derives a Lattice map template (map.yml) from a world, replacing the Python annotator.
 *
 * <p>The old toolchain needed a modern-format world, a separate scan pass (amulet) and a scan.json in between. This
 * reads a world that is already loaded on the server through chunk snapshots, so the whole import is one command:
 * blocks in, map.yml out, plus the glass waiting box built in place.
 *
 * <p>Heuristics are the same ones the Python used, with one deliberate change: a team's chests, pens and forge are
 * searched <b>inside that team's own island footprint</b>. The old code took the nearest chest to a bed regardless of
 * which island it stood on, which is how a team's spawn, forge and shop ended up on the neighbouring island - the
 * "one team off" bug.
 */
public final class MapImporter {

    private static final String[] TEAM_ORDER = {"red", "blue", "green", "yellow", "aqua", "white", "pink", "gray"};

    // block ids we look for in the snapshot pass
    private static final int AIR = 0, BED = 26, CHEST = 54, TRAPPED = 146, ENDER = 130, DIAMOND = 57, EMERALD = 133,
            BARRIER = 166, LADDER = 65;

    private MapImporter() {}

    // ======================================================================================= result

    public static final class Result {
        public final String name;
        public final GameFamily family;
        public final int teams;
        public final Map<String, List<double[]>> points = new LinkedHashMap<>();
        public final List<String> lines = new ArrayList<>();
        public File folder;
        public final java.util.List<String> tags = new java.util.ArrayList<String>();
        Result(String name, GameFamily family, int teams) { this.name = name; this.family = family; this.teams = teams; }
    }

    // ======================================================================================= world view

    /** Column tops and landmark positions, read once from chunk snapshots. */
    private static final class Scan {
        final World world;
        final Map<Long, Integer> top = new HashMap<>();       // column key -> highest solid y
        final Map<Long, Integer> low = new HashMap<>();       // column key -> lowest solid y (unused by bedwars)
        final List<int[]> beds = new ArrayList<>();           // x,y,z,data
        final List<int[]> chests = new ArrayList<>();
        final List<int[]> enders = new ArrayList<>();
        final List<int[]> diamonds = new ArrayList<>();
        final List<int[]> emeralds = new ArrayList<>();
        final List<int[]> barriers = new ArrayList<>();
        final List<int[]> ladders = new ArrayList<>();
        final int minX, maxX, minZ, maxZ, maxY;
        /** Flat copy of the map: id and data for every column in the scanned footprint, so no heuristic ever touches
         *  the world again (a getBlockAt outside the loaded set syncs a chunk load, which is what froze the server). */
        final short[] ids;
        final byte[] dataArr;
        final int size;

        Scan(World world) {
            this.world = world;
            this.size = 257;
            this.minX = -128; this.maxX = minX + size - 1; this.minZ = -128; this.maxZ = minZ + size - 1;
            this.maxY = 256;
            this.ids = new short[size * size * 256];
            this.dataArr = new byte[size * size * 256];
            // Pull the footprint in one chunk at a time and let it go again: a whole map resident at once is a lot
            // of heap on a server that is already running games.
            for (int cx = -8; cx <= 8; cx++)
                for (int cz = -8; cz <= 8; cz++) {
                    boolean wasLoaded = world.isChunkLoaded(cx, cz);
                    // generate=false: only pull chunks that already exist, never bake new terrain into a map
                    if (!wasLoaded && !world.loadChunk(cx, cz, false)) continue;
                    if (!world.isChunkLoaded(cx, cz)) continue;
                    readChunk(world.getChunkAt(cx, cz).getChunkSnapshot(false, false, false), cx << 4, cz << 4);
                    if (!wasLoaded) world.unloadChunk(cx, cz, false, false);
                }
        }

        /** One chunk of the world into the column and landmark tables. */
        private void readChunk(ChunkSnapshot s, int bx, int bz) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int wx = bx + x, wz = bz + z;
                    if (wx < minX || wx > maxX || wz < minZ || wz > maxZ) continue;
                    int topY = -1, lowY = -1;
                    for (int y = maxY - 1; y >= 0; y--) {
                        int id = s.getBlockTypeId(x, y, z);
                        int idx = index(wx, y, wz);
                        ids[idx] = (short) id;
                        dataArr[idx] = (byte) s.getBlockData(x, y, z);
                        if (id == AIR) continue;
                        if (topY < 0) topY = y;
                        lowY = y;
                        switch (id) {
                            case BED: beds.add(new int[]{wx, y, wz, s.getBlockData(x, y, z)}); break;
                            case CHEST: chests.add(new int[]{wx, y, wz}); break;
                            case TRAPPED: chests.add(new int[]{wx, y, wz}); break;
                            case ENDER: enders.add(new int[]{wx, y, wz}); break;
                            case DIAMOND: diamonds.add(new int[]{wx, y, wz}); break;
                            case EMERALD: emeralds.add(new int[]{wx, y, wz}); break;
                            case BARRIER: barriers.add(new int[]{wx, y, wz}); break;
                            case LADDER: ladders.add(new int[]{wx, y, wz}); break;
                            default: break;
                        }
                    }
                    if (topY >= 0) {
                        top.put(key(wx, wz), topY);
                        low.put(key(wx, wz), lowY);
                    }
                }
            }
        }

        int index(int x, int y, int z) { return ((x - minX) * size + (z - minZ)) * 256 + y; }

        int id(int x, int y, int z) {
            if (x < minX || x > maxX || z < minZ || z > maxZ) return AIR;
            if (y < 0 || y >= 256) return AIR;
            return ids[index(x, y, z)];
        }

        byte data(int x, int y, int z) {
            if (x < minX || x > maxX || z < minZ || z > maxZ) return 0;
            if (y < 0 || y >= 256) return 0;
            return dataArr[index(x, y, z)];
        }

        boolean passable(int x, int y, int z) {
            int id = id(x, y, z);
            if (id == AIR) return true;
            // the same set of "walk through me" blocks the Python list had, by id
            switch (id) {
                case 31: case 37: case 38: case 39: case 40: case 50: case 51: case 55: case 59: case 63:
                case 65: case 66: case 69: case 70: case 75: case 76: case 78: case 106: case 111: case 115:
                case 132: case 141: case 142: case 171: case 175: case 30:   // grass, flowers, torch, carpet, sign...
                    return true;
                default: return false;
            }
        }

        boolean solid(int x, int y, int z) { return !passable(x, y, z) && id(x, y, z) != AIR; }

        /** Feet y where a body can stand near y, or -1. */
        int floor(int x, int z, int y) {
            for (int dy = 0; dy <= 2; dy++) {
                int[] order = {y, y + 1, y - 1};
                if (dy >= order.length) break;
                int yy = order[dy];
                if (solid(x, yy - 1, z) && passable(x, yy, z) && passable(x, yy + 1, z)) return yy;
            }
            return -1;
        }

        boolean bottomSlab(int x, int y, int z) {
            if (id(x, y, z) != 44) return false;                  // 44 = step/slab
            return (data(x, y, z) & 8) == 0;                      // top bit set = upper half
        }

        int topAt(int x, int z) {
            Integer v = top.get(key(x, z));
            return v == null ? -1 : v;
        }
    }

    private static long key(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }

    // ======================================================================================= entry point

    /** Main thread: read the world into the flat snapshot. */
    public static Object scan(World world) { return new Scan(world); }

    /** Any thread: derive the points from the snapshot and write map.yml. */
    public static Result derive(Object scan, String name, GameFamily family, File mapsRoot) {
        return derive(scan, name, family, mapsRoot, null);
    }

    /** derive(...) with tags written into map.yml (lower case, de-duplicated). */
    public static Result derive(Object scan, String name, GameFamily family, File mapsRoot, java.util.List<String> tags) {
        Scan s = (Scan) scan;
        Result r = family == GameFamily.SKYWARS ? skywars(s, name) : bedwars(s, name);
        if (tags != null) for (String t : tags) {
            String v = t.trim().toLowerCase(java.util.Locale.ROOT);
            if (!v.isEmpty() && !r.tags.contains(v)) r.tags.add(v);
        }
        r.folder = new File(new File(mapsRoot, family.name().toLowerCase()), safeFolder(name));
        write(r);
        return r;
    }

    /** Main thread: the glass waiting box, which is the only part that writes to the world. */
    public static void buildLobby(World world, Result r, org.bukkit.World ignored) {
        List<double[]> lobby = r.points.get("waiting.lobby");
        if (lobby == null || lobby.isEmpty()) return;
        double[] p = lobby.get(0);
        buildWaitingBox(world, (int) Math.floor(p[0]), (int) Math.floor(p[1]) - 1, (int) Math.floor(p[2]));
    }

    public static Result importMap(World world, String name, GameFamily family, File mapsRoot) {
        Result r = derive(scan(world), name, family, mapsRoot);
        buildLobby(world, r, world);
        return r;
    }

    private static String safeFolder(String name) { return name.toLowerCase().replaceAll("[^a-z0-9]+", "_"); }

    // ======================================================================================= bedwars

    private static Result bedwars(Scan s, String name) {
        // a bed is two blocks; keep the foot of each pair and the direction it points
        List<int[]> heads = new ArrayList<>(), feet = new ArrayList<>();
        for (int[] b : s.beds) {
            if ((b[3] & 8) != 0) heads.add(b); else feet.add(b);
        }
        if (heads.isEmpty()) { heads = feet; }
        List<int[]> pairs = new ArrayList<>();      // {footX, footY, footZ, headX, headY, headZ}
        Set<Integer> used = new HashSet<>();
        for (int[] h : heads) {
            int best = -1, bestD = Integer.MAX_VALUE;
            for (int i = 0; i < feet.size(); i++) {
                if (used.contains(i)) continue;
                int[] f = feet.get(i);
                int d = Math.abs(f[0] - h[0]) + Math.abs(f[1] - h[1]) + Math.abs(f[2] - h[2]);
                if (d < bestD) { bestD = d; best = i; }
            }
            if (best < 0) continue;
            used.add(best);
            int[] f = feet.get(best);
            pairs.add(new int[]{f[0], f[1], f[2], h[0], h[1], h[2]});
        }
        if (pairs.isEmpty()) throw new IllegalStateException("no beds found in the world");
        pairs = mergeSameIsland(pairs);
        if (pairs.size() > 8) pairs = dropCentreBeds(pairs);
        if (pairs.size() > 8) pairs = mostTeamColoured(s, pairs, 8);
        if (pairs.size() != 2 && pairs.size() != 4 && pairs.size() != 8)
            throw new IllegalStateException("unexpected bed count " + pairs.size() + " (need 2, 4 or 8)");

        double cx = 0, cz = 0;
        for (int[] p : pairs) { cx += p[0]; cz += p[2]; }
        cx /= pairs.size(); cz /= pairs.size();
        final double fx = cx, fz = cz;
        pairs.sort(Comparator.comparingDouble(p -> angleCwFromNorth(p[0] - fx, p[2] - fz)));

        Result r = new Result(name, GameFamily.BEDWARS, pairs.size());
        int maxY = 0;
        Set<String> taken = new HashSet<>();
        for (int i = 0; i < pairs.size(); i++) {
            int[] p = pairs.get(i);
            String color = teamColorOf(s, new int[]{p[0], p[1], p[2]});
            if (color != null && taken.contains(color)) color = null;
            if (color == null) {
                // next colour nobody has yet, and never spin looking for one that does not exist
                for (int k = 0; k < TEAM_ORDER.length; k++) {
                    String cand = TEAM_ORDER[(i + k) % TEAM_ORDER.length];
                    if (!taken.contains(cand)) { color = cand; break; }
                }
                if (color == null) color = TEAM_ORDER[i % TEAM_ORDER.length];
            }
            taken.add(color);
            r.lines.add(color + "=" + p[0] + "," + p[1] + "," + p[2] + " (" + lastColorCount + " blocks)");
            int[] foot = {p[0], p[1], p[2]}, head = {p[3], p[4], p[5]};
            maxY = Math.max(maxY, foot[1]);
            r.points.put("team." + color + ".bed", list(pt(foot[0], foot[1], foot[2]), pt(head[0], head[1], head[2])));

            // the island this bed stands on: everything reachable from the bed column
            Set<Long> island = component(s, foot[0], foot[2], 26, 20);

            double ox = foot[0] - cx, oz = foot[2] - cz;
            double ol = Math.hypot(ox, oz);
            if (ol < 1e-6) { ox = 0; oz = 1; ol = 1; }
            ox /= ol; oz /= ol;

            // chests belong to a team only when they stand on that team's island - this is the off-by-one fix
            int[] tc = nearestOn(s.chests, island, foot[0], foot[2]);
            int[] ec = nearestOn(s.enders, island, foot[0], foot[2]);
            double sx, sy, sz;
            if (tc != null && ec != null) {
                sx = (tc[0] + ec[0]) / 2.0 + 0.5;
                sz = (tc[2] + ec[2]) / 2.0 + 0.5;
                sy = tc[1];
            } else if (tc != null) {
                sx = tc[0] + 0.5; sz = tc[2] + 0.5; sy = tc[1];
            } else {
                sx = foot[0] + 0.5 + ox * 10; sz = foot[2] + 0.5 + oz * 10; sy = foot[1];
            }
            // snap the spawn onto the island's axis (the bed lies on its centreline)
            double[] axis = islandFrame(foot, head, cx, cz);
            double afx = axis[0], afz = axis[1];
            double spawnX = Math.floor(sx - ox) + 0.5, spawnZ = Math.floor(sz - oz) + 0.5;
            if (Math.abs(afz) > 0.99 && Math.abs(spawnX - (foot[0] + 0.5)) <= 1) spawnX = foot[0] + 0.5;
            else if (Math.abs(afx) > 0.99 && Math.abs(spawnZ - (foot[2] + 0.5)) <= 1) spawnZ = foot[2] + 0.5;
            float faceMid = yawTowards(spawnX, spawnZ, cx, cz);
            r.points.put("team." + color + ".spawn", list(pt(spawnX, sy, spawnZ, faceMid)));

            double[] gen = forgeSpot(s, foot, head, new double[]{spawnX, sy, spawnZ}, cx, cz, island);
            if (gen == null) {
                int[] edge = walkToEdge(s, sx, sz, -afx, -afz, (int) sy - 1, 6);
                gen = new double[]{edge[0] + 0.5, sy, edge[1] + 0.5};
            }
            r.points.put("team." + color + ".generator", list(pt(gen[0], gen[1], gen[2])));

            Map<String, double[]> npc = npcSpots(s, foot, head, new double[]{spawnX, sy, spawnZ}, cx, cz, island);
            for (Map.Entry<String, double[]> e : npc.entrySet())
                r.points.put("team." + color + "." + e.getKey(), list(pt(e.getValue()[0], e.getValue()[1], e.getValue()[2], (float) e.getValue()[3])));

            int minIx = Integer.MAX_VALUE, maxIx = Integer.MIN_VALUE, minIz = Integer.MAX_VALUE, maxIz = Integer.MIN_VALUE;
            for (long k : island) {
                int ix = (int) (k >> 32), iz = (int) k;
                minIx = Math.min(minIx, ix); maxIx = Math.max(maxIx, ix);
                minIz = Math.min(minIz, iz); maxIz = Math.max(maxIz, iz);
            }
            r.points.put("team." + color + ".base",
                    list(pt(minIx, foot[1] - 10, minIz), pt(maxIx, foot[1] + 20, maxIz)));
        }

        // generators: isolated diamond/emerald blocks away from the bases, open to the sky
        r.points.put("gen.diamond", generatorPoints(s, s.diamonds, pairs));
        r.points.put("gen.emerald", generatorPoints(s, s.emeralds, pairs));
        List<double[]> ems = r.points.get("gen.emerald");
        double midY = ems.isEmpty() ? maxY + 1 : ems.stream().mapToDouble(p -> p[1]).average().orElse(maxY + 1);
        double[] mid = midSpot(s, cx, cz, (int) Math.round(midY));
        r.points.put("mid", list(pt(mid[0], mid[1], mid[2])));
        r.points.put("spectator", list(pt(cx + 0.5, maxY + 25, cz + 0.5)));

        int maxTop = 0;
        for (int v : s.top.values()) maxTop = Math.max(maxTop, v);
        int lobbyY = Math.max(maxTop + 15, maxY + 40);
        r.points.put("waiting.lobby", list(pt(cx + 0.5, lobbyY + 1, cz + 0.5)));
        r.lines.add("beds " + pairs.size() + ", lobby y " + lobbyY);
        writeHeader(r, maxY + 30);
        return r;
    }

    // ======================================================================================= skywars

    private static Result skywars(Scan s, String name) {
        if (s.chests.isEmpty()) throw new IllegalStateException("no chests found: a skywars map needs chests on its islands");
        List<List<int[]>> islands = new ArrayList<>();
        List<Set<Long>> islandCols = new ArrayList<>();
        Set<Long> used = new HashSet<>();
        for (int[] c : s.chests) {
            long k = key(c[0], c[2]);
            if (used.contains(k)) continue;
            Set<Long> comp = component(s, c[0], c[2], 30, 40);
            if (comp.isEmpty()) comp.add(k);
            List<int[]> members = new ArrayList<>();
            for (int[] o : s.chests) if (comp.contains(key(o[0], o[2]))) { members.add(o); used.add(key(o[0], o[2])); }
            islands.add(members);
            islandCols.add(comp);
        }
        double cx = 0, cz = 0;
        for (int[] c : s.chests) { cx += c[0]; cz += c[2]; }
        cx /= s.chests.size(); cz /= s.chests.size();

        List<Integer> nearMid = new ArrayList<>();
        for (int i = 0; i < islands.size(); i++) {
            double[] cen = centroid(islandCols.get(i));
            if (Math.hypot(cen[0] - cx, cen[1] - cz) < 30) nearMid.add(i);
        }
        if (nearMid.isEmpty()) {
            int best = 0; double bd = Double.MAX_VALUE;
            for (int i = 0; i < islands.size(); i++) {
                double[] cen = centroid(islandCols.get(i));
                double d = Math.hypot(cen[0] - cx, cen[1] - cz);
                if (d < bd) { bd = d; best = i; }
            }
            nearMid.add(best);
        }
        List<int[]> midChests = new ArrayList<>();
        for (int i : nearMid) midChests.addAll(islands.get(i));

        List<Integer> others = new ArrayList<>();
        for (int i = 0; i < islands.size(); i++) if (!nearMid.contains(i)) others.add(i);
        final double fcx = cx, fcz = cz;
        others.sort(Comparator.comparingDouble(i -> {
            double[] cen = centroid(islandCols.get(othersIdx(islands, i)));
            return angleCwFromNorth(cen[0] - fcx, cen[1] - fcz);
        }));

        Result r = new Result(name, GameFamily.SKYWARS, others.size());
        int maxTop = 0;
        for (int v : s.top.values()) maxTop = Math.max(maxTop, v);
        int idx = 0;
        for (int i : others) {
            List<int[]> chests = islands.get(i);
            Set<Long> cols = islandCols.get(i);
            double[] cen = centroid(cols);
            int lowest = Integer.MAX_VALUE;
            for (long k : cols) lowest = Math.min(lowest, s.topAt((int) (k >> 32), (int) k));
            List<long[]> ground = new ArrayList<>();
            for (long k : cols) if (s.topAt((int) (k >> 32), (int) k) <= lowest + 2) ground.add(new long[]{k >> 32, k});
            if (ground.isEmpty()) for (long k : cols) ground.add(new long[]{k >> 32, k});
            long[] hx = ground.get(0);
            double bestD = Double.MAX_VALUE;
            for (long[] g : ground) {
                double d = Math.hypot(g[0] - cx, g[1] - cz);
                if (d < bestD) { bestD = d; hx = g; }
            }
            int cageY = s.topAt((int) hx[0], (int) hx[1]) + 5;
            // a ladder means an enclosed island: cage above the ladder top
            int[] bestLadder = null;
            for (int[] l : s.ladders) if (cols.contains(key(l[0], l[2]))) if (bestLadder == null || l[1] > bestLadder[1]) bestLadder = l;
            if (bestLadder != null) { hx = new long[]{bestLadder[0], bestLadder[2]}; cageY = s.topAt((int) hx[0], (int) hx[1]) + 5; }
            float yaw = yawTowards(hx[0] + 0.5, hx[1] + 0.5, cx, cz);
            r.points.put("island." + idx + ".cage", list(pt(hx[0] + 0.5, cageY, hx[1] + 0.5, yaw)));
            List<double[]> cs = new ArrayList<>();
            for (int[] c : chests) cs.add(pt(c[0], c[1], c[2]));
            r.points.put("island." + idx + ".chests", cs);
            int minIx = Integer.MAX_VALUE, maxIx = Integer.MIN_VALUE, minIz = Integer.MAX_VALUE, maxIz = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            for (long k : cols) {
                int x = (int) (k >> 32), z = (int) k, y = s.topAt(x, z);
                minIx = Math.min(minIx, x); maxIx = Math.max(maxIx, x);
                minIz = Math.min(minIz, z); maxIz = Math.max(maxIz, z);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
            r.points.put("island." + idx + ".bounds",
                    list(pt(minIx, minY - 15, minIz), pt(maxIx, maxY + 5, maxIz)));
            idx++;
        }
        List<double[]> mid = new ArrayList<>();
        for (int[] c : midChests) mid.add(pt(c[0], c[1], c[2]));
        r.points.put("mid.chests", mid);
        int my = s.topAt((int) Math.round(cx), (int) Math.round(cz));
        if (my < 0) my = maxTop;
        r.points.put("mid", list(pt(cx + 0.5, my + 1, cz + 0.5)));
        r.points.put("spectator", list(pt(cx + 0.5, maxTop + 15, cz + 0.5)));
        int lobbyY = maxTop + 30;
        r.points.put("waiting.lobby", list(pt(cx + 0.5, lobbyY + 1, cz + 0.5)));
        r.lines.add("islands " + others.size() + ", mid chests " + midChests.size());
        writeHeader(r, Math.min(255, maxTop + 20));
        return r;
    }

    private static int othersIdx(List<List<int[]>> islands, int i) { return i; }

    // ======================================================================================= geometry

    /** Dye index -> Lattice team colour, for the blocks maps use to mark their own islands. */
    private static String teamOfDye(int dye) {
        if (dye == 10) return "green";        // lime is what several maps paint green teams with
        if (dye == 12) return "aqua";         // and light blue what they paint aqua with
        switch (dye) {
            case 14: return "red";
            case 11: return "blue";
            case 13: return "green";
            case 4:  return "yellow";
            case 3:  return "aqua";
            case 0:  return "white";
            case 6:  return "pink";
            case 7:  return "gray";
            default: return null;
        }
    }

    /**
     * The team a bed belongs to, read from the island itself: the commonest team-coloured block (wool, stained clay,
     * stained glass, carpet) within a few blocks of the bed. The old annotator assigned colours by walking the ring
     * clockwise from north, which put every team on the island next door whenever a map was built the other way round.
     * Returns null when the island carries no colour we recognise.
     */
    private static int lastColorCount;
    private static String lastColour;

    /** A map with a decorative bed in its lobby (dead centre, well inside the ring of islands) must not make a team. */
    private static List<int[]> dropCentreBeds(List<int[]> pairs) {
        double cx = 0, cz = 0;
        for (int[] p : pairs) { cx += p[0]; cz += p[2]; }
        cx /= pairs.size(); cz /= pairs.size();
        List<Double> dist = new ArrayList<>();
        for (int[] p : pairs) dist.add(Math.hypot(p[0] - cx, p[2] - cz));
        List<Double> sorted = new ArrayList<>(dist);
        java.util.Collections.sort(sorted);
        double median = sorted.get(sorted.size() / 2);
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < pairs.size(); i++) if (dist.get(i) >= median * 0.35) out.add(pairs.get(i));
        return out;
    }

    /** Two beds on one island (a map with a decorative or double bed) are one team, not two. */
    private static List<int[]> mergeSameIsland(List<int[]> pairs) {
        List<int[]> out = new ArrayList<>();
        for (int[] p : pairs) {
            boolean merged = false;
            for (int[] q : out) {
                if (Math.abs(q[0] - p[0]) <= 6 && Math.abs(q[2] - p[2]) <= 6 && Math.abs(q[1] - p[1]) <= 3) { merged = true; break; }
            }
            if (!merged) out.add(p);
        }
        return out;
    }

    /** More beds than teams: keep the ones that actually stand on a team-coloured island. */
    private static List<int[]> mostTeamColoured(Scan s, List<int[]> pairs, int keep) {
        final java.util.Map<int[], Integer> score = new java.util.HashMap<>();
        for (int[] p : pairs) score.put(p, colourCount(s, p));
        List<int[]> sorted = new ArrayList<>(pairs);
        sorted.sort((a, b) -> score.get(b) - score.get(a));
        return new ArrayList<>(sorted.subList(0, keep));
    }

    private static String teamColorOf(Scan s, int[] foot) {
        lastColorCount = colourCount(s, foot);
        return lastColour;
    }

    private static int colourCount(Scan s, int[] foot) {
        int[] tally = new int[16];
        for (int x = foot[0] - 26; x <= foot[0] + 26; x++)
            for (int z = foot[2] - 26; z <= foot[2] + 26; z++)
                for (int y = foot[1] - 12; y <= foot[1] + 12; y++) {
                    int id = s.id(x, y, z);
                    // wool, stained clay, stained glass, stained pane, carpet: the five ways a map paints a team
                    if (id != 35 && id != 159 && id != 95 && id != 160 && id != 171) continue;
                    tally[s.data(x, y, z) & 15]++;
                }
        int best = -1, bestCount = 0, second = 0;
        for (int i = 0; i < 16; i++) {
            if (teamOfDye(i) == null) continue;
            if (tally[i] > bestCount) { second = bestCount; bestCount = tally[i]; best = i; }
            else if (tally[i] > second) second = tally[i];
        }
        lastColour = null;
        if (best < 0 || bestCount < 8) return 0;        // a couple of decorative blocks is not a team colour
        // a clear winner only: if two colours are neck and neck the island is decorated, not painted
        lastColour = teamOfDye(best);
        return bestCount;
    }

    private static double angleCwFromNorth(double dx, double dz) {
        return (Math.toDegrees(Math.atan2(dx, -dz)) + 360.0) % 360.0;
    }

    private static float yawTowards(double fx, double fz, double tx, double tz) {
        return (float) Math.round(((Math.toDegrees(Math.atan2(-(tx - fx), tz - fz)) + 360.0) % 360.0) * 10.0) / 10f;
    }

    private static double[] islandFrame(int[] foot, int[] head, double cx, double cz) {
        double ax = head[0] - foot[0], az = head[2] - foot[2];
        if (ax == 0 && az == 0) { ax = cx - foot[0]; az = cz - foot[2]; }
        if (ax * (cx - foot[0]) + az * (cz - foot[2]) < 0) { ax = -ax; az = -az; }
        double al = Math.hypot(ax, az);
        if (al < 1e-6) al = 1;
        return new double[]{ax / al, az / al};
    }

    /** Connected footprint (8-neighbour) of walkable columns near a start column. */
    private static Set<Long> component(Scan s, int sx, int sz, int maxR, int maxDy) {
        Set<Long> seen = new HashSet<>();
        Integer sy = s.top.get(key(sx, sz));
        if (sy == null) return seen;
        Deque<int[]> q = new ArrayDeque<>();
        seen.add(key(sx, sz));
        q.add(new int[]{sx, sz});
        while (!q.isEmpty()) {
            int[] c = q.poll();
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    int nx = c[0] + dx, nz = c[1] + dz;
                    long k = key(nx, nz);
                    if (seen.contains(k)) continue;
                    Integer t = s.top.get(k);
                    if (t == null) continue;
                    if (Math.abs(nx - sx) > maxR || Math.abs(nz - sz) > maxR || Math.abs(t - sy) > maxDy) continue;
                    seen.add(k);
                    q.add(new int[]{nx, nz});
                }
        }
        return seen;
    }

    private static double[] centroid(Set<Long> cols) {
        double x = 0, z = 0;
        for (long k : cols) { x += (int) (k >> 32); z += (int) k; }
        int n = Math.max(1, cols.size());
        return new double[]{x / n, z / n};
    }

    private static int[] nearestOn(List<int[]> candidates, Set<Long> island, int x, int z) {
        int[] best = null;
        double bd = Double.MAX_VALUE;
        for (int[] c : candidates) {
            if (!island.contains(key(c[0], c[2]))) continue;    // must stand on our own island
            double d = Math.hypot(c[0] - x, c[2] - z);
            if (d < bd) { bd = d; best = c; }
        }
        return best;
    }

    private static int[] walkToEdge(Scan s, double x, double z, double dx, double dz, int floorY, int limit) {
        int lastX = (int) Math.floor(x), lastZ = (int) Math.floor(z), feet = floorY + 1;
        for (int i = 1; i <= limit; i++) {
            int cx = (int) Math.floor(x + dx * i), cz = (int) Math.floor(z + dz * i);
            int f = s.floor(cx, cz, feet);
            if (f < 0) break;
            feet = f; lastX = cx; lastZ = cz;
        }
        return new int[]{lastX, lastZ};
    }

    // ======================================================================================= spots

    private static double[] forgeSpot(Scan s, int[] foot, int[] head, double[] spawn, double cx, double cz, Set<Long> island) {
        double[] axis = islandFrame(foot, head, cx, cz);
        double fx = axis[0], fz = axis[1], rx = -fz, rz = fx;
        int sx = (int) Math.floor(spawn[0]), sz = (int) Math.floor(spawn[2]), floor = (int) spawn[1];
        Set<Long> cells = new HashSet<>();
        for (int x = sx - 14; x <= sx + 14; x++)
            for (int z = sz - 14; z <= sz + 14; z++) {
                if (!island.contains(key(x, z))) continue;
                double fwd = (x + 0.5 - spawn[0]) * fx + (z + 0.5 - spawn[2]) * fz;
                double lat = (x + 0.5 - spawn[0]) * rx + (z + 0.5 - spawn[2]) * rz;
                if (fwd < -1 && Math.abs(lat) <= 4 && s.bottomSlab(x, floor - 1, z)
                        && s.passable(x, floor, z) && s.passable(x, floor + 1, z)) cells.add(key(x, z));
            }
        if (cells.isEmpty()) return null;
        // biggest 4-connected pit
        List<Set<Long>> pits = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (long k : cells) {
            if (seen.contains(k)) continue;
            Set<Long> pit = new HashSet<>();
            Deque<Long> q = new ArrayDeque<>();
            q.add(k); seen.add(k);
            while (!q.isEmpty()) {
                long c = q.poll();
                pit.add(c);
                int x = (int) (c >> 32), z = (int) c;
                for (long n : new long[]{key(x + 1, z), key(x - 1, z), key(x, z + 1), key(x, z - 1)})
                    if (cells.contains(n) && !seen.contains(n)) { seen.add(n); q.add(n); }
            }
            if (pit.size() >= 3 && pit.size() <= 16) pits.add(pit);
        }
        if (pits.isEmpty()) return null;
        Set<Long> pit = pits.get(0);
        for (Set<Long> p : pits) if (p.size() > pit.size()) pit = p;
        double mx = 0, mz = 0;
        for (long k : pit) { mx += (int) (k >> 32); mz += (int) k; }
        mx /= pit.size(); mz /= pit.size();
        long best = pit.iterator().next();
        double bd = Double.MAX_VALUE;
        for (long k : pit) {
            int x = (int) (k >> 32), z = (int) k;
            double d = Math.hypot(x - mx, z - mz);
            if (d < bd) { bd = d; best = k; }
        }
        return new double[]{((int) (best >> 32)) + 0.5, floor, ((int) best) + 0.5};
    }

    private static Map<String, double[]> npcSpots(Scan s, int[] foot, int[] head, double[] spawn, double cx, double cz, Set<Long> island) {
        Map<String, double[]> out = new LinkedHashMap<>();
        double[] axis = islandFrame(foot, head, cx, cz);
        double fx = axis[0], fz = axis[1], rx = -fz, rz = fx;
        int floor = (int) spawn[1];
        // marked pens: a barrier capping a 2-high cell
        List<List<int[]>> pens = new ArrayList<>();
        Set<Long> penCells = new HashSet<>();
        for (int[] b : s.barriers) {
            if (Math.abs(b[0] - foot[0]) > 16 || Math.abs(b[2] - foot[2]) > 16) continue;
            if (!island.contains(key(b[0], b[2]))) continue;
            if (s.passable(b[0], b[1] - 1, b[2]) && s.passable(b[0], b[1] - 2, b[2]) && !s.passable(b[0], b[1] - 3, b[2])) {
                penCells.add(key(b[0], b[2]));
            }
        }
        Set<Long> seen = new HashSet<>();
        for (long k : penCells) {
            if (seen.contains(k)) continue;
            List<int[]> pen = new ArrayList<>();
            Deque<Long> q = new ArrayDeque<>();
            q.add(k); seen.add(k);
            while (!q.isEmpty()) {
                long c = q.poll();
                int x = (int) (c >> 32), z = (int) c;
                pen.add(new int[]{x, floor, z});
                for (long n : new long[]{key(x + 1, z), key(x - 1, z), key(x, z + 1), key(x, z - 1)})
                    if (penCells.contains(n) && !seen.contains(n)) { seen.add(n); q.add(n); }
            }
            if (pen.size() <= 6) pens.add(pen);
        }
        if (!pens.isEmpty()) {
            pens.sort(Comparator.comparingDouble(p -> distTo(p, spawn)));
            List<int[]> a = pens.get(0);
            List<int[]> b = pens.size() > 1 ? pens.get(1) : null;
            if (b != null) {
                double la = latOf(a, spawn, rx, rz), lb = latOf(b, spawn, rx, rz);
                if (la < lb) { List<int[]> t = a; a = b; b = t; }
                out.put("upgrades", penPoint(a, spawn));
                out.put("shop", penPoint(b, spawn));
                return out;
            }
        }
        // symmetric fallback either side of the spawn along the lateral axis
        double[] best = null;
        for (int f = 0; f >= -3; f--) {
            int[] reach = new int[2];
            for (int side = 0; side < 2; side++) {
                int sgn = side == 0 ? 1 : -1;
                int n = 0;
                for (int d = 1; d <= 12; d++) {
                    int x = (int) Math.floor(spawn[0] + rx * sgn * d + fx * f);
                    int z = (int) Math.floor(spawn[2] + rz * sgn * d + fz * f);
                    if (s.floor(x, z, floor) != floor) break;
                    n = d;
                }
                reach[side] = n;
            }
            int d = Math.min(Math.min(reach[0], reach[1]) - 1, 6);
            if (d >= 2) { best = new double[]{d, f}; break; }
        }
        if (best == null) best = new double[]{2, -2};
        double d = best[0], f = best[1];
        out.put("shop", new double[]{Math.floor(spawn[0] - rx * d + fx * f) + 0.5, floor, Math.floor(spawn[2] - rz * d + fz * f) + 0.5, 0});
        out.put("upgrades", new double[]{Math.floor(spawn[0] + rx * d + fx * f) + 0.5, floor, Math.floor(spawn[2] + rz * d + fz * f) + 0.5, 0});
        return out;
    }

    private static double distTo(List<int[]> pen, double[] spawn) {
        double best = Double.MAX_VALUE;
        for (int[] c : pen) best = Math.min(best, Math.hypot(c[0] + 0.5 - spawn[0], c[2] + 0.5 - spawn[2]));
        return best;
    }

    private static double latOf(List<int[]> pen, double[] spawn, double rx, double rz) {
        double lat = 0; int n = 0;
        for (int[] c : pen) { lat += (c[0] + 0.5 - spawn[0]) * rx + (c[2] + 0.5 - spawn[2]) * rz; n++; }
        return lat / Math.max(1, n);
    }

    private static double[] penPoint(List<int[]> pen, double[] spawn) {
        double x = 0, z = 0;
        for (int[] c : pen) { x += c[0] + 0.5; z += c[2] + 0.5; }
        x /= pen.size(); z /= pen.size();
        return new double[]{x, pen.get(0)[1], z, yawTowards(x, z, spawn[0], spawn[2])};
    }

    private static List<double[]> generatorPoints(Scan s, List<int[]> markers, List<int[]> teams) {
        List<int[]> cand = new ArrayList<>();
        for (int[] b : markers) {
            boolean isolated = true;
            for (int[] o : markers) {
                if (o == b) continue;
                if (Math.abs(o[0] - b[0]) <= 2 && Math.abs(o[1] - b[1]) <= 2 && Math.abs(o[2] - b[2]) <= 2) { isolated = false; break; }
            }
            if (!isolated) continue;
            boolean nearBase = false;
            for (int[] t : teams) if (Math.hypot(b[0] - t[0], b[2] - t[2]) <= 12) { nearBase = true; break; }
            if (!nearBase) cand.add(b);
        }
        List<int[]> open = new ArrayList<>();
        for (int[] b : cand) {
            boolean sky = true;
            for (int k = 1; k <= 7; k++) if (!s.passable(b[0], b[1] + k, b[2])) { sky = false; break; }
            if (sky) open.add(b);
        }
        if (!open.isEmpty()) cand = open;
        cand.sort(Comparator.comparingInt(b -> b[1]));
        List<int[]> chosen = new ArrayList<>();
        for (int[] b : cand) {
            boolean clash = false;
            for (int[] c : chosen) if (Math.hypot(b[0] - c[0], b[2] - c[2]) <= 8) { clash = true; break; }
            if (!clash) chosen.add(b);
        }
        List<double[]> out = new ArrayList<>();
        for (int[] b : chosen) out.add(pt(b[0] + 0.5, b[1] + 1, b[2] + 0.5));
        return out;
    }

    private static double[] midSpot(Scan s, double cx, double cz, int y0) {
        int ix = (int) Math.floor(cx), iz = (int) Math.floor(cz);
        double[] best = null;
        double bestKey = Double.MAX_VALUE;
        for (int d = 0; d <= 20; d++) {
            for (int x = ix - d; x <= ix + d; x++)
                for (int z = iz - d; z <= iz + d; z++) {
                    if (Math.max(Math.abs(x - ix), Math.abs(z - iz)) != d) continue;
                    for (int dy = 0; dy <= 6; dy++) {
                        for (int sign = 0; sign < 2; sign++) {
                            int y = y0 + (sign == 0 ? dy : -dy);
                            if (!standable(s, x, y, z)) continue;
                            int patch = 0;
                            for (int ax = x - 2; ax <= x + 2; ax++)
                                for (int az = z - 2; az <= z + 2; az++) if (standable(s, ax, y, az)) patch++;
                            if (patch < 20) continue;
                            double k = Math.hypot(x + 0.5 - cx, z + 0.5 - cz) + 0.5 * Math.abs(dy);
                            if (k < bestKey) { bestKey = k; best = new double[]{x + 0.5, y, z + 0.5}; }
                        }
                    }
                }
            if (best != null && bestKey <= d - 1) break;
        }
        return best == null ? new double[]{ix + 0.5, y0, iz + 0.5} : best;
    }

    private static boolean standable(Scan s, int x, int y, int z) {
        return s.solid(x, y - 1, z) && s.passable(x, y, z) && s.passable(x, y + 1, z);
    }

    /** Hypixel-style waiting box: a glass floor with glass/barrier walls, built in place. */
    private static void buildWaitingBox(World w, int x0, int y0, int z0) {
        int r = 8;
        for (int x = x0 - r; x <= x0 + r; x++)
            for (int z = z0 - r; z <= z0 + r; z++) {
                w.getBlockAt(x, y0, z).setType(Material.GLASS);
                boolean edge = x == x0 - r || x == x0 + r || z == z0 - r || z == z0 + r;
                if (!edge) continue;
                for (int y = y0 + 1; y <= y0 + 4; y++)
                    w.getBlockAt(x, y, z).setType(y <= y0 + 2 ? Material.GLASS : Material.BARRIER);
            }
    }

    // ======================================================================================= output

    private static List<double[]> list(double[]... points) {
        List<double[]> out = new ArrayList<>();
        Collections.addAll(out, points);
        return out;
    }

    private static double[] pt(double x, double y, double z) { return new double[]{round(x), round(y), round(z), 0}; }
    private static double[] pt(double x, double y, double z, float yaw) { return new double[]{round(x), round(y), round(z), yaw}; }
    private static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }

    private static void writeHeader(Result r, int buildLimit) {
        r.lines.add(0, "family " + r.family + ", build-limit " + buildLimit);
    }

    private static void write(Result r) {
        try {
            if (r.folder.exists()) {
                File[] kids = r.folder.listFiles();
                if (kids != null) for (File k : kids) if (k.getName().equals(MapFiles.MAP_YML)) k.delete();
            }
            if (!r.folder.isDirectory() && !r.folder.mkdirs()) throw new IllegalStateException("cannot create " + r.folder);
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (List<double[]> pts : r.points.values())
                for (double[] p : pts) {
                    minX = Math.min(minX, (int) Math.floor(p[0])); maxX = Math.max(maxX, (int) Math.ceil(p[0]));
                    minZ = Math.min(minZ, (int) Math.floor(p[2])); maxZ = Math.max(maxZ, (int) Math.ceil(p[2]));
                }
            StringBuilder sb = new StringBuilder();
            sb.append("# Annotated by the built-in importer (net.h2020.maps.MapImporter). Check points in-game.\n");
            sb.append("name: \"").append(r.name).append("\"\n");
            sb.append("family: ").append(r.family.name()).append('\n');
            sb.append("teams: [").append(r.teams).append("]\n");
            sb.append("build-limit: ").append(96).append('\n');
            if (!r.tags.isEmpty()) {
                sb.append("tags: [");
                for (int i = 0; i < r.tags.size(); i++) { if (i > 0) sb.append(", "); sb.append(r.tags.get(i)); }
                sb.append("]\n");
            }
            sb.append("bounds:\n");
            sb.append("  min: {x: ").append(minX - 15).append(", y: 0, z: ").append(minZ - 15).append("}\n");
            sb.append("  max: {x: ").append(maxX + 15).append(", y: 255, z: ").append(maxZ + 15).append("}\n");
            sb.append("points:\n");
            for (Map.Entry<String, List<double[]>> e : r.points.entrySet()) {
                sb.append("  ").append(e.getKey()).append(":\n");
                for (double[] p : e.getValue()) {
                    sb.append("    - {x: ").append(fmt(p[0])).append(", y: ").append(fmt(p[1]))
                      .append(", z: ").append(fmt(p[2]));
                    if (p[3] != 0) sb.append(", yaw: ").append(fmt(p[3]));
                    sb.append("}\n");
                }
            }
            File out = new File(r.folder, MapFiles.MAP_YML);
            Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8);
            try { w.write(sb.toString()); } finally { w.close(); }
        } catch (Exception e) {
            throw new IllegalStateException("could not write " + r.folder + ": " + e, e);
        }
    }

    private static String fmt(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return String.valueOf(v);
    }
}
