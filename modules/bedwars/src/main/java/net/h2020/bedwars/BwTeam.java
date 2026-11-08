package net.h2020.bedwars;

import net.h2020.api.game.GameTeam;
import net.h2020.api.game.TeamColor;
import net.h2020.bedwars.shop.TeamUpgrades;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** One Bed Wars team: base locations, bed state, upgrades and members. */
public final class BwTeam implements GameTeam {
    public final BedwarsGame game;
    public final TeamColor color;
    public final List<Player> members = new ArrayList<>();
    public final TeamUpgrades upgrades = new TeamUpgrades();

    public Location spawn, bedFoot, bedHead, generator, shop, upgradesNpc;
    /** The team chest: punch it to deposit, it is shared with teammates. */
    public Location chest;
    /** Base bounding box for traps / heal pool (null = radius around the bed). */
    public Location baseMin, baseMax;
    public boolean bedAlive = true;
    /** True once the team had players at game start (empty teams are shown as eliminated). */
    public boolean participated;
    public boolean eliminationAnnounced;
    /** Enemies currently inside the base (traps fire on entry, not while standing inside). */
    public final java.util.Set<java.util.UUID> intruders = new java.util.HashSet<>();
    public long lastTrapTick = -100000;
    public net.h2020.bedwars.world.TeamForge forge;

    BwTeam(BedwarsGame game, TeamColor color) { this.game = game; this.color = color; }

    @Override public TeamColor color() { return color; }
    @Override public List<Player> members() { return new ArrayList<>(members); }

    @Override
    public boolean alive() {
        for (Player p : members) {
            BwPlayer bp = game.data(p);
            if (bp != null && !bp.eliminated) return true;
        }
        return false;
    }

    public TeamUpgrades upgrades() { return upgrades; }
    public boolean bedAlive() { return bedAlive; }
    public Location spawn() { return spawn; }
    public Location generator() { return generator; }
    public Location bedFoot() { return bedFoot; }

    /** Members still playing (not eliminated); includes respawning players. */
    public int remaining() {
        int n = 0;
        for (Player p : members) {
            BwPlayer bp = game.data(p);
            if (bp != null && !bp.eliminated) n++;
        }
        return n;
    }

    /** "§cRed" */
    public String coloredName() { return color.chat + color.displayName; }

    public boolean inBase(Location l) {
        if (l == null || l.getWorld() != game.world()) return false;
        if (baseMin != null && baseMax != null) {
            return l.getX() >= baseMin.getX() - 0.5 && l.getX() <= baseMax.getX() + 1.5
                    && l.getY() >= baseMin.getY() - 6 && l.getY() <= baseMax.getY() + 12
                    && l.getZ() >= baseMin.getZ() - 0.5 && l.getZ() <= baseMax.getZ() + 1.5;
        }
        Location c = bedFoot != null ? bedFoot : spawn;
        if (c == null) return false;
        double dx = l.getX() - c.getX(), dz = l.getZ() - c.getZ();
        double r = game.islandRadius();
        return dx * dx + dz * dz <= r * r && Math.abs(l.getY() - c.getY()) <= 10;
    }
}
