package net.h2020.practice.drill;

import net.h2020.api.H2020;
import net.h2020.api.bot.BotService;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * One line per finished bed-defence run, with the placements that made it.
 *
 * <p>Written to {@code plugins/Lattice/bed-defence-runs.jsonl} so it sits with the rest of the server's data. The
 * {@code kind} field is the whole point: {@code "player"} or {@code "bot"}, decided by the bot service rather than by a
 * name pattern, so a person's times and a bot's times share a file and stay separable. A drill whose data does not say
 * which is which cannot be used to tune the bots toward what a human does.
 *
 * <p>The placements are the run's replay in miniature: milliseconds from the first block, and the cell, which is enough
 * to see the order somebody builds in, whether they seal the closest gap first, and where they hesitate.
 */
public final class RunLog {

    private RunLog() { }

    public static void record(Player p, int level, long ms, int blocks, int wasted, int cells, List<int[]> trace) {
        if (p == null) return;
        String kind = "player";
        try {
            BotService bs = H2020.find(BotService.class);
            if (bs != null && bs.isBot(p)) kind = "bot";
        } catch (Throwable ignored) { }

        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"t\":").append(System.currentTimeMillis())
          .append(",\"player\":\"").append(esc(p.getName())).append('"')
          .append(",\"kind\":\"").append(kind).append('"')
          .append(",\"level\":").append(level)
          .append(",\"ms\":").append(ms)
          .append(",\"blocks\":").append(blocks)
          .append(",\"wasted\":").append(wasted)
          .append(",\"cells\":").append(cells)
          .append(",\"rate\":").append(ms > 0 ? String.format(Locale.ROOT, "%.2f", blocks * 1000.0 / ms) : "0")
          .append(",\"placements\":[");
        if (trace != null) {
            for (int i = 0; i < trace.size(); i++) {
                int[] e = trace.get(i);
                if (i > 0) sb.append(',');
                sb.append('[').append(e[0]).append(',').append(e[1]).append(',').append(e[2]).append(',').append(e[3]).append(']');
            }
        }
        sb.append("]}");

        File f = new File(H2020.plugin().getDataFolder(), "bed-defence-runs.jsonl");
        try (FileWriter w = new FileWriter(f, true)) {
            w.write(sb.toString());
            w.write('\n');
        } catch (IOException ignored) {
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
