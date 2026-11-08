package net.h2020.bedwars.world;

import net.h2020.bedwars.util.Text;
import net.minecraft.server.v1_8_R3.IScoreboardCriteria;
import net.minecraft.server.v1_8_R3.PacketPlayOutScoreboardDisplayObjective;
import net.minecraft.server.v1_8_R3.PacketPlayOutScoreboardObjective;
import net.minecraft.server.v1_8_R3.PacketPlayOutScoreboardScore;
import net.minecraft.server.v1_8_R3.Scoreboard;
import net.minecraft.server.v1_8_R3.ScoreboardObjective;
import net.minecraft.server.v1_8_R3.ScoreboardScore;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * "20 ❤" health under every player's name tag during a Bed Wars game. Sent as raw packets on a private objective
 * so it never clashes with core's sidebar/team scoreboard handling.
 */
public final class BelowNameHealth {
    private static final String NAME = "bw_hp";
    private static final int SLOT_BELOW_NAME = 2;

    private final Scoreboard board = new Scoreboard();
    private final ScoreboardObjective objective;
    private final Set<UUID> viewers = new HashSet<>();
    private final Map<String, Integer> lastSent = new HashMap<>();

    public BelowNameHealth() {
        objective = new ScoreboardObjective(board, NAME, IScoreboardCriteria.b);
        objective.setDisplayName("§c❤");
    }

    public void addViewer(Player viewer, Collection<Player> targets) {
        if (!viewers.add(viewer.getUniqueId())) return;
        Text.send(viewer, new PacketPlayOutScoreboardObjective(objective, 0));
        Text.send(viewer, new PacketPlayOutScoreboardDisplayObjective(SLOT_BELOW_NAME, objective));
        for (Player t : targets) Text.send(viewer, scorePacket(t.getName(), health(t)));
    }

    public void removeViewer(Player viewer) {
        if (!viewers.remove(viewer.getUniqueId())) return;
        Text.send(viewer, new PacketPlayOutScoreboardObjective(objective, 1));
    }

    public boolean isViewer(Player p) { return viewers.contains(p.getUniqueId()); }

    /** Pushes changed health values to every viewer. */
    public void update(Collection<Player> viewersNow, Collection<Player> targets) {
        for (Player t : targets) {
            int hp = health(t);
            Integer last = lastSent.get(t.getName());
            if (last != null && last == hp) continue;
            lastSent.put(t.getName(), hp);
            PacketPlayOutScoreboardScore packet = scorePacket(t.getName(), hp);
            for (Player v : viewersNow) if (viewers.contains(v.getUniqueId())) Text.send(v, packet);
        }
    }

    private PacketPlayOutScoreboardScore scorePacket(String entry, int value) {
        ScoreboardScore s = new ScoreboardScore(board, objective, entry);
        s.setScore(value);
        return new PacketPlayOutScoreboardScore(s);
    }

    private static int health(Player p) { return (int) Math.ceil(p.getHealth()); }
}
