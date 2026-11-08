package net.h2020.core.scoreboard;

import net.h2020.api.stats.CombatStats;
import fr.mrmicky.fastboard.FastBoard;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Trial integration: run a FastBoard next to the existing scoreboard for a few seconds and report whether the 1.8
 * reflection path survives contact with WindSpigot. Nothing else uses this until the answer is known.
 */
public final class FastBoardProbe {
    private FastBoardProbe() {}

    public static void run(JavaPlugin plugin, Player p) {
        try {
            FastBoard board = new FastBoard(p);
            board.updateTitle("§e§lH2020 FASTBOARD");
            List<String> lines = new ArrayList<String>();
            for (int i = 0; i < 8; i++) lines.add("§7line " + i + " §f" + p.getName());
            board.updateLines(lines);
            net.h2020.api.stats.CombatStats.inc("fastboard.create");
            new BukkitRunnable() {
                int n = 0;
                @Override public void run() {
                    if (board.isDeleted() || !p.isOnline()) { cancel(); return; }
                    List<String> l = new ArrayList<String>();
                    for (int i = 0; i < 8; i++) l.add("§7line " + i + " §f" + (n % 10));
                    board.updateLines(l);
                    board.updateTitle("§e§lH2020 " + n);
                    net.h2020.api.stats.CombatStats.inc("fastboard.update");
                    if (++n >= 6) {
                        board.delete();
                        net.h2020.api.stats.CombatStats.inc("fastboard.delete");
                        cancel();
                    }
                }
            }.runTaskTimer(plugin, 20L, 20L);
        } catch (Throwable t) {
            net.h2020.api.stats.CombatStats.inc("fastboard.error");
            plugin.getLogger().warning("[fastboard] " + t);
        }
    }
}
