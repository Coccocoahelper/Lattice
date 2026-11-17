package net.h2020.core.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every admin subcommand, also reachable on its own.
 *
 * <p>{@code /h2020 games} and {@code /games} run the same code: this class registers one thin root command per
 * subcommand and forwards to {@link AdminCommand} with the subcommand name prepended. The {@code /h2020} root stays,
 * because a console operator and the help text both still use it, and an alias costs nothing.
 *
 * <p>The roots are only created for subcommands that read as a verb on their own. {@code /create} or {@code /fill}
 * would be meaningless without knowing what they belong to, so those stay under {@code /h2020}; the list is explicit
 * rather than automatic so a new subcommand cannot silently claim a common word as a top-level command.
 */
public final class AdminRoots {

    /**
     * Subcommand -> the bare command it also answers to. Only unambiguous, useful-on-their-own names are here.
     * A name colliding with an existing command is skipped at registration rather than overwriting it.
     */
    private static final Map<String, String> ROOTS = new LinkedHashMap<String, String>();

    static {
        // running the server
        ROOTS.put("games", "games");
        ROOTS.put("replay", "replay");
        ROOTS.put("train", "train");
        ROOTS.put("bots", "bots");
        ROOTS.put("watch", "watch");
        ROOTS.put("reload", "lreload");
        ROOTS.put("date", "date");
        ROOTS.put("messages", "messages");
        ROOTS.put("sidebar", "sidebar");
        ROOTS.put("modes", "modes");
        ROOTS.put("ping", "ping");
        // maps
        ROOTS.put("convert", "convert");
        ROOTS.put("tags", "maptags");
        ROOTS.put("maptag", "maptag");
        ROOTS.put("mapimport", "mapimport");
        ROOTS.put("mapimportdir", "mapimportdir");
        ROOTS.put("slimeimport", "slimeimport");
        ROOTS.put("edit", "mapedit");
        ROOTS.put("islandcolors", "islandcolors");
        ROOTS.put("npcprobe", "npcprobe");
        ROOTS.put("lobbyprobe", "lobbyprobe");
        ROOTS.put("blockat", "blockat");
        ROOTS.put("gens", "gens");
        ROOTS.put("setpoi", "setpoi");
        ROOTS.put("setspawn", "setspawn");
        ROOTS.put("npc", "npc");
        ROOTS.put("arena", "arena");
        ROOTS.put("build", "lbuild");
        // players and games
        ROOTS.put("join", "join");
        ROOTS.put("start", "start");
        ROOTS.put("end", "end");
        ROOTS.put("fill", "fill");
        ROOTS.put("rank", "rank");
        ROOTS.put("level", "level");
        ROOTS.put("cheaters", "cheaters");
        ROOTS.put("fillspeed", "fillspeed");
        ROOTS.put("background", "background");
        ROOTS.put("teamchest", "teamchest");
        ROOTS.put("private", "private");
        ROOTS.put("joincode", "joincode");
        ROOTS.put("spectate", "spectate");
    }


    private AdminRoots() {}

    /**
     * Point every root name at the admin tree.
     *
     * <p>The names are declared in plugin.yml (see {@link #ROOTS}); this only binds executors, because Bukkit's
     * PluginCommand is final in 1.8 and declaring them in the file is both simpler and what /help reads.
     */
    public static void register(JavaPlugin plugin, AdminCommand admin) {
        if (plugin == null || admin == null) return;
        int bound = 0;
        List<String> missing = new ArrayList<String>();
        for (Map.Entry<String, String> e : ROOTS.entrySet()) {
            String sub = e.getKey(), root = e.getValue();
            PluginCommand c = plugin.getCommand(root);
            if (c == null) { missing.add(root); continue; }
            c.setExecutor(adminFor(admin, sub));
            c.setTabCompleter(new RootTab(admin, sub));
            c.setDescription("Same as /h2020 " + sub);
            bound++;
        }
        plugin.getLogger().info("[commands] " + bound + " top-level command(s) bound to the admin tree"
                + (missing.isEmpty() ? "" : "; not in plugin.yml: " + missing));
    }

    private static CommandExecutor adminFor(final AdminCommand admin, final String sub) {
        return new CommandExecutor() {
            @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
                String[] all = new String[args.length + 1];
                all[0] = sub;
                System.arraycopy(args, 0, all, 1, args.length);
                return admin.onCommand(sender, command, label, all);
            }
        };
    }

    /** Completes the root's arguments the way the subcommand would, minus the subcommand itself. */
    private static final class RootTab implements TabCompleter {
        private final AdminCommand admin;
        private final String sub;
        RootTab(AdminCommand admin, String sub) { this.admin = admin; this.sub = sub; }
        @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            String[] all = new String[args.length + 1];
            all[0] = sub;
            System.arraycopy(args, 0, all, 1, args.length);
            List<String> out = admin.onTabComplete(sender, command, alias, all);
            return out == null ? new ArrayList<String>() : out;
        }
    }

    /** Every root name this class manages, for help and for a test. */
    public static List<String> roots() { return new ArrayList<String>(ROOTS.values()); }

    /** subcommand -> root name, for the help text. */
    public static Map<String, String> mapping() { return new LinkedHashMap<String, String>(ROOTS); }
}
