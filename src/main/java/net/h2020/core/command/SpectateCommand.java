package net.h2020.core.command;

import net.h2020.api.game.Game;
import net.h2020.core.Core;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * /spectate [player] - watch a player's game with the camera locked on them.
 * <p>
 * With no argument it stops spectating. The camera follows the target through the vanilla spectator target, so this
 * works exactly like a 2020 spectator clicking a player in the tab list: you see their view, and you leave with /lobby
 * or /spectate.
 */
public final class SpectateCommand implements CommandExecutor, TabCompleter {
    private final Core core;

    public SpectateCommand(Core core) { this.core = core; }

    /** Join a game as a spectator (shared by /spectate, /h2020 watch and the games menu). */
    public void spectate(Player p, Game game) {
        if (p == null || game == null) return;
        Game cur = core.games.gameOf(p);
        if (cur != null) core.games.leaveGame(p, cur);
        core.lobby.leaving(p);
        core.scoreboards.clearSidebar(p);
        p.getInventory().clear();
        p.setGameMode(GameMode.SPECTATOR);
        Location to = game.spectatorSpawn();
        if (to != null) p.teleport(to);
        else if (game.world() != null) p.teleport(game.world().getSpawnLocation());
        p.sendMessage(net.h2020.api.core.Messages.get("core.spectatecommand.spectating-lobby-to-leave","p1",game.id(),"p2",game.mapName()));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(net.h2020.api.core.Messages.get("core.command.players-only")); return true; }
        Player p = (Player) sender;
        if (args.length < 1) {
            stop(p);
            return true;
        }
        // a lobby name (game id) is just as valid as a player name: /spectate m420D
        Game byId = null;
        try { byId = net.h2020.api.H2020.find(net.h2020.api.game.GameManager.class).games().stream()
                .filter(g -> g.id().equalsIgnoreCase(args[0])).findFirst().orElse(null); } catch (Throwable ignored) { }
        if (byId != null) { spectate(p, byId); return true; }
        Player target = find(args[0]);
        if (target == null) { p.sendMessage(net.h2020.api.core.Messages.get("core.spectatecommand.no-player-or-game","p1",args[0])); return true; }
        if (target == p) { net.h2020.api.core.Messages.send(p, "core.spectatecommand.you-are-already-looking"); return true; }
        Game game = core.games.gameOf(target);
        if (game == null) {
            p.sendMessage(net.h2020.api.core.Messages.get("core.spectatecommand.is-not-in-a","p1",target.getName()));
            return true;
        }
        Game cur = core.games.gameOf(p);
        if (cur != null) core.games.leaveGame(p, cur);
        core.lobby.leaving(p);
        core.scoreboards.clearSidebar(p);
        p.getInventory().clear();
        p.setGameMode(GameMode.SPECTATOR);
        Location to = target.getLocation();
        if (to.getWorld() != p.getWorld()) {
            Location spawn = game.spectatorSpawn();
            p.teleport(spawn != null ? spawn : game.world().getSpawnLocation());
        }
        p.teleport(target.getLocation());
        try { p.setSpectatorTarget(target); } catch (Throwable ignored) { }   // camera locks on (1.8 API)
        p.sendMessage(net.h2020.api.core.Messages.get("core.spectatecommand.spectating-in-spectate-to","p1",target.getName(),"p2",game.id(),"p3",game.mapName()));
        return true;
    }

    /** Stop spectating: unlock the camera and go back to the lobby. */
    private void stop(Player p) {
        try { p.setSpectatorTarget(null); } catch (Throwable ignored) { }
        Game game = core.games.gameOf(p);
        if (game != null) core.games.leaveGame(p, game);
        core.lobby.leaving(p);
        core.scoreboards.clearSidebar(p);
        p.setGameMode(GameMode.SURVIVAL);
        net.h2020.api.core.Messages.send(p, "core.spectatecommand.no-longer-spectating");
    }

    private Player find(String name) {
        for (Player o : Bukkit.getOnlinePlayers()) if (o.getName().equalsIgnoreCase(name)) return o;
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<String>();
        if (args.length != 1) return out;
        String pre = args[0].toLowerCase();
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o == sender) continue;
            if (!o.getName().toLowerCase().startsWith(pre)) continue;
            out.add(o.getName());
        }
        try {
            net.h2020.api.game.GameManager gm = net.h2020.api.H2020.find(net.h2020.api.game.GameManager.class);
            if (gm != null) for (net.h2020.api.game.Game g : gm.games())
                if (g.id().toLowerCase().startsWith(pre)) out.add(g.id());
        } catch (Throwable ignored) { }
        java.util.Collections.sort(out);
        return out;
    }
}
