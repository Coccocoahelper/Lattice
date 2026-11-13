package net.h2020.core.command;

import net.h2020.api.H2020;
import net.h2020.api.game.GameManager;
import net.h2020.api.core.LastGame;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /playagain -- queue for the mode you just finished, like the Play Again item on the end screen. */
public final class PlayAgainCommand implements CommandExecutor {
    /** Shared by /playagain and the Play Again item on the end screen. */
    public static void requeue(Player p) { net.h2020.api.core.PlayAgain.requeue(p); }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!(s instanceof Player)) { net.h2020.api.core.Messages.send(s, "core.playagaincommand.players-only"); return true; }
        requeue((Player) s);
        return true;
    }
}
