package net.h2020.core.command;

import net.h2020.api.game.GameType;
import net.h2020.core.Core;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** /play <id> — Hypixel play ids (bedwars_eight_one, solo_insane, ...). */
public final class PlayCommand implements TabExecutor {
    private final Core core;

    public PlayCommand(Core core) { this.core = core; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(net.h2020.api.core.Messages.get("core.command.players-only")); return true; }
        Player p = (Player) sender;
        if (args.length < 1) {
            net.h2020.api.core.Messages.send(p, "core.playcommand.usage-play-game");
            p.sendMessage(net.h2020.api.core.Messages.get("core.playcommand.games","p1",ids()));
            return true;
        }
        GameType type = GameType.byId(alias(args[0]));
        if (type == null) {
            p.sendMessage(net.h2020.api.core.Messages.get("core.playcommand.unknown-game-available","p1",ids()));
            return true;
        }
        core.games.queue(p, type);
        return true;
    }

    /** A few shorthands people type: bw_solo, sw_insane, ... */
    private static String alias(String s) {
        String a = s.toLowerCase();
        switch (a) {
            case "bw_solo": case "bedwars_solo": case "bw1": return "bedwars_eight_one";
            case "bw_doubles": case "bedwars_doubles": case "bw2": return "bedwars_eight_two";
            case "bw_3v3v3v3": case "bw3": return "bedwars_four_three";
            case "bw_4v4v4v4": case "bw4": return "bedwars_four_four";
            case "skywars_solo_normal": case "sw_normal": return "solo_normal";
            case "skywars_solo_insane": case "sw_insane": return "solo_insane";
            case "skywars_teams_normal": return "teams_normal";
            case "skywars_teams_insane": return "teams_insane";
            default: return a;
        }
    }

    private static String ids() {
        StringBuilder sb = new StringBuilder();
        for (GameType t : GameType.values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(t.id());
        }
        return sb.toString();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) for (GameType t : GameType.values()) if (t.id().startsWith(args[0].toLowerCase())) out.add(t.id());
        return out;
    }
}
