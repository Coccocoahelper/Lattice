package net.h2020.duels;

import net.h2020.api.H2020;
import net.h2020.api.game.Game;
import net.h2020.api.game.GameManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /duel <player> [mode]} and {@code /duel <mode>} the way Hypixel's are used: name a player to challenge them,
 * or name a mode to queue for it. No argument opens the mode menu.
 *
 * <p>Hypixel's real command has a full argument grammar (kit, rounds, map). The parts that change the game are the
 * mode and who you fight, so those are the two it takes; anything else is treated as a mode name and refused with the
 * list.
 */
public final class DuelsCommand implements CommandExecutor, TabCompleter {

    private final DuelsModule module;

    DuelsCommand(DuelsModule module) { this.module = module; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Duels are played by players; use /h2020 create duels_classic for a bot game.");
            return true;
        }
        Player p = (Player) sender;
        if (args.length == 0) {
            menu(p);
            return true;
        }

        // /duel <player> [mode]  |  /duel <mode>
        DuelsMode mode = DuelsMode.byName(args[0]);
        Player target = null;
        if (mode == null) {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                p.sendMessage(ChatColor.RED + "No player called " + args[0] + ", and no mode by that name.");
                p.sendMessage(ChatColor.GRAY + "Modes: " + modeList());
                return true;
            }
            if (args.length > 1) {
                mode = DuelsMode.byName(args[1]);
                if (mode == null) {
                    p.sendMessage(ChatColor.RED + "Unknown mode: " + args[1]);
                    p.sendMessage(ChatColor.GRAY + "Modes: " + modeList());
                    return true;
                }
            }
        } else if (args.length > 1) {
            target = Bukkit.getPlayerExact(args[1]);
        }
        if (mode == null) mode = DuelsMode.CLASSIC;
        queue(p, mode, target);
        return true;
    }

    /** Put the sender into a duel, dragging the target along when there is one. */
    private void queue(Player p, DuelsMode mode, Player target) {
        GameManager gm = H2020.find(GameManager.class);
        if (gm == null) { p.sendMessage(ChatColor.RED + "No game manager."); return; }
        if (target != null && target.equals(p)) target = null;
        if (target != null) {
            DuelsGame tg = module.gameOf(target);
            if (tg != null) {
                p.sendMessage(ChatColor.RED + target.getName() + " is already in a duel.");
                return;
            }
        }
        Game g = gm.queue(p, mode);
        if (g == null) {
            p.sendMessage(ChatColor.RED + "Could not start a " + mode.displayName() + " duel right now.");
            return;
        }
        p.sendMessage(ChatColor.YELLOW + "Queuing " + ChatColor.AQUA + mode.displayName() + ChatColor.YELLOW + " duel"
                + (target == null ? "" : ChatColor.GRAY + " against " + ChatColor.WHITE + target.getName()) + ChatColor.YELLOW + "...");
        if (target != null) {
            final Player t = target;
            final Game game = g;
            t.sendMessage(ChatColor.YELLOW + p.getName() + ChatColor.YELLOW + " challenged you to a "
                    + ChatColor.AQUA + mode.displayName() + ChatColor.YELLOW + " duel!");
            // a challenge is accepted by putting them in the same instance; if it has filled already, queue them
            // into a fresh one of the same mode so the challenge never silently does nothing
            Bukkit.getScheduler().runTask(module.plugin(), new Runnable() {
                @Override public void run() {
                    if (game.players().size() < 2 && game.state() == net.h2020.api.game.GameState.WAITING) {
                        try { game.addPlayer(t); } catch (Throwable ignored) { }
                    } else {
                        GameManager gm = H2020.find(GameManager.class);
                        if (gm != null) gm.queue(t, mode);
                    }
                }
            });
        }
    }

    private void menu(Player p) {
        p.sendMessage(ChatColor.GRAY + "" + ChatColor.STRIKETHROUGH + "--------------------------------");
        p.sendMessage(ChatColor.YELLOW + "" + ChatColor.BOLD + "Duels");
        p.sendMessage("");
        int i = 1;
        for (DuelsMode m : DuelsMode.ALL) {
            p.sendMessage(ChatColor.WHITE + "" + i++ + ". " + ChatColor.AQUA + "/duel " + m.displayName().toLowerCase(Locale.ROOT)
                    + ChatColor.GRAY + " - " + plain(m.description()));
        }
        p.sendMessage("");
        p.sendMessage(ChatColor.GRAY + "Challenge a player: " + ChatColor.WHITE + "/duel <player> <mode>");
        p.sendMessage(ChatColor.GRAY + "" + ChatColor.STRIKETHROUGH + "--------------------------------");
    }

    private static String plain(String s) {
        return ChatColor.stripColor(s);
    }

    private static String modeList() {
        StringBuilder b = new StringBuilder();
        for (DuelsMode m : DuelsMode.ALL) {
            if (b.length() > 0) b.append(ChatColor.GRAY).append(", ");
            b.append(ChatColor.AQUA).append(m.displayName());
        }
        return b.toString();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<String>();
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(last)) out.add(p.getName());
            }
            for (DuelsMode m : DuelsMode.ALL) {
                String n = m.displayName().toLowerCase(Locale.ROOT);
                if (n.startsWith(last)) out.add(n);
            }
        } else if (args.length == 2) {
            for (DuelsMode m : DuelsMode.ALL) {
                String n = m.displayName().toLowerCase(Locale.ROOT);
                if (n.startsWith(last)) out.add(n);
            }
        }
        return out;
    }
}
