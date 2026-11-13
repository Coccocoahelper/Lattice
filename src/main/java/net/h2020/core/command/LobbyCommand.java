package net.h2020.core.command;

import net.h2020.api.game.Game;
import net.h2020.api.game.GameFamily;
import net.h2020.api.game.GameState;
import net.h2020.core.Core;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * /lobby (/l, /hub) [main|bedwars|skywars]. From a running game: "Are you sure? Type /lobby again if you really
 * want to quit." then the game's lobby; from a game lobby: the main lobby.
 */
public final class LobbyCommand implements CommandExecutor {
    private static final long CONFIRM_MS = 10000;
    private final Core core;
    private final Map<UUID, Long> confirm = new HashMap<>();

    public LobbyCommand(Core core) { this.core = core; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(net.h2020.api.core.Messages.get("core.command.players-only")); return true; }
        Player p = (Player) sender;
        Game g = core.games.gameOf(p);
        GameFamily target;
        boolean explicit = args.length > 0;
        if (explicit) {
            String a = args[0].toLowerCase();
            if (a.startsWith("b")) target = GameFamily.BEDWARS;
            else if (a.startsWith("s")) target = GameFamily.SKYWARS;
            else target = null;
        } else if (g != null) {
            target = g.type().family();
        } else {
            target = null; // game lobby -> main lobby, main lobby -> main lobby spawn
        }
        if (g != null && g.state() == GameState.PLAYING && !g.isSpectator(p) && p.getGameMode() != GameMode.SPECTATOR) {
            Long at = confirm.get(p.getUniqueId());
            long now = System.currentTimeMillis();
            if (at == null || now - at > CONFIRM_MS) {
                confirm.put(p.getUniqueId(), now);
                net.h2020.api.core.Messages.send(p, "core.lobbycommand.are-you-sure-type");
                return true;
            }
        }
        confirm.remove(p.getUniqueId());
        if (p.getGameMode() == GameMode.SPECTATOR && g == null) p.setGameMode(GameMode.ADVENTURE);
        core.games.sendToLobby(p, target);
        return true;
    }
}
