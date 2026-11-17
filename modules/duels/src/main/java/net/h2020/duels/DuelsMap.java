package net.h2020.duels;

import net.h2020.api.game.GameFamily;
import net.h2020.api.map.MapKeys;
import net.h2020.api.map.MapTemplate;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The map a duels game is fought on.
 *
 * <p>Two kinds exist. A real one is a Hypixel capture with a {@code map.yml} full of points - that is what the queue
 * prefers, and what the arena must reproduce exactly. A generated one is the fallback for a mode with no capture yet:
 * a walled floor built in code, so a duel can always be played rather than the mode being unavailable.
 *
 * <p>Kept as an interface-facing wrapper rather than two classes because everything downstream (the queue, the admin
 * commands, the replay) only ever asks a MapTemplate for points and an instance world.
 */
public final class DuelsMap implements MapTemplate {

    private final DuelsMode mode;
    /** A real map from the map folder, or null for a generated arena. */
    private final MapTemplate real;
    private DuelsArena live;

    /** A generated arena for this mode. */
    public DuelsMap(DuelsMode mode) { this(mode, null); }

    /** The map the queue handed over, if it is a real one. */
    public DuelsMap(DuelsMode mode, MapTemplate real) {
        this.mode = mode;
        this.real = real;
    }

    public DuelsMode mode() { return mode; }
    public boolean isReal() { return real != null; }
    public MapTemplate real() { return real; }

    @Override public String name() { return real != null ? real.name() : mode.displayName() + " Arena"; }
    @Override public GameFamily family() { return GameFamily.DUELS; }
    @Override public List<Integer> supportedTeamCounts() { return Collections.singletonList(2); }
    @Override public int buildLimitY() { return real != null ? real.buildLimitY() : 200; }
    @Override public Vector boundsMin() { return real != null ? real.boundsMin() : new Vector(-DuelsArena.RADIUS, 0, -DuelsArena.RADIUS); }
    @Override public Vector boundsMax() { return real != null ? real.boundsMax() : new Vector(DuelsArena.RADIUS, 200, DuelsArena.RADIUS); }

    @Override
    public List<Vector> points(String key) {
        if (real != null) {
            List<Vector> p = real.points(key);
            if (p != null && !p.isEmpty()) return p;
        }
        // generated arena answers for itself
        if (MapKeys.WAITING_LOBBY.equals(key)) {
            List<Vector> l = new ArrayList<Vector>();
            l.add(new Vector(0.5, DuelsArena.FLOOR_Y, DuelsArena.RADIUS - 1.5));
            l.add(new Vector(0.5, DuelsArena.FLOOR_Y, -(DuelsArena.RADIUS - 1.5)));
            return l;
        }
        if (MapKeys.DUEL_SPAWN_A.equals(key)) return Collections.singletonList(new Vector(0.5, DuelsArena.FLOOR_Y, DuelsArena.RADIUS - 1.5));
        if (MapKeys.DUEL_SPAWN_B.equals(key)) return Collections.singletonList(new Vector(0.5, DuelsArena.FLOOR_Y, -(DuelsArena.RADIUS - 1.5)));
        return Collections.emptyList();
    }

    @Override public Vector point(String key) {
        List<Vector> p = points(key);
        return p.isEmpty() ? null : p.get(0);
    }

    @Override public float yaw(String key, int index) {
        if (real != null) return real.yaw(key, index);
        if (MapKeys.DUEL_SPAWN_A.equals(key)) return 180f;
        if (MapKeys.DUEL_SPAWN_B.equals(key)) return 0f;
        return 0f;
    }

    @Override
    public Map<String, List<Vector>> allPoints() {
        Map<String, List<Vector>> m = new LinkedHashMap<String, List<Vector>>();
        if (real != null) m.putAll(real.allPoints());
        if (!m.containsKey(MapKeys.MID)) m.put(MapKeys.MID, Collections.singletonList(new Vector(0, DuelsArena.FLOOR_Y, 0)));
        if (!m.containsKey(MapKeys.SPECTATOR)) m.put(MapKeys.SPECTATOR, Collections.singletonList(new Vector(0, DuelsArena.FLOOR_Y + 14, 0)));
        return m;
    }

    /** The two pads, A first, with their facing - what the game teleports to. */
    public List<Location> spawns(World w) {
        List<Location> l = new ArrayList<Location>();
        for (String key : new String[]{MapKeys.DUEL_SPAWN_A, MapKeys.DUEL_SPAWN_B}) {
            Vector v = point(key);
            if (v == null) continue;
            l.add(new Location(w, v.getX(), v.getY(), v.getZ(), yaw(key, 0), 0f));
        }
        if (l.isEmpty() && live != null) {
            l.add(live.spawnA());
            l.add(live.spawnB());
        }
        return l;
    }

    @Override
    public World createInstance(String instanceWorldName) {
        if (real != null) {
            // a real map: an instance copy of the capture's own world, exactly as bedwars and skywars do it
            live = null;
            return real.createInstance(instanceWorldName);
        }
        live = DuelsArena.acquire(mode);
        return live.world();
    }

    @Override
    public void destroyInstance(World world) {
        if (real != null) { try { real.destroyInstance(world); } catch (Throwable ignored) { } return; }
        if (live != null && live.world().equals(world)) { live.destroy(); live = null; }
    }

    /** The arena most recently created for this template, for a caller that needs the spawn points. */
    public DuelsArena lastArena() { return live; }
}
