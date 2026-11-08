package net.h2020.skywars.game;

import net.h2020.api.game.GameTeam;
import net.h2020.api.game.TeamColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A SkyWars team (a single player in solo modes). Bound to one island. */
final class SkywarsTeam implements GameTeam {
    private final SkywarsGame game;
    final TeamColor color;
    final Island island;
    final List<UUID> members = new ArrayList<>();

    SkywarsTeam(SkywarsGame game, TeamColor color, Island island) {
        this.game = game; this.color = color; this.island = island;
    }

    @Override public TeamColor color() { return color; }

    @Override public List<Player> members() {
        List<Player> l = new ArrayList<>();
        for (UUID u : members) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) l.add(p);
        }
        return l;
    }

    @Override public boolean alive() {
        for (UUID u : members) if (game.isAlive(u)) return true;
        return false;
    }
}
