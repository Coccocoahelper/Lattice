package net.h2020.core.command;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/**
 * In-game /help (replaces Bukkit's): every command of this server on clickable lines (click = put it in the chat box).
 * Players see the game commands; ops also see the recording/admin, bot and Watchdog tools.
 * /help 2 (or /help admin) shows the second page.
 */
public final class HelpMenu implements Listener {

    private static final String[][] PLAY = {
            {"/play bedwars_eight_one", "Bed Wars Solo (also _eight_two, _four_three, _four_four)"},
            {"/play solo_normal", "SkyWars Solo Normal (also solo_insane, teams_normal, teams_insane)"},
            {"/lobby", "Back to the lobby (aliases /l, /hub)"},
            {"/shout ", "Bed Wars: talk to every team in team modes"},
            {"/report ", "Report a player to Watchdog (alias /wdr)"},
    };

    private static final String[][] ADMIN = {
            {"/h2020 games", "List running games with ids and maps"},
            {"/h2020 create bedwars_eight_one Speedway", "Start a game on a chosen map (Treenan, Aquarium, Airshow, Memorial...)"},
            {"/h2020 start ", "Fill a waiting game with bots so it starts now"},
            {"/h2020 join ", "Put a player or bot into a game: /h2020 join <player> <mode>"},
            {"/h2020 watch ", "Spectate a game: /h2020 watch <gameId>"},
            {"/spectate ", "Spectate a player (camera follows them): /spectate <player>"},
            {"/h2020 fillspeed 1", "How fast bots fill lobbies (1 = Hypixel-like 20-60 s)"},
            {"/h2020 cheaters 0", "Percent of queue bots that cheat (Watchdog bans them)"},
            {"/h2020 date today", "Scoreboard date: today's real date (or 2020, or MM/dd/yy)"},
            {"/h2020 rank ", "Set a rank: /h2020 rank <player> MVP_PLUS RED"},
            {"/h2020 bots 15", "Number of idle bots in the lobby"},
    };

    private static final String[][] TOOLS = {
            {"/bot spawn ", "Spawn a bot: /bot spawn [name] [KILLAURA|REACH|FLY|SPEED|SCAFFOLD|...]"},
            {"/bot list", "All bots and what they are doing"},
            {"/bot look ", "One bot's aim, movement and state"},
            {"/bot remove ", "Remove a bot (or all)"},
    };

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage().trim();
        String lower = msg.toLowerCase();
        if (!(lower.equals("/help") || lower.startsWith("/help ") || lower.equals("/?") || lower.startsWith("/? "))) return;
        e.setCancelled(true);
        String arg = msg.contains(" ") ? msg.substring(msg.indexOf(' ') + 1).trim().toLowerCase() : "";
        show(e.getPlayer(), arg.equals("2") || arg.equals("admin") || arg.equals("bots"));
    }

    public static void show(CommandSender s, boolean page2) {
        boolean admin = s.hasPermission("h2020.admin");
        s.sendMessage(ChatColor.GOLD + "" + ChatColor.STRIKETHROUGH + "-----------------------------------------------------");
        if (!page2 || !admin) {
            s.sendMessage(ChatColor.YELLOW + "" + ChatColor.BOLD + "Hypixel 2020 " + ChatColor.GRAY + "- click a command to use it");
            section(s, "Playing", PLAY);
            s.sendMessage(ChatColor.GRAY + "Or use the " + ChatColor.GREEN + "compass" + ChatColor.GRAY + " / the NPCs in the lobby.");
            if (admin) {
                section(s, "Recording & games (admin)", ADMIN);
                line(s, "/help 2", ChatColor.AQUA + "Next page: bots & Watchdog tools", true);
            }
        } else {
            s.sendMessage(ChatColor.YELLOW + "" + ChatColor.BOLD + "Hypixel 2020 " + ChatColor.GRAY + "- bots & Watchdog (page 2)");
            section(s, "Bots & Watchdog (admin)", TOOLS);
            line(s, "/help", ChatColor.AQUA + "Back to page 1", true);
        }
        s.sendMessage(ChatColor.GOLD + "" + ChatColor.STRIKETHROUGH + "-----------------------------------------------------");
    }

    private static void section(CommandSender s, String title, String[][] rows) {
        s.sendMessage(ChatColor.GOLD + title + ":");
        for (String[] r : rows) line(s, r[0], ChatColor.GRAY + r[1], !r[0].endsWith(" ") && r[0].split(" ").length <= 2);
    }

    /** One clickable line. run=true executes the command on click, otherwise it is put in the chat box to finish. */
    private static void line(CommandSender s, String cmd, String desc, boolean run) {
        if (!(s instanceof Player)) { s.sendMessage(ChatColor.YELLOW + cmd.trim() + ChatColor.GRAY + " - " + desc); return; }
        TextComponent c = new TextComponent(TextComponent.fromLegacyText(ChatColor.YELLOW + " " + cmd.trim() + ChatColor.GRAY + " - " + desc));
        c.setClickEvent(new ClickEvent(run ? ClickEvent.Action.RUN_COMMAND : ClickEvent.Action.SUGGEST_COMMAND, cmd));
        c.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder((run ? "Click to run " : "Click to type ") + cmd.trim()).color(net.md_5.bungee.api.ChatColor.YELLOW).create()));
        ((Player) s).spigot().sendMessage(c);
    }
}
