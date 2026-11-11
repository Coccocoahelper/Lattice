package net.h2020.replay;

import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

/** /h2020 train start|stop|report|promote|discard|sprint - the self-play arena and the sprint trainer. */
public final class TrainCommand {
    private TrainCommand() {}

    public static boolean run(CommandSender s, String[] a) {
        String sub = a.length > 1 ? a[1].toLowerCase() : "report";
        if (sub.equals("start")) {
            int total = a.length > 2 ? parseInt(a[2], 40) : 40;
            int train = a.length > 3 ? parseInt(a[3], 6) : 6;
            int eval = a.length > 4 ? parseInt(a[4], 4) : 4;
            s.sendMessage("§6[train] §e" + Arena.start(total, train, eval));
            return true;
        }
        if (sub.equals("stop")) { s.sendMessage("§6[train] §e" + Arena.stop()); return true; }
        if (sub.equals("sprint")) {
            String what = a.length > 2 ? a[2].toLowerCase() : "";
            if (what.equals("stop")) { s.sendMessage("§6[trainer] §e" + Trainer.stop()); return true; }
            if (what.equals("status")) {
                for (String l : Trainer.report()) s.sendMessage(l);
                return true;
            }
            int max = a.length > 2 ? parseInt(a[2], 8) : 8;
            // default cap matches Trainer's: the fastest measured setting, 201 games/hour on this host
            int gameSeconds = a.length > 3 ? parseInt(a[3], 60) : 60;
            s.sendMessage("§6[trainer] §e" + Trainer.start(max, gameSeconds));
            return true;
        }
        if (sub.equals("human")) {
            if (a.length < 4) { s.sendMessage("§c/h2020 train human <gameId> <player>"); return true; }
            HumanReplay.Result r = HumanReplay.train(a[2], a[3]);
            s.sendMessage(r.line());
            return true;
        }
        if (sub.equals("hindsight")) {
            int rows = a.length > 2 ? parseInt(a[2], 4000) : 4000;
            Hindsight.pass(rows, true);
            s.sendMessage("§6[train] §ehindsight pass done: " + Hindsight.reportLine());
            return true;
        }
        if (sub.equals("promote")) {
            Gate.Result r = Gate.evaluate();
            s.sendMessage(r.line());
            return true;
        }
        if (sub.equals("discard")) { League.discardChallenger(); s.sendMessage("§6[train] §echallenger discarded"); return true; }
        for (String l : Arena.report()) s.sendMessage(l);
        return true;
    }

    public static List<String> complete(String[] a) {
        List<String> out = new ArrayList<String>();
        if (a.length == 2) {
            for (String c : new String[]{"start", "stop", "report", "promote", "discard", "hindsight", "human", "sprint"})
                if (c.startsWith(a[1].toLowerCase())) out.add(c);
        } else if (a.length == 3 && a[1].equalsIgnoreCase("sprint")) {
            for (String c : new String[]{"stop", "status", "4", "8", "12", "16", "24"})
                if (c.startsWith(a[2].toLowerCase())) out.add(c);
        } else if (a.length == 4 && a[1].equalsIgnoreCase("sprint")) {
            for (String c : new String[]{"30", "60", "90", "120", "180", "240"})
                if (c.startsWith(a[3].toLowerCase())) out.add(c);
        }
        return out;
    }

    private static int parseInt(String v, int def) {
        try { return Integer.parseInt(v); } catch (Throwable t) { return def; }
    }
}
