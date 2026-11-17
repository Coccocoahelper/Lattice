package net.h2020.duels;

import net.h2020.api.game.GameFamily;
import net.h2020.api.map.MapKeys;
import net.h2020.api.map.MapTemplate;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The map the queue sees for a duels mode. There is no folder behind it: createInstance() builds the arena, and every
 * key a game asks for is answered from the same constants the builder uses, so the arena and the map metadata cannot
 * drift apart.
 */
public final class DuelsMap implements MapTemplate {

    private final DuelsMode mode;
    private DuelsArena live;

    public DuelsMap(DuelsMode mode) { this.mode = mode; }

    public DuelsMode mode() { return mode; }

    @Override public String name() { return mode.displayName(); }
    @Override public GameFamily family() { return GameFamily.DUELS; }
    /**
     * Two teams, one player each: the number the mode reports, because templatesFor() matches this list against
     * GameMode.teams(). Declaring one team here hid every duels map from the queue - createRandom() found no template
     * and every duel failed to start.
     */
    @Override public List<Integer> supportedTeamCounts() { return Collections.singletonList(2); }
    @Override public Vector point(String key) { return null; }
    @Override public float yaw(String key, int index) { return 0f; }

    /** The pads, so a body waiting to be added to the duel stands on the floor rather than in the air above it. */
    @Override
    public List<Vector> points(String key) {
        if (MapKeys.WAITING_LOBBY.equals(key)) {
            List<Vector> l = new ArrayList<Vector>();
            l.add(new Vector(0.5, DuelsArena.FLOOR_Y, DuelsArena.RADIUS - 1.5));
            l.add(new Vector(0.5, DuelsArena.FLOOR_Y, -(DuelsArena.RADIUS - 1.5)));
            return l;
        }
        return Collections.emptyList();
    }

    @Override
    public Map<String, List<Vector>> allPoints() {
        Map<String, List<Vector>> m = new LinkedHashMap<String, List<Vector>>();
        m.put(MapKeys.SPECTATOR, Collections.singletonList(new Vector(0, DuelsArena.FLOOR_Y + 14, 0)));
        m.put(MapKeys.MID, Collections.singletonList(new Vector(0, DuelsArena.FLOOR_Y, 0)));
        return m;
    }

    @Override public int buildLimitY() { return 256; }
    @Override public Vector boundsMin() {
        return new Vector(-DuelsArena.RADIUS, 0, -DuelsArena.RADIUS);
    }
    @Override public Vector boundsMax() {
        return new Vector(DuelsArena.RADIUS, 256, DuelsArena.RADIUS);
    }

    @Override
    public World createInstance(String instanceWorldName) {
        // the world name is the game's business: the arena picks its own so two live duels cannot collide
        live = DuelsArena.acquire(mode);
        return live.world();
    }

    @Override
    public void destroyInstance(World world) {
        if (live != null && live.world().equals(world)) { live.destroy(); live = null; }
    }

    /** The arena most recently created for this template, for a caller that needs the spawn points. */
    public DuelsArena lastArena() { return live; }

    /** Both spawn points of the last arena, A first. */
    public List<Vector> spawns() {
        List<Vector> l = new ArrayList<Vector>();
        if (live != null) {
            l.add(live.spawnA().toVector());
            l.add(live.spawnB().toVector());
        }
        return l;
    }
}
