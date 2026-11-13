package net.h2020.bots;

import net.h2020.bots.ai.ml.ServerQuestions;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /qanswer - the click target behind the server's multiple-choice questions. */
final class AnswersCommand implements CommandExecutor {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player p = (Player) sender;
        if (args.length < 2) {
            p.sendMessage(ChatColor.GRAY + "Answer with the number, e.g. /" + label + " <id> 2");
            return true;
        }
        try {
            ServerQuestions.answer(p, Long.parseLong(args[0]), Integer.parseInt(args[1]));
        } catch (NumberFormatException e) {
            net.h2020.api.core.Messages.send(p, "bots.answerscommand.that-is-not-an");
        }
        return true;
    }
}
