package net.h2020.bedwars.shop;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Purchased team upgrade levels and the trap queue of one team. */
public final class TeamUpgrades {
    private final Map<Upgrade, Integer> levels = new EnumMap<>(Upgrade.class);
    private final List<Trap> traps = new ArrayList<>();

    public int level(Upgrade u) { Integer l = levels.get(u); return l == null ? 0 : l; }
    public void setLevel(Upgrade u, int level) { levels.put(u, level); }

    public List<Trap> traps() { return traps; }

    public int count(Trap t) {
        int n = 0;
        for (Trap q : traps) if (q == t) n++;
        return n;
    }

    /** Pops the first trap in the queue (null when empty). */
    public Trap pollTrap() { return traps.isEmpty() ? null : traps.remove(0); }
}
